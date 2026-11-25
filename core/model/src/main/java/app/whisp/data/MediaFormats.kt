// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

object MediaFormats {
    val audio = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac")
    val video = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "m2ts", "mpg", "mpeg")
    val playable = audio + video
    val subtitles = setOf("vtt", "lrc", "srt")

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String) = extension(name) in video

    fun videoMimeType(name: String): String? =
        when (extension(name)) {
            "mp4",
            "m4v",
            "mov" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "ts",
            "m2ts" -> "video/mp2t"
            "mpg",
            "mpeg" -> "video/mp2p"
            else -> null
        }
}
