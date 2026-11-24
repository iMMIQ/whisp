// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.Intent
import android.media.AudioDeviceInfo
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.scheduler.Requirements
import app.whisp.data.*
import app.whisp.system.*
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemCapabilitiesTest {
    @Test
    fun audioDeviceMonitorClassifiesSpeakerVsExternalAndPriority() {
        val speakerOnly =
            AudioDeviceMonitor.resolveSnapshot(
                listOf(AudioDeviceDescriptor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker")),
            )
        assertTrue(speakerOnly.isBuiltInSpeakerOnly)
        assertFalse(speakerOnly.hasExternalOutput)
        assertEquals(AudioOutputKind.SPEAKER, speakerOnly.kind)

        val btAndWired =
            AudioDeviceMonitor.resolveSnapshot(
                listOf(
                    AudioDeviceDescriptor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
                    AudioDeviceDescriptor(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired"),
                    AudioDeviceDescriptor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "SleepBuds Pro"),
                ),
            )
        assertFalse(btAndWired.isBuiltInSpeakerOnly)
        assertTrue(btAndWired.hasExternalOutput)
        assertEquals(AudioOutputKind.BLUETOOTH, btAndWired.kind)
        assertEquals("SleepBuds Pro", btAndWired.label)
        assertEquals("bluetooth|sleepbuds pro", btAndWired.deviceKey)

        val unnamedUsb =
            AudioDeviceMonitor.resolveSnapshot(
                listOf(AudioDeviceDescriptor(AudioDeviceInfo.TYPE_USB_HEADSET, "  ")),
            )
        assertEquals(AudioOutputKind.USB_AUDIO, unnamedUsb.kind)
        assertEquals("USB 耳机", unnamedUsb.label)
    }

    @Test
    fun shakeStateMachineRequiresDoublePeakAndRespectsCooldown() {
        val sm = ShakeStateMachine()
        // Mild resting gravity (~1g) never triggers.
        assertFalse(sm.onAcceleration(0f, 9.81f, 0f, 1_000L))
        // First strong peak (~3g) arms detector.
        assertFalse(sm.onAcceleration(28f, 5f, 0f, 1_200L))
        // Too-close spike within 120ms debounce is ignored.
        assertFalse(sm.onAcceleration(28f, 5f, 0f, 1_250L))
        // Second peak within window triggers.
        assertTrue(sm.onAcceleration(-28f, 5f, 0f, 1_500L))
        // Immediate repeated shakes are blocked by 5s cooldown.
        assertFalse(sm.onAcceleration(30f, 0f, 0f, 2_000L))
        assertFalse(sm.onAcceleration(-30f, 0f, 0f, 2_300L))
        // After cooldown expires, another double-shake triggers again.
        assertFalse(sm.onAcceleration(30f, 0f, 0f, 7_000L))
        assertTrue(sm.onAcceleration(-30f, 0f, 0f, 7_300L))
    }

    @Test
    fun externalIntentParserExtractsCodesUrlsMediaAndActions() {
        assertEquals(
            ExternalLaunchAction.OpenPlayer,
            ExternalIntentParser.parse(Intent(ExternalIntentParser.ACTION_OPEN_PLAYER)),
        )
        assertEquals(
            ExternalLaunchAction.OpenStorage,
            ExternalIntentParser.parse(Intent(ExternalIntentParser.ACTION_OPEN_STORAGE)),
        )
        assertEquals(
            ExternalLaunchAction.StartSleepTimer(45),
            ExternalIntentParser.parse(
                Intent(ExternalIntentParser.ACTION_START_SLEEP_TIMER)
                    .putExtra(ExternalIntentParser.EXTRA_MINUTES, 45),
            ),
        )

        val sharedDlsite =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "推荐作品 https://www.dlsite.com/maniax/work/=/product_id/RJ01234567.html",
                )
            }
        assertEquals(
            ExternalLaunchAction.OpenWorkCode("RJ01234567", 1234567L),
            ExternalIntentParser.parse(sharedDlsite),
        )

        val processText =
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, "rj123456")
            }
        assertEquals(
            ExternalLaunchAction.OpenWorkCode("RJ123456", 123456L),
            ExternalIntentParser.parse(processText),
        )

        val searchText =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "耳かき バイノーラル")
            }
        assertEquals(
            ExternalLaunchAction.SearchQuery("耳かき バイノーラル"),
            ExternalIntentParser.parse(searchText),
        )

        val audioUri = Uri.parse("content://media/external/audio/media/42")
        val viewAudio =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(audioUri, "audio/flac")
            }
        assertEquals(
            ExternalLaunchAction.PlayExternalMedia(audioUri, "audio/flac"),
            ExternalIntentParser.parse(viewAudio),
        )
    }

    @Test
    fun lanDiscoveryFormatsUrlsAndSelectsUsableAddress() {
        assertEquals("_smb._tcp.", LanDiscovery.serviceTypeFor(SourceKind.SMB))
        assertEquals("_nfs._tcp.", LanDiscovery.serviceTypeFor(SourceKind.NFS))
        assertEquals("_webdavs._tcp.", LanDiscovery.serviceTypeFor(SourceKind.WEBDAV))
        assertNull(LanDiscovery.serviceTypeFor(SourceKind.LOCAL))

        assertEquals(
            "smb://nas.example/",
            DiscoveredNasService("NAS", "nas.example", 445, SourceKind.SMB).suggestedUrl(),
        )
        assertEquals(
            "smb://nas.example:4445/",
            DiscoveredNasService("NAS", "nas.example", 4445, SourceKind.SMB).suggestedUrl(),
        )
        assertEquals(
            "nfs://nas.example/",
            DiscoveredNasService("NAS", "nas.example", 2049, SourceKind.NFS).suggestedUrl(),
        )
        assertEquals(
            "https://nas.example:8443/",
            DiscoveredNasService("NAS", "nas.example", 8443, SourceKind.WEBDAV).suggestedUrl(),
        )

        val loopback = InetAddress.getByName("127.0.0.1")
        val ipv4 = InetAddress.getByName("198.51.100.10")
        assertEquals("198.51.100.10", LanDiscovery.pickBestAddress(listOf(loopback, ipv4)))
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun downloadRequirementsHonorWifiOnlyToggle() {
        val wifiOnly = MediaSourceRouting.downloadRequirements(wifiOnly = true)
        assertTrue(wifiOnly.isStorageNotLowRequired)
        assertTrue(wifiOnly.isUnmeteredNetworkRequired)

        val anyNetwork = MediaSourceRouting.downloadRequirements(wifiOnly = false)
        assertTrue(anyNetwork.isStorageNotLowRequired)
        assertFalse(anyNetwork.isUnmeteredNetworkRequired)
        assertEquals(Requirements.DEVICE_STORAGE_NOT_LOW, anyNetwork.requirements)
    }

    @Test
    fun libraryBackupRoundTripsAndExcludesSecrets() {
        val settings =
            Settings(
                preserveBinaural = true,
                speakerGuard = true,
                autoDeviceAudioProfile = true,
                shakeExtendTimer = true,
                wifiOnlyDownloads = true,
                appLockEnabled = true,
                bedsideDimMode = false,
                swapChannels = true,
                volumeGain = 0.65f,
            )
        val profile = settings.toDeviceAudioProfile()
        val applied = Settings().applyingDeviceAudioProfile(profile)
        assertTrue(applied.swapChannels)
        assertEquals(0.65f, applied.volumeGain, 0.001f)

        val snapshot =
            WhispBackupSnapshot(
                version = 1,
                exportedAt = 1_700_000_000_000L,
                settings = settings,
                trackSubtitleOffsets = mapOf("track-1" to 500L),
                deviceAudioProfiles = mapOf("bluetooth|sleepbuds" to profile),
                works =
                    listOf(
                        BackupWorkCustomization(
                            workId = "w1",
                            rj = "RJ01234567",
                            title = "自定义标题",
                            originalTitle = "原标题",
                            artist = "声优",
                            originalArtist = "声优",
                            favorite = true,
                        ),
                    ),
                progress = listOf(BackupProgress("w1", "RJ01234567", "track-1", 42_000L, 1234L)),
                bookmarks =
                    listOf(
                        BackupBookmark("b1", "w1", "RJ01234567", "track-1", 10_000L, "耳语", 20_000L),
                    ),
            )
        val json = LibraryBackup.encode(snapshot)
        assertFalse(json.contains("password", ignoreCase = true))
        assertFalse(json.contains("secret", ignoreCase = true))
        assertFalse(json.contains("cookie", ignoreCase = true))

        val decoded = LibraryBackup.decode(json)
        assertEquals(snapshot, decoded)
    }
}
