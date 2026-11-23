// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.net.URI

data class NfsAddress(
    val host: String,
    val port: Int,
    val exportPath: String,
    val version: Int = 4,
    val uid: Int = 65534,
    val gid: Int = 65534,
    val mountPort: Int = 0,
) {
    init {
        require(version in listOf(3, 4, 42)) { "请选择 NFS 3、4.0 或 4.2" }
        require(uid >= 0 && gid >= 0) { "UID 和 GID 应为非负整数" }
        require(port in 1..65535 && mountPort in 0..65535) { "端口无效" }
    }

    val normalized: String
        get() =
            URI(
                    "nfs",
                    null,
                    host,
                    if (port == 2049) -1 else port,
                    exportPath,
                    "version=${if (version == 42) "4.2" else version.toString()}&uid=$uid&gid=$gid" +
                        if (mountPort == 0) "" else "&mountport=$mountPort",
                    null,
                )
                .toASCIIString()

    companion object {
        fun parse(value: String, version: Int = 4, uid: Int = 65534, gid: Int = 65534): NfsAddress {
            val uri =
                try {
                    URI(value.trim())
                } catch (_: Exception) {
                    throw IllegalArgumentException("请输入有效的 NFS 地址")
                }
            require(uri.scheme.equals("nfs", true) && !uri.host.isNullOrBlank()) {
                "请输入 nfs://服务器/导出目录"
            }
            require(uri.userInfo == null && uri.fragment == null) { "NFS 账号使用 UID 和 GID" }
            require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
            val query = mutableMapOf<String, String>()
            uri.rawQuery?.split('&')?.forEach { item ->
                val pair = item.split('=', limit = 2)
                require(pair.size == 2 && pair[0] in setOf("version", "uid", "gid", "mountport")) {
                    "NFS 地址参数无效"
                }
                require(query.put(pair[0], pair[1]) == null) { "NFS 地址参数重复" }
            }
            val protocol =
                when (query["version"]) {
                    null -> version
                    "3" -> 3
                    "4",
                    "4.0" -> 4
                    "4.2" -> 42
                    else -> throw IllegalArgumentException("请选择 NFS 3、4.0 或 4.2")
                }
            fun number(name: String, default: Int) =
                query[name]?.let {
                    it.toIntOrNull() ?: throw IllegalArgumentException("$name 应为非负整数")
                } ?: default
            return NfsAddress(
                uri.host.lowercase(),
                if (uri.port == -1) 2049 else uri.port,
                "/" + RemoteAssetUri.decodeSegments(uri.rawPath.orEmpty()).joinToString("/"),
                protocol,
                number("uid", uid),
                number("gid", gid),
                number("mountport", 0),
            )
        }

        fun assetUri(sourceId: String, relative: String): String =
            RemoteAssetUri.encode("whisp-nfs", sourceId, relative, forbidBackslash = false)

        fun assetPath(value: String, sourceId: String): String =
            RemoteAssetUri.decode(value, "whisp-nfs", sourceId, forbidBackslash = false)

        fun safeSegments(path: String): List<String> =
            RemoteAssetUri.safeSegments(path, forbidBackslash = false)
    }
}
