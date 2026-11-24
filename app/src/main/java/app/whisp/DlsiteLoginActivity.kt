// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.whisp.data.DlsiteLoginRequired
import app.whisp.data.DlsiteSession
import app.whisp.ui.Cream
import app.whisp.ui.Ink
import app.whisp.ui.Muted
import app.whisp.ui.WhispTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Official pages own the login form; the app receives only the completed session. */
class DlsiteLoginActivity : ComponentActivity() {
    companion object {
        const val SOURCE = "dlsite-source"
        private const val BROWSER_STATE = "dlsite-browser-state"
        private const val SESSION_READY = "dlsite-session-ready"
    }

    private var browser: WebView? = null
    private var checking by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var socialLogin by mutableStateOf(false)
    private var closing = false
    private var ready by mutableStateOf(false)

    private fun verify(showFailure: Boolean = false) {
        if (checking || closing) return
        val manager = CookieManager.getInstance()
        val snapshot =
            DlsiteSession.fromBrowser(
                DlsiteSession.origins.associateWith { manager.getCookie(it).orEmpty() },
            )
        checking = true
        lifecycleScope.launch {
            try {
                val graph = (application as WhispApp).graph
                val id = graph.sources.acceptDlsiteLogin(snapshot, intent.getStringExtra(SOURCE))
                graph.sync.scheduleScan(id, force = true)
                closing = true
                setResult(RESULT_OK, Intent().putExtra(SOURCE, id))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: DlsiteLoginRequired) {
                if (showFailure) error = "请先完成官方账号登录"
            } catch (e: IllegalArgumentException) {
                error = e.message?.takeIf(String::isNotBlank) ?: "登录验证失败，请重试"
            } catch (_: Exception) {
                error = "登录验证失败，请重试"
            } finally {
                checking = false
            }
        }
    }

    // Official account pages require JavaScript; file access and app bridges are disabled.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val restoredBrowser = savedInstanceState?.getBundle(BROWSER_STATE)
        if (savedInstanceState?.getBoolean(SESSION_READY) == true) ready = true
        else {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                if (!isFinishing && !isDestroyed) ready = true
            }
        }
        setContent {
            WhispTheme {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        Column(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            Row(
                                Modifier.statusBarsPadding()
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Cream)
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    Text(
                                        "DLsite 登录",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Cream,
                                    )
                                    Text(
                                        "login.dlsite.com",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                    )
                                }
                                TextButton(enabled = !checking, onClick = { verify(true) }) {
                                    Text("完成")
                                }
                            }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    it,
                                    Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(
                                    onClick = {
                                        error = null
                                        verify(true)
                                    },
                                ) {
                                    Text("重试")
                                }
                            }
                        }
                        if (ready)
                            AndroidView(
                                modifier = Modifier.weight(1f),
                                factory = { context ->
                                    WebView(context).apply {
                                        browser = this
                                        setBackgroundColor(Ink.toArgb())
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        if (Build.VERSION.SDK_INT < 30) {
                                            @Suppress("DEPRECATION")
                                            settings.allowFileAccessFromFileURLs = false
                                            @Suppress("DEPRECATION")
                                            settings.allowUniversalAccessFromFileURLs = false
                                        }
                                        settings.mixedContentMode =
                                            WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        CookieManager.getInstance()
                                            .setAcceptThirdPartyCookies(this, false)
                                        webViewClient =
                                            object : WebViewClient() {
                                                override fun onReceivedSslError(
                                                    view: WebView,
                                                    handler: SslErrorHandler,
                                                    error: android.net.http.SslError,
                                                ) {
                                                    handler.cancel()
                                                    this@DlsiteLoginActivity.error =
                                                        "安全连接验证失败，请检查网络环境"
                                                }

                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView,
                                                    request: WebResourceRequest,
                                                ): Boolean {
                                                    val uri = request.url
                                                    if (
                                                        uri.scheme == "https" &&
                                                            (uri.host == "dlsite.com" ||
                                                                uri.host
                                                                    .orEmpty()
                                                                    .endsWith(".dlsite.com"))
                                                    )
                                                        return false
                                                    if (request.isForMainFrame) socialLogin = true
                                                    return true
                                                }

                                                override fun onPageFinished(
                                                    view: WebView,
                                                    url: String,
                                                ) {
                                                    if (url.toUri().host == "play.dlsite.com")
                                                        verify()
                                                }
                                            }
                                        if (
                                            restoredBrowser == null ||
                                                restoreState(restoredBrowser) == null
                                        )
                                            loadUrl("https://play.dlsite.com/login/?to=%2Flibrary")
                                    }
                                },
                                onRelease = { view ->
                                    if (browser === view) browser = null
                                    (view.parent as? ViewGroup)?.removeView(view)
                                    view.stopLoading()
                                    view.clearHistory()
                                    view.clearCache(true)
                                    view.destroy()
                                },
                            )
                    }
                }
                BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else finish() }
                if (socialLogin)
                    AlertDialog(
                        onDismissRequest = { socialLogin = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        title = { Text("使用 viviON ID 登录") },
                        text = { Text("在 DLsite 账号管理中设置登录 ID 和密码，然后返回登录。") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    socialLogin = false
                                    runCatching {
                                        startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                "https://login.dlsite.com/".toUri(),
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Text("账号管理")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { socialLogin = false }) { Text("返回") }
                        },
                    )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(SESSION_READY, ready)
        browser?.let { view ->
            val state = Bundle()
            if (view.saveState(state) != null) outState.putBundle(BROWSER_STATE, state)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closing = true
        browser?.stopLoading()
        // Rotation replaces the view, while the official login session remains active.
        // Clear browser credentials and storage when the user actually leaves this screen.
        if (!isChangingConfigurations) {
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }
}
