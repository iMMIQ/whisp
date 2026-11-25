// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.whisp.data.*
import java.util.LinkedHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Bounded LRU cache and per-work-part request deduplication for DLsite detail tabs. */
class DlsiteDetailsLoader(
    private val scope: CoroutineScope,
    private val library: LibraryStore,
    private val defaultClient: DlsiteClient,
) {
    companion object {
        private const val MAX_ENTRIES = 6
        private const val PRODUCT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val STATS_TTL_MS = 24L * 60 * 60 * 1000

        fun isFresh(
            cached: DlsiteDetails?,
            part: String,
            now: Long = System.currentTimeMillis(),
        ): Boolean {
            if (cached == null) return false
            return when (part) {
                "product" ->
                    cached.completeProduct && now - cached.productFetchedAt < PRODUCT_TTL_MS
                "description" -> cached.descriptionFetchedAt > 0
                "statistics" -> {
                    val stats = cached.statistics
                    stats != null && now - stats.fetchedAt < STATS_TTL_MS
                }
                else -> false
            }
        }
    }

    val states = MutableStateFlow<Map<String, DlsiteUiState>>(emptyMap())
    private val cache = LinkedHashMap<String, DlsiteUiState>(MAX_ENTRIES + 1, 0.75f, true)
    private val activeJobs = mutableMapOf<Pair<String, String>, Job>()

    fun accept(workId: String, details: DlsiteDetails) {
        val current = cache[workId]
        val merged = details.preserving(current?.details)
        put(workId, (current ?: DlsiteUiState()).copy(details = merged))
    }

    fun load(
        workId: String,
        rj: String,
        part: String = "product",
        seed: DlsiteDetails? = null,
        force: Boolean = false,
        client: DlsiteClient = defaultClient,
    ) {
        val key = workId to part
        if (activeJobs[key]?.isActive == true) return
        val existing = cache[workId]
        val baseline = existing?.details ?: seed
        if (seed != null && existing?.details == null) {
            put(workId, (existing ?: DlsiteUiState()).copy(details = seed))
        }
        if (!force && isFresh(baseline, part)) return
        put(
            workId,
            (cache[workId] ?: DlsiteUiState(baseline)).let {
                it.copy(loading = it.loading + part, errors = it.errors - part)
            },
        )
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val stored = library.readDlsiteDetails(workId)
                    val mergedSeed =
                        (cache[workId]?.details ?: seed).let { current ->
                            stored?.preserving(current) ?: current
                        }
                    if (!force && isFresh(mergedSeed, part)) {
                        if (mergedSeed != null && cache.containsKey(workId)) {
                            put(workId, cache[workId]!!.copy(details = mergedSeed))
                        }
                        return@launch
                    }
                    val fetched: DlsiteDetails =
                        withContext(Dispatchers.IO) {
                            when (part) {
                                "description" ->
                                    DlsiteDetails(
                                        rj = rj,
                                        description = client.description(rj),
                                        descriptionFetchedAt = System.currentTimeMillis(),
                                    )
                                "statistics" ->
                                    DlsiteDetails(rj = rj, statistics = client.statistics(rj))
                                else -> client.work(rj).details
                            }
                        }
                    val combined = fetched.preserving(cache[workId]?.details ?: mergedSeed)
                    library.saveDlsiteDetails(workId, combined)
                    val me = currentCoroutineContext()[Job]
                    if (activeJobs[key] === me && cache.containsKey(workId)) {
                        put(workId, cache[workId]!!.copy(details = combined))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val me = currentCoroutineContext()[Job]
                    if (activeJobs[key] === me && cache.containsKey(workId)) {
                        put(
                            workId,
                            cache[workId]!!.let {
                                it.copy(errors = it.errors + (part to (e.message ?: "读取失败")))
                            },
                        )
                    }
                } finally {
                    val me = currentCoroutineContext()[Job]
                    if (activeJobs[key] === me) {
                        activeJobs.remove(key)
                        cache[workId]?.let { put(workId, it.copy(loading = it.loading - part)) }
                    }
                }
            }
        activeJobs[key] = job
        job.start()
    }

    private fun put(workId: String, state: DlsiteUiState) {
        cache[workId] = state
        while (cache.size > MAX_ENTRIES) {
            val evicted = cache.entries.iterator().next().key
            cache.remove(evicted)
            activeJobs.keys
                .filter { it.first == evicted }
                .forEach { key ->
                    activeJobs.remove(key)?.cancel()
                }
        }
        states.value = cache.toMap()
    }
}
