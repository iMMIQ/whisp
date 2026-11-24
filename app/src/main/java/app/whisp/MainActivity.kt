// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.whisp.system.AppLockAuthenticator
import app.whisp.system.ExternalIntentParser
import app.whisp.system.ExternalLaunchAction
import app.whisp.system.WhispShortcuts
import app.whisp.system.WhispWidgetProvider
import app.whisp.ui.Cream
import app.whisp.ui.Muted
import app.whisp.ui.Rose
import app.whisp.ui.WhispRoot
import app.whisp.ui.WhispTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val OPEN_PLAYER = ExternalIntentParser.ACTION_OPEN_PLAYER
        private const val RELOCK_BACKGROUND_TIMEOUT_MS = 30_000L
    }

    private var launchCounter by mutableIntStateOf(0)
    private var pendingLaunchAction by mutableStateOf<ExternalLaunchAction?>(null)
    private var inPipMode by mutableStateOf(false)
    private var hideRecents = true
    private var hideArtwork = false
    private var appLockEnabled = false
    private var appLocked by mutableStateOf(false)
    private var appLockError by mutableStateOf<String?>(null)
    private var activityResumed = false
    private var backgroundedAtElapsedMs = 0L
    private var authSignal: CancellationSignal? = null
    private var videoBounds: Rect? = null

    private fun updateBackgroundProtection() {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        val shouldSecure =
            appLocked || (Build.VERSION.SDK_INT >= 33 && hideRecents && !activityResumed)
        if (shouldSecure) window.addFlags(flag) else window.clearFlags(flag)
    }

    private var notificationRequested = false

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val legacyCredentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                appLocked = false
                appLockError = null
                updateBackgroundProtection()
            } else {
                appLockError = "身份验证未完成，请重试"
            }
        }

    private fun triggerAppUnlock() {
        if (!appLocked) return
        authSignal?.cancel()
        appLockError = null
        authSignal =
            AppLockAuthenticator.authenticate(
                context = this,
                onSuccess = {
                    appLocked = false
                    appLockError = null
                    updateBackgroundProtection()
                },
                onError = { msg -> appLockError = msg },
                onLegacyCredentialIntent = { intent -> legacyCredentialLauncher.launch(intent) },
            )
    }

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

    private fun enqueueLaunchIntent(intent: Intent?) {
        val action = ExternalIntentParser.parse(intent) ?: return
        when (action) {
            ExternalLaunchAction.OpenPlayer ->
                WhispShortcuts.reportUsed(this, WhispShortcuts.ID_RESUME)
            ExternalLaunchAction.OpenStorage ->
                WhispShortcuts.reportUsed(this, WhispShortcuts.ID_STORAGE)
            is ExternalLaunchAction.StartSleepTimer ->
                WhispShortcuts.reportUsed(this, WhispShortcuts.ID_SLEEP_30)
            else -> Unit
        }
        pendingLaunchAction = action
        launchCounter++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchCounter = savedInstanceState?.getInt("launch-counter") ?: 0
        notificationRequested = savedInstanceState?.getBoolean("notification-requested") ?: false
        appLocked = savedInstanceState?.getBoolean("app-locked") ?: false
        if (savedInstanceState == null) enqueueLaunchIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val graph = (application as WhispApp).graph
        var initialLockChecked = savedInstanceState != null
        lifecycleScope.launch {
            graph.settings.settingsUpdates
                .map { Triple(it.hideRecents, it.hideArtwork, it.appLockEnabled) }
                .distinctUntilChanged()
                .collect { (recents, artwork, lockEnabled) ->
                    hideRecents = recents
                    hideArtwork = artwork
                    appLockEnabled = lockEnabled
                    if (!initialLockChecked) {
                        initialLockChecked = true
                        if (lockEnabled && AppLockAuthenticator.isDeviceSecure(this@MainActivity)) {
                            appLocked = true
                            if (activityResumed) triggerAppUnlock()
                        }
                    } else if (!lockEnabled && appLocked) {
                        appLocked = false
                        appLockError = null
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        setRecentsScreenshotEnabled(!recents && !lockEnabled)
                    }
                    updateBackgroundProtection()
                    WhispShortcuts.refresh(this@MainActivity, graph)
                    WhispWidgetProvider.requestUpdate(this@MainActivity)
                }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Keep cold-start previews private until the persisted preference is available.
            setRecentsScreenshotEnabled(false)
            updateBackgroundProtection()
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
            if (appLocked) {
                val amoled = graph.settings.settings.collectAsState().value.amoledBlack
                WhispTheme(amoledBlack = amoled) {
                    AppLockScreen(error = appLockError, onUnlock = ::triggerAppUnlock)
                }
            } else {
                WhispRoot(
                    graph = graph,
                    launchCounter = launchCounter,
                    launchAction = pendingLaunchAction,
                    inPipMode = inPipMode,
                    requestNotifications = ::requestNotificationsIfNeeded,
                    onVideoBoundsChanged = { rect ->
                        videoBounds = rect
                        if (Build.VERSION.SDK_INT >= 31) {
                            val st = graph.playback.state.value
                            val eligible =
                                st.hasVideo &&
                                    st.playing &&
                                    !graph.playback.audioOnly.value &&
                                    graph.playback.isVideoOutputActive()
                            updateAutoPipParams(eligible, st.videoAspectRatio)
                        }
                    },
                )
            }
        }
    }

    private fun buildPipParams(aspectRatio: Float, autoEnter: Boolean): PictureInPictureParams {
        val ratio = aspectRatio.coerceIn(0.45f, 2.35f)
        val numerator = (ratio * 1000f).toInt().coerceAtLeast(100)
        val rect =
            videoBounds?.takeIf { !it.isEmpty }
                ?: Rect().also { window.decorView.getGlobalVisibleRect(it) }
        val builder =
            PictureInPictureParams.Builder().setAspectRatio(Rational(numerator, 1000)).apply {
                if (!rect.isEmpty) setSourceRectHint(rect)
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(autoEnter)
                    setSeamlessResizeEnabled(true)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    setTitle(if (hideArtwork) "正在收听" else "Whisp")
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
        enqueueLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (
            appLockEnabled &&
                !appLocked &&
                backgroundedAtElapsedMs > 0L &&
                SystemClock.elapsedRealtime() - backgroundedAtElapsedMs >=
                    RELOCK_BACKGROUND_TIMEOUT_MS &&
                AppLockAuthenticator.isDeviceSecure(this)
        ) {
            appLocked = true
        }
        backgroundedAtElapsedMs = 0L
        updateBackgroundProtection()
        if (appLocked) triggerAppUnlock()
    }

    override fun onPause() {
        activityResumed = false
        if (appLockEnabled && !appLocked) {
            backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        authSignal?.cancel()
        authSignal = null
        updateBackgroundProtection()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("launch-counter", launchCounter)
        outState.putBoolean("notification-requested", notificationRequested)
        outState.putBoolean("app-locked", appLocked)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun AppLockScreen(error: String?, onUnlock: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Outlined.Fingerprint,
                contentDescription = "应用已锁定",
                tint = Rose,
                modifier = Modifier.size(56.dp),
            )
            Text("Whisp 已锁定", style = MaterialTheme.typography.headlineSmall, color = Cream)
            Text(
                error ?: "验证生物识别或设备锁屏密码以访问作品库",
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else Muted,
            )
            FilledTonalButton(
                onClick = onUnlock,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(top = 8.dp).height(48.dp),
            ) {
                Text("验证解锁")
            }
        }
    }
}
