// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.net.URI
import java.net.URLDecoder

/** Credentials live in the source record, never in media URLs. */
data class SmbAddress(
    val host: String,
    val port: Int,
    val share: String,
    val root: List<String>,
    val encrypt: Boolean = true,
) {
    val normalized: String
        get() =
            URI(
                    "smb",
                    null,
                    host,
                    if (port == 445) -1 else port,
                    "/" + (listOf(share) + root).joinToString("/") + "/",
                    if (encrypt) null else "encrypt=false",
                    null,
                )
                .toASCIIString()

    fun filePath(relative: String) = (root + safeSegments(relative)).joinToString("\\")

    companion object {
        private val sourceIdPattern = Regex("[A-Za-z0-9_-]+")

        fun parse(value: String, encrypt: Boolean? = null): SmbAddress {
            val uri =
                try {
                    URI(value.trim())
                } catch (_: Exception) {
                    error("请输入有效的 SMB 地址")
                }
            require(uri.scheme.equals("smb", true) && !uri.host.isNullOrBlank()) {
                "请输入 smb://服务器/共享名/目录"
            }
            require(uri.userInfo == null && uri.fragment == null) { "账号请填写在账号栏" }
            var resolvedEncrypt = encrypt ?: true
            uri.rawQuery?.let { raw ->
                val pairs = raw.split('&')
                require(pairs.size == 1) { "账号请填写在账号栏" }
                val pair = pairs.single().split('=', limit = 2)
                require(pair.size == 2 && pair[0] == "encrypt") { "账号请填写在账号栏" }
                resolvedEncrypt =
                    when (pair[1]) {
                        "true" -> true
                        "false" -> false
                        else -> throw IllegalArgumentException("SMB 加密参数无效")
                    }
            }
            require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
            val parts = decodeSegments(uri.rawPath.orEmpty())
            require(parts.isNotEmpty()) { "请填写共享名" }
            return SmbAddress(
                uri.host,
                if (uri.port == -1) 445 else uri.port,
                parts.first(),
                parts.drop(1),
                encrypt ?: resolvedEncrypt,
            )
        }

        fun assetUri(sourceId: String, relative: String): String {
            require(sourceIdPattern.matches(sourceId))
            return URI("whisp-smb", sourceId, "/" + safeSegments(relative).joinToString("/"), null)
                .toASCIIString()
        }

        fun assetPath(value: String, sourceId: String): String {
            val uri = URI(value)
            require(
                uri.scheme == "whisp-smb" &&
                    uri.rawAuthority == sourceId &&
                    uri.query == null &&
                    uri.fragment == null,
            ) {
                "来源未关联"
            }
            return decodeSegments(uri.rawPath).joinToString("/")
        }

        private fun decodeSegments(path: String): List<String> =
            path
                .split('/')
                .filter { it.isNotEmpty() }
                .map {
                    URLDecoder.decode(it.replace("+", "%2B"), "UTF-8").also(::validateSegment)
                }

        fun safeSegments(path: String): List<String> =
            path.split('/').filter { it.isNotEmpty() }.onEach(::validateSegment)

        private fun validateSegment(segment: String) {
            require(
                segment != "." &&
                    segment != ".." &&
                    segment.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 },
            ) {
                "目录包含无效字符"
            }
        }
    }
}

data class SmbAccount(val user: String, val domain: String) {
    companion object {
        fun parse(value: String): SmbAccount {
            val parts = value.trim().split('\\')
            require(parts.size <= 2 && (parts.size == 1 || parts.all { it.isNotEmpty() })) {
                "账号格式为 用户名 或 域\\用户名"
            }
            return if (parts.size == 2) SmbAccount(parts[1], parts[0]) else SmbAccount(parts[0], "")
        }
    }
}
