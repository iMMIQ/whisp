// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.File
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

/** Only fixed phases, HTTP status codes and timings enter the diagnostic report. */
class DlsiteDiagnostics(private val context: Context) {
    private val preferences =
        context.getSharedPreferences("dlsite-diagnostics", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun readEvents(): List<DlsiteRequestEvent> = runCatching {
        json.decodeFromString<List<DlsiteRequestEvent>>(
            preferences.getString("events", "[]")!!,
        )
    }
        .getOrDefault(emptyList())

    @Synchronized
    fun record(event: DlsiteRequestEvent) {
        val next = readEvents().takeLast(63) + event
        preferences.edit { putString("events", json.encodeToString(next)) }
    }

    @Synchronized
    fun clear() {
        preferences.edit { clear() }
    }

    @Synchronized
    fun shareIntent(): Intent {
        val report =
            DlsiteDiagnosticReport(
                schema = 1,
                androidSdk = Build.VERSION.SDK_INT,
                version = context.packageManager.getPackageInfo(context.packageName, 0).versionName,
                requests = readEvents(),
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
