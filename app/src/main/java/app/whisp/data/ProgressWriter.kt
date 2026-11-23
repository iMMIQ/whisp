// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retain the latest pending position per work, including when several works change. */
internal class ProgressWriter(
    scope: CoroutineScope,
    private val write: suspend (ProgressEntity) -> Unit,
    private val failed: (Exception) -> Unit = {},
) {
    private val pending = LinkedHashMap<String, ProgressEntity>()
    private val signal = Channel<Boolean>(Channel.CONFLATED)
    private val writes = Mutex()
    private var latest: ProgressEntity? = null
    private var immediateRequested = false

    init {
        scope.launch {
            for (immediate in signal) {
                val skipDelay =
                    synchronized(pending) {
                        val value = immediate || immediateRequested
                        immediateRequested = false
                        value
                    }
                // Merge the callbacks produced by a single pause/seek/transition unless shutting
                // down.
                if (!skipDelay) delay(150)
                try {
                    flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(e)
                    delay(5000)
                    signal.trySend(false)
                }
            }
        }
    }

    fun save(progress: ProgressEntity) = enqueue(progress, immediate = false)

    fun saveImmediate(progress: ProgressEntity) = enqueue(progress, immediate = true)

    private fun enqueue(progress: ProgressEntity, immediate: Boolean) =
        synchronized(pending) {
            if (
                latest?.let {
                    it.workId == progress.workId &&
                        it.trackId == progress.trackId &&
                        it.positionMs == progress.positionMs
                } == true
            ) {
                if (immediate && pending.isNotEmpty()) {
                    immediateRequested = true
                    signal.trySend(true)
                }
                return@synchronized
            }
            latest = progress
            pending.remove(progress.workId)
            pending[progress.workId] = progress
            if (immediate) immediateRequested = true
            signal.trySend(immediate)
            Unit
        }

    suspend fun flush() = writes.withLock {
        while (true) {
            val progress =
                synchronized(pending) {
                    pending.entries.firstOrNull()?.value?.also { pending.remove(it.workId) }
                } ?: break
            try {
                write(progress)
            } catch (e: Exception) {
                synchronized(pending) { pending.putIfAbsent(progress.workId, progress) }
                throw e
            }
        }
    }
}
