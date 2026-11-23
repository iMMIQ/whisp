// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.File
import kotlinx.serialization.json.*

enum class DlsitePhase {
    AUTHORIZE,
    PURCHASES,
    FILES,
    SIGNATURE,
    MEDIA,
    OTHER,
}

data class DlsiteRequestEvent(val phase: DlsitePhase, val status: Int, val elapsedMs: Long)

/** Only fixed phases, HTTP status codes and timings enter the diagnostic report. */
class DlsiteDiagnostics(private val context: Context) {
    private val preferences =
        context.getSharedPreferences("dlsite-diagnostics", Context.MODE_PRIVATE)

    @Synchronized
    fun record(event: DlsiteRequestEvent) {
        val previous = runCatching {
            Json.parseToJsonElement(preferences.getString("events", "[]")!!).jsonArray
        }
            .getOrDefault(JsonArray(emptyList()))
        val next =
            previous.takeLast(63) +
                buildJsonObject {
                    put("phase", event.phase.name)
                    put("status", event.status)
                    put("elapsedMs", event.elapsedMs)
                }
        preferences.edit { putString("events", JsonArray(next).toString()) }
    }

    @Synchronized
    fun clear() {
        preferences.edit { clear() }
    }

    @Synchronized
    fun shareIntent(): Intent {
        val events = runCatching {
            Json.parseToJsonElement(preferences.getString("events", "[]")!!)
        }
            .getOrDefault(JsonArray(emptyList()))
        val report = buildJsonObject {
            put("schema", 1)
            put("androidSdk", Build.VERSION.SDK_INT)
            put(
                "version",
                context.packageManager.getPackageInfo(context.packageName, 0).versionName,
            )
            put("requests", events)
        }
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(directory, "whisp-dlsite.json").apply { writeText(report.toString()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        return Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
