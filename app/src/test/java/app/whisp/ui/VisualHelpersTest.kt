// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.toArgb
import app.whisp.data.LibrarySort
import app.whisp.data.ProgressEntity
import app.whisp.data.Settings
import app.whisp.data.TrackSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisualHelpersTest {
    @Test
    fun ambientSamplingClampsBrightCoversToNocturnalRange() {
        val bright =
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(AndroidColor.rgb(255, 240, 120))
            }
        try {
            val sampled = sampleAmbientColor("test-bright-cover", bright)
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(sampled.toArgb(), hsv)
            assertTrue("Ambient saturation must stay muted (${hsv[1]})", hsv[1] in 0.17f..0.53f)
            assertTrue("Ambient brightness must stay nocturnal (${hsv[2]})", hsv[2] in 0.17f..0.35f)
            assertEquals(sampled, cachedAmbientColor("test-bright-cover"))
        } finally {
            bright.recycle()
        }
    }

    @Test
    fun amoledSettingDefaultsOffAndPreservesNormalization() {
        val defaults = Settings()
        assertFalse(defaults.amoledBlack)
        assertTrue(defaults.copy(amoledBlack = true).normalized().amoledBlack)
        assertEquals(10, defaults.skipSeconds)
        assertEquals(15, defaults.copy(skipSeconds = 15).normalized().skipSeconds)
        assertEquals(10, defaults.copy(skipSeconds = 99).normalized().skipSeconds)
        assertEquals(
            LibrarySort.RECENT,
            defaults.copy(librarySort = LibrarySort.RECENT).normalized().librarySort,
        )
        assertEquals(
            LibrarySort.ADDED_DESC,
            defaults.copy(librarySort = "invalid").normalized().librarySort,
        )
    }

    @Test
    fun workProgressFractionComputesRealRatioInsteadOfFullBar() {
        val tracks =
            listOf(
                TrackSummary("t1", "w1", 1, 60_000),
                TrackSummary("t2", "w1", 2, 60_000),
            )
        assertNull(workProgressFraction(tracks, null))
        assertNull(workProgressFraction(tracks, ProgressEntity("w1", "t1", 0)))
        assertEquals(
            0.25f,
            workProgressFraction(tracks, ProgressEntity("w1", "t1", 30_000))!!,
            0.001f,
        )
        assertEquals(
            0.75f,
            workProgressFraction(tracks, ProgressEntity("w1", "t2", 30_000))!!,
            0.001f,
        )
        val unknownDur = listOf(TrackSummary("t1", "w1", 1, 0))
        val frac = workProgressFraction(unknownDur, ProgressEntity("w1", "t1", 5_000))!!
        assertTrue("Unknown duration must not display as 100% completed ($frac)", frac < 0.5f)
    }
}
