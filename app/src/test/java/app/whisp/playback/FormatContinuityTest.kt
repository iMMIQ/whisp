// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** DecoderReuseEvaluation touches framework text utilities, so this runs on Robolectric. */
@RunWith(RobolectricTestRunner::class)
class FormatContinuityTest {
    private val empty = Format.Builder().build()

    @Test
    fun nullEvaluationMeansBypass() {
        assertEquals(FormatContinuity.BYPASS, formatContinuity(null))
    }

    @Test
    fun discardedEvaluationMeansRebuild() {
        assertEquals(
            FormatContinuity.REBUILD,
            formatContinuity(
                DecoderReuseEvaluation(
                    "c2 decoder",
                    empty,
                    empty,
                    DecoderReuseEvaluation.REUSE_RESULT_NO,
                    0,
                ),
            ),
        )
    }

    @Test
    fun reusableEvaluationMeansReuse() {
        assertEquals(
            FormatContinuity.REUSE,
            formatContinuity(
                DecoderReuseEvaluation(
                    "c2 decoder",
                    empty,
                    empty,
                    DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH,
                    0,
                ),
            ),
        )
    }
}
