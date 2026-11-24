// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.whisp.ui.WhispRoot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val OPEN_PLAYER = "app.whisp.OPEN_PLAYER"
    }

    private var playerRequest by mutableIntStateOf(0)
    private var inPipMode by mutableStateOf(false)
    private var hideRecents = true
    private var activityResumed = false

    private fun updateBackgroundProtection() {
        if (Build.VERSION.SDK_INT < 33) return
        // Some OEM overview screens use a live surface or retain the previous thumbnail.
        // Protect only while paused, keeping user screenshots available in the foreground.
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (hideRecents && !activityResumed) window.addFlags(flag) else window.clearFlags(flag)
    }

    private var notificationRequested = false

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || notificationRequested) return
        if (
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationRequested = true
            return
        }
        notificationRequested = true
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerRequest = savedInstanceState?.getInt("player-request") ?: 0
        notificationRequested = savedInstanceState?.getBoolean("notification-requested") ?: false
        if (savedInstanceState == null && intent.action == OPEN_PLAYER) playerRequest++
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val graph = (application as WhispApp).graph
        if (Build.VERSION.SDK_INT >= 33) {
            // Keep cold-start previews private until the persisted preference is available.
            setRecentsScreenshotEnabled(false)
            updateBackgroundProtection()
            lifecycleScope.launch {
                graph.settings.settingsUpdates
                    .map { it.hideRecents }
                    .distinctUntilChanged()
                    .collect {
                        hideRecents = it
                        setRecentsScreenshotEnabled(!it)
                        updateBackgroundProtection()
                    }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            lifecycleScope.launch {
                combine(
                        graph.playback.state.map {
                            Triple(it.hasVideo, it.playing, it.videoAspectRatio)
                        },
                        graph.playback.audioOnly,
                    ) { (hasVideo, playing, aspectRatio), audioOnly ->
                        Triple(hasVideo && playing && !audioOnly, aspectRatio, Unit)
                    }
                    .distinctUntilChanged()
                    .collect { (eligible, aspectRatio, _) ->
                        updateAutoPipParams(
                            eligible && graph.playback.isVideoOutputActive(),
                            aspectRatio,
                        )
                    }
            }
        }
        setContent {
            WhispRoot(
                graph = graph,
                playerRequest = playerRequest,
                inPipMode = inPipMode,
                requestNotifications = ::requestNotificationsIfNeeded,
            )
        }
    }

    private fun buildPipParams(aspectRatio: Float, autoEnter: Boolean): PictureInPictureParams {
        val ratio = aspectRatio.coerceIn(0.45f, 2.35f)
        val numerator = (ratio * 1000f).toInt().coerceAtLeast(100)
        val rect = Rect()
        window.decorView.getGlobalVisibleRect(rect)
        val builder =
            PictureInPictureParams.Builder().setAspectRatio(Rational(numerator, 1000)).apply {
                if (!rect.isEmpty) setSourceRectHint(rect)
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(autoEnter)
                    setSeamlessResizeEnabled(true)
                }
            }
        return builder.build()
    }

    private fun updateAutoPipParams(eligible: Boolean, aspectRatio: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching { setPictureInPictureParams(buildPipParams(aspectRatio, eligible)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 31) return
        val playback = (application as WhispApp).graph.playback
        val st = playback.state.value
        if (st.hasVideo && st.playing && playback.isVideoOutputActive()) {
            runCatching {
                enterPictureInPictureMode(buildPipParams(st.videoAspectRatio, autoEnter = false))
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == OPEN_PLAYER) playerRequest++
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        updateBackgroundProtection()
    }

    override fun onPause() {
        activityResumed = false
        updateBackgroundProtection()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("player-request", playerRequest)
        outState.putBoolean("notification-requested", notificationRequested)
        super.onSaveInstanceState(outState)
    }
}
