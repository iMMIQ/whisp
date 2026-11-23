// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkLocksTest {
    @Test
    fun distinctWorksExecuteConcurrentlyWhileSameWorkSerializes() = runIsolatedTest { scope ->
        val locks = WorkLocks()
        val gateA = Gate()
        val activeB = AtomicInteger(0)

        val jobA = scope.async {
            locks.withLock("work-a") { gateA.arrive() }
        }
        gateA.awaitArrival()

        // work-b must not block on work-a's lock.
        withTimeout(2_000) {
            locks.withLock("work-b") { activeB.incrementAndGet() }
        }
        assertEquals(1, activeB.get())

        // A second work-a operation must wait until the first completes.
        val order = mutableListOf<String>()
        val jobA2 = scope.async {
            locks.withLock("work-a") { synchronized(order) { order += "second" } }
        }
        delay(50)
        synchronized(order) { assertTrue(order.isEmpty()) }
        synchronized(order) { order += "first-releasing" }
        gateA.open()
        awaitAll(jobA, jobA2)
        assertEquals(listOf("first-releasing", "second"), order)
    }
}
