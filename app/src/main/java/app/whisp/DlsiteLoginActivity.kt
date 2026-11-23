// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.whisp.data.DlsiteLoginRequired
import app.whisp.data.DlsiteSession
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
                val id =
                    (application as WhispApp)
                        .repository
                        .acceptDlsiteLogin(snapshot, intent.getStringExtra(SOURCE))
                closing = true
                setResult(RESULT_OK, Intent().putExtra(SOURCE, id))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: DlsiteLoginRequired) {
                if (showFailure) error = "请先完成官方账号登录"
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val restoredBrowser = savedInstanceState?.getBundle(BROWSER_STATE)
        if (savedInstanceState?.getBoolean(SESSION_READY) == true) ready = true
        else
            CookieManager.getInstance().removeAllCookies {
                if (!isFinishing && !isDestroyed) ready = true
            }
        setContent {
            WhispTheme {
                Scaffold(
                    topBar = {
                        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp)) {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                            }
                            Column(Modifier.weight(1f).padding(8.dp)) {
                                Text("DLsite 登录", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "login.dlsite.com",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(enabled = !checking, onClick = { verify(true) }) {
                                Text("完成")
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let {
                            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(it, Modifier.weight(1f))
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
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
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
