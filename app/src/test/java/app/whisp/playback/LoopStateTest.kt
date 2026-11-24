// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import org.junit.Assert.*
import org.junit.Test

class LoopStateTest {
    @Test
    fun validSegmentIsKept() {
        assertEquals(LoopState(1_000, 5_000), LoopState.validate(1_000, 5_000, 60_000, true))
    }

    @Test
    fun endIsClampedToDuration() {
        assertEquals(LoopState(1_000, 60_000), LoopState.validate(1_000, 90_000, 60_000, true))
    }

    @Test
    fun unknownDurationKeepsRawEnd() {
        assertEquals(LoopState(1_000, 90_000), LoopState.validate(1_000, 90_000, null, true))
    }

    @Test
    fun invalidSegmentsAreRejected() {
        assertNull(LoopState.validate(-1, 5_000, 60_000, true))
        assertNull(LoopState.validate(5_000, 5_000, 60_000, true))
        assertNull(LoopState.validate(5_000, 5_499, 60_000, true))
        assertNull(LoopState.validate(1_000, 5_000, 60_000, false))
        assertNull(LoopState.validate(70_000, 90_000, 60_000, true))
    }

    @Test
    fun exactlyFiveHundredMillisecondsIsAccepted() {
        assertEquals(LoopState(1_000, 1_500), LoopState.validate(1_000, 1_500, 60_000, true))
    }
}

class SleepTimerTest {
    @Test
    fun zeroMinutesClearsDeadline() {
        assertEquals(0, SleepTimer.deadlineMs(1_000_000, 0))
    }

    @Test
    fun deadlineIsMinutesAfterNow() {
        assertEquals(1_900_000, SleepTimer.deadlineMs(1_000_000, 15))
    }

    @Test
    fun minutesAreClamped() {
        assertEquals(1_000_000 + 180 * 60_000L, SleepTimer.deadlineMs(1_000_000, 999))
        assertEquals(0, SleepTimer.deadlineMs(1_000_000, -5))
    }

    @Test
    fun remainingMinutesUsesMonotonicClockReference() {
        val nowElapsed = 2_000_000L
        val deadline = SleepTimer.deadlineMs(nowElapsed, 15)
        assertEquals(15, SleepTimer.remainingMinutes(deadline, nowElapsed))
        assertEquals(15, SleepTimer.remainingMinutes(deadline, nowElapsed + 1_000L))
        assertEquals(1, SleepTimer.remainingMinutes(deadline, deadline - 1_000L))
        assertEquals(0, SleepTimer.remainingMinutes(deadline, deadline))
        assertEquals(0, SleepTimer.remainingMinutes(0L, nowElapsed))
    }
}
