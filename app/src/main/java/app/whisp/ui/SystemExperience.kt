// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

internal val LocalTouchFeedback = staticCompositionLocalOf { true }

internal enum class HapticKind {
    None,
    Tick,
    Confirm,
}

/** Feedback only accompanies explicit actions, never playback or subtitle following. */
internal class WhispHaptics(private val view: View, private val enabled: Boolean) {
    fun confirm() {
        if (enabled)
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.CONTEXT_CLICK,
            )
    }

    fun tick() {
        if (enabled)
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
                else HapticFeedbackConstants.CLOCK_TICK,
            )
    }

    fun reject() {
        if (enabled)
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
                else HapticFeedbackConstants.LONG_PRESS,
            )
    }
}

@Composable
internal fun rememberWhispHaptics(): WhispHaptics {
    val view = LocalView.current
    val enabled = LocalTouchFeedback.current
    return remember(view, enabled) { WhispHaptics(view, enabled) }
}

internal fun openAudioOutput(context: Context) {
    try {
        if (
            Build.VERSION.SDK_INT >= 34 &&
                MediaRouter2.getInstance(context).showSystemOutputSwitcher()
        )
            return
        context.startActivity(
            Intent(
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME
                else Settings.ACTION_SOUND_SETTINGS,
            ),
        )
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "无法打开系统音频面板", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "无法打开系统音频面板", Toast.LENGTH_SHORT).show()
    }
}
