// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.toArgb
import app.whisp.data.Settings
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
    }
}
