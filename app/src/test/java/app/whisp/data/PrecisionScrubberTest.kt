// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import app.whisp.ui.PrecisionScrubber
import org.junit.Assert.*
import org.junit.Test

class PrecisionScrubberTest {
    @Test
    fun switchingScaleDoesNotJumpAndFineMovementIsMuchSmaller() {
        val drag = PrecisionScrubber(3_600_000, 300f, 150f, 30f, 36f)
        assertEquals(1_800_000, drag.move(150f, 80f))
        assertTrue(drag.fine)
        assertEquals(1_802_000, drag.move(160f, 80f))
        assertEquals(1_802_000, drag.move(160f, 30f))
        assertFalse(drag.fine)
        assertEquals(1_922_000, drag.move(170f, 30f))
    }

    @Test
    fun fineWindowAndSeekingAreClampedAtBothEnds() {
        val nearStart = PrecisionScrubber(3_600_000, 300f, 0f, 30f, 36f)
        nearStart.move(0f, 80f)
        assertEquals(0, nearStart.rangeStart)
        assertEquals(0, nearStart.move(-20f, 80f))
        val nearEnd = PrecisionScrubber(3_600_000, 300f, 300f, 30f, 36f)
        nearEnd.move(300f, 80f)
        assertEquals(3_600_000, nearEnd.rangeEnd)
        assertEquals(3_600_000, nearEnd.move(320f, 80f))
    }

    @Test
    fun shortAudioDoesNotProduceAnInvalidRange() {
        val drag = PrecisionScrubber(10_000, 300f, 150f, 30f, 36f)
        drag.move(150f, 80f)
        assertEquals(0, drag.rangeStart)
        assertEquals(10_000, drag.rangeEnd)
        assertEquals(10_000, drag.move(400f, 80f))
    }

    @Test
    fun grabbingNearThumbPreservesInitialPositionWithoutJump() {
        val drag =
            PrecisionScrubber(
                durationMs = 3_600_000,
                widthPx = 300f,
                startX = 160f,
                startY = 30f,
                fineThresholdPx = 36f,
                initialPositionMs = 1_800_000,
            )
        assertEquals(1_800_000, drag.positionMs)
        assertEquals(1_800_000, drag.move(160f, 30f))
        assertEquals(1_920_000, drag.move(170f, 30f))
    }
}
