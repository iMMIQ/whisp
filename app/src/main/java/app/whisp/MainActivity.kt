// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.whisp.ui.WhispRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val OPEN_PLAYER = "app.whisp.OPEN_PLAYER"
    }

    private var playerRequest by mutableIntStateOf(0)
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
        setContent { WhispRoot(graph, playerRequest, ::requestNotificationsIfNeeded) }
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
