// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.net.URI
import java.net.URLDecoder

/** Shared URI codec and path-segment validation for whisp-smb:// and whisp-nfs:// assets. */
object RemoteAssetUri {
    private val sourceIdPattern = Regex("[A-Za-z0-9_-]+")

    fun encode(
        scheme: String,
        sourceId: String,
        relative: String,
        forbidBackslash: Boolean,
    ): String {
        require(sourceIdPattern.matches(sourceId))
        return URI(
                scheme,
                sourceId,
                "/" + safeSegments(relative, forbidBackslash).joinToString("/"),
                null,
            )
            .toASCIIString()
    }

    fun decode(value: String, scheme: String, sourceId: String, forbidBackslash: Boolean): String {
        val uri = URI(value)
        require(
            uri.scheme == scheme &&
                uri.rawAuthority == sourceId &&
                uri.query == null &&
                uri.fragment == null,
        ) {
            "来源未关联"
        }
        return decodeSegments(uri.rawPath.orEmpty(), forbidBackslash).joinToString("/")
    }

    fun safeSegments(path: String, forbidBackslash: Boolean = false): List<String> =
        path.split('/').filter { it.isNotEmpty() }.onEach { validateSegment(it, forbidBackslash) }

    fun decodeSegments(path: String, forbidBackslash: Boolean = false): List<String> =
        path
            .split('/')
            .filter { it.isNotEmpty() }
            .map {
                URLDecoder.decode(it.replace("+", "%2B"), "UTF-8").also { seg ->
                    validateSegment(seg, forbidBackslash)
                }
            }

    private fun validateSegment(segment: String, forbidBackslash: Boolean) {
        require(
            segment != "." &&
                segment != ".." &&
                segment.none {
                    it == '/' || (forbidBackslash && it == '\\') || it.code < 32 || it.code == 127
                },
        ) {
            "目录包含无效字符"
        }
    }
}
