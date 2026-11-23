// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-work mutex registry so slow operations (such as offline attachment downloads) on one work
 * never stall imports, cover edits, or metadata updates on unrelated works.
 */
internal class WorkLocks {
    private class Entry {
        val mutex = Mutex()
        var waiters = 0
    }

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(workId: String, block: suspend () -> T): T {
        val entry =
            synchronized(entries) {
                entries.getOrPut(workId) { Entry() }.also { it.waiters++ }
            }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                entry.waiters--
                if (entry.waiters == 0 && entries[workId] === entry) entries.remove(workId)
            }
        }
    }
}
