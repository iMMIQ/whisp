// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class SingleflightTest {
    @Test
    fun concurrentRequestsForTheSameKeyShareOneExecution() = runIsolatedTest { scope ->
        val flights = Singleflight<String, String>(scope, 4)
        val executions = AtomicInteger(0)
        val results =
            (1..16)
                .map {
                    scope.async {
                        flights.run("cover:id") {
                            executions.incrementAndGet()
                            delay(50)
                            "bytes"
                        }
                    }
                }
                .awaitAll()
        assertEquals(1, executions.get())
        assertTrue(results.all { it == "bytes" })
    }

    @Test
    fun differentKeysRunIndependently() = runIsolatedTest { scope ->
        val flights = Singleflight<String, String>(scope, 4)
        val first = scope.async { flights.run("a") { "one" } }
        val second = scope.async { flights.run("b") { "two" } }
        assertEquals("one", first.await())
        assertEquals("two", second.await())
    }

    @Test
    fun failedExecutionDoesNotPoisonLaterRequests() = runIsolatedTest { scope ->
        val flights = Singleflight<String, String>(scope, 4)
        val attempts = AtomicInteger(0)
        try {
            flights.run("cover:id") {
                attempts.incrementAndGet()
                throw IOException("transient")
            }
            fail("Expected failure")
        } catch (_: IOException) {}
        assertEquals("recovered", flights.run("cover:id") { "recovered" })
        assertEquals(1, attempts.get())
    }

    @Test
    fun completedKeyCanBeFetchedAgain() = runIsolatedTest { scope ->
        val flights = Singleflight<String, Int>(scope, 4)
        val calls = AtomicInteger(0)
        repeat(50) { round ->
            assertEquals(round * 2 + 1, flights.run("k") { calls.incrementAndGet() })
            assertEquals(round * 2 + 2, flights.run("k") { calls.incrementAndGet() })
        }
        assertEquals(100, calls.get())
    }

    @Test
    fun cancelledKeyCanBeFetchedAgain() = runIsolatedTest { scope ->
        val flights = Singleflight<String, String>(scope, 4)
        val gate = Gate()
        val first = scope.async {
            try {
                flights.run("k") {
                    gate.arrive()
                    "stale"
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
        gate.awaitArrival()
        flights.cancelAndJoin("k")
        try {
            first.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertEquals("fresh", flights.run("k") { "fresh" })
    }

    @Test
    fun joinAllWaitsForInflightWork() = runIsolatedTest { scope ->
        val flights = Singleflight<String, String>(scope, 4)
        val release = Channel<Unit>(1)
        val first = scope.async {
            flights.run("k") {
                release.receive()
                "done"
            }
        }
        val joined = scope.async { flights.joinAll() }
        release.send(Unit)
        joined.await()
        assertEquals("done", first.await())
    }
}
