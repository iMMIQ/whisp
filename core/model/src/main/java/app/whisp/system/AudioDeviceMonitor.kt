// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.system

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioOutputKind {
    SPEAKER,
    WIRED_HEADSET,
    BLUETOOTH,
    USB_AUDIO,
    OTHER_EXTERNAL,
}

data class AudioOutputSnapshot(
    val kind: AudioOutputKind = AudioOutputKind.SPEAKER,
    val label: String = "手机扬声器",
    val deviceKey: String = "speaker",
    val hasExternalOutput: Boolean = false,
) {
    val isBuiltInSpeakerOnly: Boolean
        get() = !hasExternalOutput
}

data class AudioDeviceDescriptor(val type: Int, val productName: String)

/**
 * Observes Android audio output routes via [AudioManager.registerAudioDeviceCallback] without
 * requiring Bluetooth runtime permissions, powering Speaker Guard and per-headphone DSP profiles.
 */
class AudioDeviceMonitor(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _output = MutableStateFlow(readCurrentSnapshot())
    val output: StateFlow<AudioOutputSnapshot> = _output.asStateFlow()

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refresh()
            }
        }

    init {
        runCatching {
            audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    fun refresh(): AudioOutputSnapshot {
        val next = readCurrentSnapshot()
        if (_output.value != next) _output.value = next
        return next
    }

    fun isSystemSpatializerActive(): Boolean {
        if (Build.VERSION.SDK_INT < 32) return false
        return runCatching {
                val sp = audioManager?.spatializer ?: return false
                sp.isAvailable && sp.isEnabled
            }
            .getOrDefault(false)
    }

    fun release() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    private fun readCurrentSnapshot(): AudioOutputSnapshot {
        val devices = runCatching {
            audioManager
                ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.map { AudioDeviceDescriptor(it.type, it.productName?.toString().orEmpty()) }
                .orEmpty()
        }
            .getOrDefault(emptyList())
        return resolveSnapshot(devices)
    }

    companion object {
        fun classifyExternalKind(type: Int): AudioOutputKind? =
            when (type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioOutputKind.WIRED_HEADSET
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputKind.BLUETOOTH
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputKind.USB_AUDIO
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_AUX_LINE,
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC -> AudioOutputKind.OTHER_EXTERNAL
                else ->
                    if (
                        Build.VERSION.SDK_INT >= 31 &&
                            (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                    ) {
                        AudioOutputKind.BLUETOOTH
                    } else if (
                        Build.VERSION.SDK_INT >= 33 && type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                    ) {
                        AudioOutputKind.BLUETOOTH
                    } else null
            }

        private fun priority(kind: AudioOutputKind): Int =
            when (kind) {
                AudioOutputKind.BLUETOOTH -> 4
                AudioOutputKind.USB_AUDIO -> 3
                AudioOutputKind.WIRED_HEADSET -> 2
                AudioOutputKind.OTHER_EXTERNAL -> 1
                AudioOutputKind.SPEAKER -> 0
            }

        private fun defaultLabel(kind: AudioOutputKind): String =
            when (kind) {
                AudioOutputKind.BLUETOOTH -> "蓝牙音频设备"
                AudioOutputKind.USB_AUDIO -> "USB 耳机"
                AudioOutputKind.WIRED_HEADSET -> "有线耳机"
                AudioOutputKind.OTHER_EXTERNAL -> "外部音频设备"
                AudioOutputKind.SPEAKER -> "手机扬声器"
            }

        fun resolveSnapshot(devices: List<AudioDeviceDescriptor>): AudioOutputSnapshot {
            val external =
                devices
                    .mapNotNull { descriptor ->
                        classifyExternalKind(descriptor.type)?.let { kind -> descriptor to kind }
                    }
                    .maxByOrNull { priority(it.second) }
            if (external != null) {
                val (descriptor, kind) = external
                val cleanedName = descriptor.productName.trim()
                val label = cleanedName.ifEmpty { defaultLabel(kind) }
                val keyName = cleanedName.lowercase().ifEmpty { kind.name.lowercase() }
                return AudioOutputSnapshot(
                    kind = kind,
                    label = label,
                    deviceKey = "${kind.name.lowercase()}|$keyName",
                    hasExternalOutput = true,
                )
            }
            return AudioOutputSnapshot(
                kind = AudioOutputKind.SPEAKER,
                label = "手机扬声器",
                deviceKey = "speaker",
                hasExternalOutput = false,
            )
        }
    }
}
