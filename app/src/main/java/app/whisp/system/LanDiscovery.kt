// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.system

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import app.whisp.data.SourceKind
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredNasService(
    val name: String,
    val host: String,
    val port: Int,
    val kind: SourceKind,
) {
    fun suggestedUrl(): String {
        val safeHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
        return when (kind) {
            SourceKind.SMB ->
                if (port > 0 && port != 445) "smb://$safeHost:$port/" else "smb://$safeHost/"
            SourceKind.NFS -> "nfs://$safeHost/"
            SourceKind.WEBDAV ->
                if (port > 0 && port != 443) "https://$safeHost:$port/" else "https://$safeHost/"
            else -> safeHost
        }
    }
}

/**
 * Discovers LAN SMB, NFS, and HTTPS WebDAV services via Android's built-in [NsdManager] (mDNS /
 * DNS-SD) without requiring location or extra network scan permissions.
 */
object LanDiscovery {
    internal fun serviceTypeFor(kind: SourceKind): String? =
        when (kind) {
            SourceKind.SMB -> "_smb._tcp."
            SourceKind.NFS -> "_nfs._tcp."
            SourceKind.WEBDAV -> "_webdavs._tcp."
            else -> null
        }

    internal fun pickBestAddress(addresses: List<InetAddress>): String? {
        val usable = addresses.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        val chosen = usable.firstOrNull { it is Inet4Address } ?: usable.firstOrNull()
        return chosen?.hostAddress?.substringBefore('%')
    }

    fun discover(context: Context, kind: SourceKind): Flow<List<DiscoveredNasService>> =
        callbackFlow {
            val serviceType = serviceTypeFor(kind)
            val nsd = context.getSystemService(NsdManager::class.java)
            if (serviceType == null || nsd == null) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }
            val found = ConcurrentHashMap<String, DiscoveredNasService>()
            val executor = Executors.newSingleThreadExecutor()
            val infoCallbacks = ConcurrentHashMap<String, Any>()

            fun emitSnapshot() {
                trySend(found.values.sortedBy { it.name.lowercase() })
            }

            fun record(info: NsdServiceInfo, host: String?) {
                val cleanHost = host?.trim()?.takeIf(String::isNotEmpty) ?: return
                val cleanName = info.serviceName?.trim()?.ifEmpty { cleanHost } ?: cleanHost
                val key = "${cleanName.lowercase()}|$cleanHost:${info.port}"
                found[key] = DiscoveredNasService(cleanName, cleanHost, info.port, kind)
                emitSnapshot()
            }

            val discoveryListener =
                object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        close()
                    }

                    override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

                    override fun onDiscoveryStarted(serviceType: String?) {
                        emitSnapshot()
                    }

                    override fun onDiscoveryStopped(serviceType: String?) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val name = serviceInfo.serviceName ?: return
                        if (Build.VERSION.SDK_INT >= 34) {
                            resolveApi34(nsd, serviceInfo, executor, infoCallbacks, name, ::record)
                        } else {
                            resolveLegacy(nsd, serviceInfo, ::record)
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        val name = serviceInfo.serviceName?.lowercase() ?: return
                        val removed = found.keys.filter { it.startsWith("$name|") }
                        if (removed.isNotEmpty()) {
                            removed.forEach(found::remove)
                            emitSnapshot()
                        }
                    }
                }

            val started = runCatching {
                nsd.discoverServices(
                    serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener,
                )
            }
                .isSuccess
            if (!started) {
                trySend(emptyList())
                close()
                executor.shutdownNow()
                return@callbackFlow
            }

            awaitClose {
                if (Build.VERSION.SDK_INT >= 34) {
                    unregisterApi34Callbacks(nsd, infoCallbacks)
                }
                infoCallbacks.clear()
                runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                executor.shutdownNow()
            }
        }

    @RequiresApi(34)
    private fun resolveApi34(
        nsd: NsdManager,
        serviceInfo: NsdServiceInfo,
        executor: java.util.concurrent.Executor,
        callbacks: ConcurrentHashMap<String, Any>,
        key: String,
        onResolved: (NsdServiceInfo, String?) -> Unit,
    ) {
        if (callbacks.containsKey(key)) return
        val cb =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    callbacks.remove(key)
                }

                override fun onServiceUpdated(info: NsdServiceInfo) {
                    val host = pickBestAddress(info.hostAddresses)
                    onResolved(info, host)
                }

                override fun onServiceLost() {
                    callbacks.remove(key)
                }

                override fun onServiceInfoCallbackUnregistered() {
                    callbacks.remove(key)
                }
            }
        callbacks[key] = cb
        runCatching { nsd.registerServiceInfoCallback(serviceInfo, executor, cb) }
            .onFailure { callbacks.remove(key) }
    }

    @RequiresApi(34)
    private fun unregisterApi34Callbacks(
        nsd: NsdManager,
        callbacks: ConcurrentHashMap<String, Any>,
    ) {
        callbacks.values.forEach { cb ->
            if (cb is NsdManager.ServiceInfoCallback) {
                runCatching { nsd.unregisterServiceInfoCallback(cb) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(
        nsd: NsdManager,
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo, String?) -> Unit,
    ) {
        runCatching {
            nsd.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) = Unit

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress?.substringBefore('%')
                        onResolved(info, host)
                    }
                },
            )
        }
    }
}
