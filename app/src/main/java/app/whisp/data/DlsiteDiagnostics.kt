// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class DlsitePhase {
    AUTHORIZE,
    PURCHASES,
    FILES,
    SIGNATURE,
    MEDIA,
    OTHER,
}

@Serializable
data class DlsiteRequestEvent(val phase: DlsitePhase, val status: Int, val elapsedMs: Long)

@Serializable
private data class DlsiteDiagnosticReport(
    val schema: Int,
    val androidSdk: Int,
    val version: String?,
    val requests: List<DlsiteRequestEvent>,
)

/**
 * Bounded ring-buffered diagnostic recorder: avoids per-request SharedPreferences XML rewrites by
 * keeping the last 64 fixed-schema events in memory and debouncing disk persistence.
 */
class DlsiteDiagnostics(private val context: Context) {
    private val preferences by lazy {
        context.getSharedPreferences("dlsite-diagnostics", Context.MODE_PRIVATE)
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val ring = ArrayDeque<DlsiteRequestEvent>(64)
    private var loaded = false
    private var flushTask: ScheduledFuture<*>? = null
    private val flusher by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "whisp-diag-flush").apply { isDaemon = true }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = runCatching {
            json.decodeFromString<List<DlsiteRequestEvent>>(
                preferences.getString("events", "[]")!!,
            )
        }
            .getOrDefault(emptyList())
        stored.takeLast(64).forEach(ring::addLast)
    }

    private fun scheduleFlush() {
        flushTask?.cancel(false)
        flushTask = flusher.schedule({ flushNow() }, 2_000L, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun flushNow() {
        flushTask = null
        val snapshot = ring.toList()
        preferences.edit { putString("events", json.encodeToString(snapshot)) }
    }

    @Synchronized
    fun record(event: DlsiteRequestEvent) {
        ensureLoaded()
        while (ring.size >= 64) ring.removeFirst()
        ring.addLast(event)
        scheduleFlush()
    }

    @Synchronized
    fun clear() {
        flushTask?.cancel(false)
        flushTask = null
        ring.clear()
        loaded = true
        preferences.edit { clear() }
    }

    @Synchronized
    fun shareIntent(): Intent {
        ensureLoaded()
        val snapshot = ring.toList()
        val report =
            DlsiteDiagnosticReport(
                schema = 1,
                androidSdk = Build.VERSION.SDK_INT,
                version = context.packageManager.getPackageInfo(context.packageName, 0).versionName,
                requests = snapshot,
            )
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file =
            File(directory, "whisp-dlsite.json").apply { writeText(json.encodeToString(report)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        return Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
