// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.system

import android.content.Intent
import android.net.Uri
import app.whisp.data.asmrWorkNumber
import app.whisp.data.normalizedAsmrInput

sealed interface ExternalLaunchAction {
    data object OpenPlayer : ExternalLaunchAction

    data object OpenStorage : ExternalLaunchAction

    data class StartSleepTimer(val minutes: Int) : ExternalLaunchAction

    data class OpenWorkCode(val code: String, val asmrId: Long?) : ExternalLaunchAction

    data class SearchQuery(val query: String) : ExternalLaunchAction

    data class PlayExternalMedia(val uri: Uri, val mimeType: String?) : ExternalLaunchAction
}

/**
 * Parses incoming Android intents (Share sheet, Process Text menu, View URL/media, App Shortcuts,
 * Quick Settings Tiles, and Manage Space) into typed app actions.
 */
object ExternalIntentParser {
    const val ACTION_OPEN_PLAYER = "app.whisp.OPEN_PLAYER"
    const val ACTION_OPEN_STORAGE = "app.whisp.OPEN_STORAGE"
    const val ACTION_START_SLEEP_TIMER = "app.whisp.START_SLEEP_TIMER"
    const val EXTRA_MINUTES = "minutes"

    private val workCodeRegex = Regex("(?i)\\b((?:RJ|VJ|BJ)[0-9]{6,8})\\b")

    fun parse(intent: Intent?): ExternalLaunchAction? {
        if (intent == null) return null
        return when (intent.action) {
            ACTION_OPEN_PLAYER -> ExternalLaunchAction.OpenPlayer
            ACTION_OPEN_STORAGE -> ExternalLaunchAction.OpenStorage
            ACTION_START_SLEEP_TIMER ->
                ExternalLaunchAction.StartSleepTimer(
                    intent.getIntExtra(EXTRA_MINUTES, 30).coerceIn(1, 180),
                )
            Intent.ACTION_SEND -> {
                val text =
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                text?.let(::parseTextPayload)
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                text?.let(::parseTextPayload)
            }
            Intent.ACTION_VIEW -> parseViewIntent(intent)
            else -> null
        }
    }

    internal fun parseTextPayload(raw: String): ExternalLaunchAction? {
        val text = normalizedAsmrInput(raw)
        if (text.isBlank()) return null
        asmrWorkNumber(text)?.let { id ->
            val explicitCode = extractWorkCode(text) ?: formatRjCode(id)
            return ExternalLaunchAction.OpenWorkCode(explicitCode, id)
        }
        extractWorkCode(text)?.let { code ->
            val asmrId = if (code.startsWith("RJ")) code.removePrefix("RJ").toLongOrNull() else null
            return ExternalLaunchAction.OpenWorkCode(code, asmrId)
        }
        // Extract URLs inside shared text (e.g. "Check out
        // https://www.dlsite.com/.../RJ01234567.html")
        val urlToken =
            Regex("https?://\\S+", RegexOption.IGNORE_CASE)
                .find(text)
                ?.value
                ?.trimEnd(')', ']', '.', ',')
        if (urlToken != null) {
            asmrWorkNumber(urlToken)?.let { id ->
                return ExternalLaunchAction.OpenWorkCode(formatRjCode(id), id)
            }
        }
        val compact = text.lineSequence().firstOrNull()?.trim()?.take(120).orEmpty()
        return if (compact.isNotBlank()) ExternalLaunchAction.SearchQuery(compact) else null
    }

    private fun parseViewIntent(intent: Intent): ExternalLaunchAction? {
        val uri = intent.data ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            return parseTextPayload(uri.toString())
        }
        if (scheme == "content" || scheme == "file") {
            return ExternalLaunchAction.PlayExternalMedia(uri, intent.type)
        }
        return null
    }

    internal fun extractWorkCode(text: String): String? {
        val match = workCodeRegex.find(text) ?: return null
        return match.groupValues[1].uppercase()
    }

    private fun formatRjCode(id: Long): String =
        if (id >= 1_000_000L) "RJ%08d".format(id) else "RJ%06d".format(id)
}
