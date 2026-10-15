// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import app.whisp.playback.DecoderEvent
import java.io.File
import kotlinx.serialization.json.*

/** Only decoder names, MIME types and offload states enter the diagnostic report. */
class PlaybackDiagnostics(private val context: Context) {
    private val preferences =
        context.getSharedPreferences("playback-diagnostics", Context.MODE_PRIVATE)

    private fun readEvents(): JsonArray = runCatching {
        Json.parseToJsonElement(preferences.getString("events", "[]")!!).jsonArray
    }
        .getOrDefault(JsonArray(emptyList()))

    private fun writeEvents(events: List<JsonElement>) {
        preferences.edit { putString("events", JsonArray(events).toString()) }
    }

    private fun encode(event: DecoderEvent): JsonObject = buildJsonObject {
        event.id?.let { put("id", it) }
        put("kind", event.kind)
        event.mime?.let { put("mime", it) }
        event.codecs?.let { put("codecs", it) }
        event.decoder?.let { put("decoder", it) }
        event.hardwareAccelerated?.let { put("hardwareAccelerated", it) }
        event.offloaded?.let { put("offloaded", it) }
    }

    @Synchronized
    fun record(event: DecoderEvent) {
        writeEvents(readEvents().takeLast(63) + encode(event))
    }

    @Synchronized
    fun replaceById(id: String, event: DecoderEvent) {
        val previous = readEvents()
        val index = previous.indexOfFirst { it.jsonObject["id"]?.jsonPrimitive?.content == id }
        writeEvents(
            if (index < 0) {
                previous.takeLast(63) + encode(event)
            } else {
                previous.toMutableList().also { it[index] = encode(event) }
            },
        )
    }

    @Synchronized
    fun snapshot(): List<DecoderEvent> =
        readEvents().map { element ->
            val fields = element.jsonObject
            DecoderEvent(
                fields["kind"]!!.jsonPrimitive.content,
                fields["mime"]?.jsonPrimitive?.content,
                fields["codecs"]?.jsonPrimitive?.content,
                fields["decoder"]?.jsonPrimitive?.content,
                fields["hardwareAccelerated"]?.jsonPrimitive?.boolean,
                fields["offloaded"]?.jsonPrimitive?.boolean,
                fields["id"]?.jsonPrimitive?.content,
            )
        }

    @Synchronized
    fun clear() {
        preferences.edit { clear() }
    }

    fun shareIntent(): Intent {
        val events = readEvents()
        val report = buildJsonObject {
            put("schema", 1)
            put("androidSdk", Build.VERSION.SDK_INT)
            put(
                "version",
                context.packageManager.getPackageInfo(context.packageName, 0).versionName,
            )
            put("events", events)
        }
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(directory, "whisp-playback.json").apply { writeText(report.toString()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        return Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
