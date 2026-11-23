// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProgressWriterTest {
    @Test
    fun burstsKeepTheLatestPositionForEveryWork() = runIsolatedTest { scope ->
        val saved = mutableListOf<ProgressEntity>()
        val writer = ProgressWriter(scope, { saved += it })
        writer.save(ProgressEntity("a", "a1", 100, 1))
        writer.save(ProgressEntity("b", "b1", 200, 2))
        writer.save(ProgressEntity("a", "a2", 300, 3))
        writer.flush()
        assertEquals(listOf("b", "a"), saved.map { it.workId })
        assertEquals(listOf(200L, 300L), saved.map { it.positionMs })
        assertEquals("a2", saved.last().trackId)
    }

    @Test
    fun updatesDuringAnInFlightWriteAreNotLostOrReordered() = runIsolatedTest { scope ->
        val gate = Gate()
        val saved = mutableListOf<ProgressEntity>()
        val writer =
            ProgressWriter(
                scope,
                {
                    if (saved.isEmpty()) gate.arrive()
                    saved += it
                },
            )
        writer.save(ProgressEntity("a", "a1", 100, 1))
        val flush = scope.async { writer.flush() }
        gate.awaitArrival()
        writer.save(ProgressEntity("a", "a2", 900, 2))
        writer.save(ProgressEntity("b", "b1", 200, 3))
        gate.open()
        flush.await()
        assertEquals(listOf(100L, 900L, 200L), saved.map { it.positionMs })
    }

    @Test
    fun aFailedWriteNeverOverwritesANewerPendingPosition() = runIsolatedTest { scope ->
        var failWrites = true
        val saved = mutableListOf<ProgressEntity>()
        val writer =
            ProgressWriter(
                scope,
                {
                    if (failWrites) throw IOException("disk")
                    saved += it
                },
            )
        writer.save(ProgressEntity("a", "a1", 100))
        try {
            writer.flush()
            fail("Expected write failure")
        } catch (_: IOException) {}
        writer.save(ProgressEntity("a", "a2", 900))
        writer.save(ProgressEntity("b", "b1", 200))
        failWrites = false
        writer.flush()
        assertEquals(listOf(900L, 200L), saved.map { it.positionMs })
    }

    @Test
    fun duplicateCallbacksAreIgnoredButReturningToAnotherWorkIsSaved() = runIsolatedTest { scope ->
        val saved = mutableListOf<ProgressEntity>()
        val writer = ProgressWriter(scope, { saved += it })
        writer.save(ProgressEntity("a", "a1", 100, 1))
        writer.flush()
        writer.save(ProgressEntity("a", "a1", 100, 2))
        writer.flush()
        assertEquals(1, saved.size)
        writer.save(ProgressEntity("b", "b1", 100, 3))
        writer.flush()
        writer.save(ProgressEntity("a", "a1", 100, 4))
        writer.flush()
        assertEquals(listOf("a", "b", "a"), saved.map { it.workId })
    }

    @Test
    fun immediateSavePersistsWithoutDebounceDelay() = runIsolatedTest { scope ->
        val written = CompletableDeferred<ProgressEntity>()
        val writer = ProgressWriter(scope, { written.complete(it) })
        writer.saveImmediate(ProgressEntity("a", "a1", 777, 1))
        val entity = withTimeout(1000) { written.await() }
        assertEquals(777L, entity.positionMs)
    }
}
