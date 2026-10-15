// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Coalesces concurrent requests for the same key into a single execution. Cleanup belongs to the
 * fetch itself: a screen can disappear before the shared fetch completes, and a later request must
 * never reuse a deleted entry.
 */
internal class Singleflight<K, V>(private val scope: CoroutineScope, permits: Int) {
    private val lanes = Semaphore(permits)
    private val ongoing = mutableMapOf<K, Deferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val task =
            synchronized(ongoing) {
                // Await can resume before the completion handler below removes
                // the entry, so a finished task must never be shared again.
                ongoing[key]?.let { if (it.isCompleted) ongoing.remove(key, it) }
                ongoing[key]
                    ?: scope
                        .async(start = CoroutineStart.LAZY) { lanes.withPermit { block() } }
                        .also { task ->
                            ongoing[key] = task
                            task.invokeOnCompletion {
                                synchronized(ongoing) {
                                    if (ongoing[key] === task) ongoing.remove(key)
                                }
                            }
                            task.start()
                        }
            }
        return task.await()
    }

    fun active(): List<Deferred<V>> = synchronized(ongoing) { ongoing.values.toList() }

    suspend fun cancelAndJoin(key: K) {
        synchronized(ongoing) { ongoing[key] }?.cancelAndJoin()
    }

    suspend fun joinAll() {
        // Let already requested work finish before clearing so it cannot restore stale cache.
        active().forEach {
            try {
                it.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }
}
