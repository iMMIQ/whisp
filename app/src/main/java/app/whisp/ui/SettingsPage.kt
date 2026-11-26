// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.EditionPreference
import app.whisp.data.LibraryBackup
import app.whisp.data.StorageUsage
import app.whisp.data.SubtitleLang
import app.whisp.data.SupportedSkipSeconds
import app.whisp.system.AppLockAuthenticator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val settingsSpeeds = listOf(0.75f, 0.85f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 2.0f)

private fun formatBytesCompact(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
        bytes > 0 -> "${(bytes + 1023) / 1024} KiB"
        else -> "0"
    }

@OptIn(FlowPreview::class)
@Composable
internal fun SettingsPage(
    graph: AppGraph,
    openStorage: () -> Unit,
    reselectTick: Int = 0,
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val audioOutput by graph.audioDevices.output.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberWhispHaptics()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var backupBusy by remember { mutableStateOf(false) }

    val exportBackup =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            backupBusy = true
            scope.launch {
                try {
                    val snapshot = LibraryBackup.exportSnapshot(graph.library, graph.settings)
                    val encoded = LibraryBackup.encode(snapshot)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(encoded.toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入目标文件")
                    }
                    haptics.confirm()
                    graph.messages.tryEmit("本地备份已导出（不含账号凭据）")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.messages.tryEmit(e.message ?: "导出备份失败")
                } finally {
                    backupBusy = false
                }
            }
        }

    val pickAutoBackupTarget =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            backupBusy = true
            scope.launch {
                try {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    val targetStr = uri.toString()
                    graph.settings.update { it.copy(autoBackupUri = targetStr) }
                    LibraryBackup.writeToUri(context, targetStr, graph.library, graph.settings)
                    LibraryBackup.scheduleAutoBackup(context, enabled = true)
                    haptics.confirm()
                    graph.messages.tryEmit("已开启每日自动备份并完成首次同步")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.messages.tryEmit(e.message ?: "配置自动备份失败")
                } finally {
                    backupBusy = false
                }
            }
        }

    val importBackup =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            backupBusy = true
            scope.launch {
                try {
                    val raw =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                input.readBytes().toString(Charsets.UTF_8)
                            } ?: error("无法读取备份文件")
                        }
                    val snapshot = LibraryBackup.decode(raw)
                    val summary =
                        LibraryBackup.importSnapshot(snapshot, graph.library, graph.settings)
                    haptics.confirm()
                    graph.messages.tryEmit(
                        "已恢复设置、${summary.restoredProgress} 条进度与 ${summary.restoredBookmarks} 条书签",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.messages.tryEmit(e.message ?: "恢复备份失败")
                } finally {
                    backupBusy = false
                }
            }
        }

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) listState.animateScrollToItem(0)
    }

    LaunchedEffect(graph, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            merge(
                    flowOf(Unit),
                    graph.storageChanges,
                    graph.media.downloads.map { Unit },
                    graph.settings.streamingCacheMiBUpdates.map { Unit },
                )
                .debounce(300)
                .collect { usage = graph.images.storageUsage() }
        }
    }

    val totalBytes = usage?.let {
        it.offline + it.streaming + it.images + it.customArtwork + it.library
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("settings-page")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.title_settings),
                style = MaterialTheme.typography.headlineLarge,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "section-comfort") {
                SettingsGroupCard("外观与交互") {
                    WhispToggleRow(
                        label = "深夜纯黑背景",
                        subtitle = "OLED 纯黑底色并柔化夜间播放按钮亮度",
                        checked = settings.amoledBlack,
                        tag = "settings-amoled-black",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(amoledBlack = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "触觉反馈",
                        subtitle = "播放控制、精细拖动与手势越阈时的轻微震动",
                        checked = settings.touchFeedback,
                        tag = "settings-touch-feedback",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(touchFeedback = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "减少动态效果",
                        subtitle = "关闭过渡动画与弹性缩放",
                        checked = settings.reduceMotion,
                        tag = "settings-reduce-motion",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(reduceMotion = checked) }
                            }
                        },
                    )
                }
            }
            item(key = "section-audio") {
                SettingsGroupCard("音声与听感") {
                    WhispToggleRow(
                        label = "左右声道互换",
                        subtitle = "修正双耳声道录反或睡眠耳机反戴",
                        checked = settings.swapChannels,
                        tag = "settings-swap-channels",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(swapChannels = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "夜间防炸耳柔化",
                        subtitle = "压缩突发高音峰值并轻度提亮轻声耳语",
                        checked = settings.nightDynamics,
                        tag = "settings-night-dynamics",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(nightDynamics = checked) }
                            }
                        },
                    )
                    if (settings.nightDynamics) {
                        val strengthOptions = listOf(1 to "轻柔", 2 to "标准", 3 to "强力")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            strengthOptions.forEachIndexed { index, (level, label) ->
                                SegmentedButton(
                                    selected = settings.nightDynamicsStrength == level,
                                    onClick = {
                                        haptics.tick()
                                        scope.launch {
                                            graph.settings.update {
                                                it.copy(nightDynamicsStrength = level)
                                            }
                                        }
                                    },
                                    shape =
                                        SegmentedButtonDefaults.itemShape(
                                            index,
                                            strengthOptions.size,
                                        ),
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    WhispToggleRow(
                        label = "低切降噪（80 Hz）",
                        subtitle = "削减麦克风喷麦与低频环境底噪",
                        checked = settings.highPassFilter,
                        tag = "settings-high-pass",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(highPassFilter = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "单声道合并",
                        subtitle = "侧睡单耳佩戴时听全双耳内容",
                        checked = settings.monoAudio,
                        tag = "settings-mono-audio",
                        onCheckedChange = { checked ->
                            scope.launch { graph.settings.update { it.copy(monoAudio = checked) } }
                        },
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "左右声道平衡",
                            style = MaterialTheme.typography.labelLarge,
                            color = Muted,
                            modifier = Modifier.weight(1f),
                        )
                        val balPct = (settings.stereoBalance * 100f).roundToInt()
                        Text(
                            when {
                                balPct < -1 -> "左 +${-balPct}%"
                                balPct > 1 -> "右 +${balPct}%"
                                else -> "居中"
                            },
                            style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                            color = if (abs(balPct) > 1) Rose else Muted,
                        )
                        if (abs(settings.stereoBalance) > 0.02f) {
                            TextButton(
                                onClick = {
                                    haptics.tick()
                                    scope.launch {
                                        graph.settings.update { it.copy(stereoBalance = 0f) }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("居中")
                            }
                        }
                    }
                    SyncedSlider(
                        settings.stereoBalance,
                        -1f..1f,
                        onValueChange = { value ->
                            val snapped = if (abs(value) < 0.04f) 0f else value
                            scope.launch {
                                graph.settings.update { it.copy(stereoBalance = snapped) }
                            }
                        },
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "夜间精细音量",
                            style = MaterialTheme.typography.labelLarge,
                            color = Muted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(settings.volumeGain * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                            color = if (settings.volumeGain < 0.99f) Rose else Muted,
                        )
                    }
                    SyncedSlider(
                        settings.volumeGain,
                        0.15f..1.0f,
                        onValueChange = { value ->
                            scope.launch { graph.settings.update { it.copy(volumeGain = value) } }
                        },
                    )
                    WhispToggleRow(
                        label = "连续音轨无缝衔接",
                        subtitle = "减少章节切曲停顿，必要时自动回退软件拼接",
                        checked = settings.gaplessPriority,
                        tag = "settings-gapless",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(gaplessPriority = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "恢复播放微淡入",
                        subtitle = "避免佩戴耳机恢复播放时的生硬切入",
                        checked = settings.softFadePause,
                        tag = "settings-soft-fade",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(softFadePause = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "长暂停后自动回退 3 秒",
                        subtitle = "暂停超过 5 分钟再听时自动回溯衔接",
                        checked = settings.smartResumeRewind,
                        tag = "settings-smart-rewind",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(smartResumeRewind = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "提示音时压低音量而非暂停",
                        subtitle = "导航或通知语音播报时自动降低背景声音",
                        checked = settings.duckOnInterrupt,
                        tag = "settings-duck-interrupt",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(duckOnInterrupt = checked) }
                            }
                        },
                    )
                    if (Build.VERSION.SDK_INT >= 32) {
                        WhispToggleRow(
                            label = "保持双耳原声（禁用空间音频重混）",
                            subtitle = "防止系统空间音频破坏 3Dio / 双耳音声方位感",
                            checked = settings.preserveBinaural,
                            tag = "settings-preserve-binaural",
                            onCheckedChange = { checked ->
                                scope.launch {
                                    graph.settings.update { it.copy(preserveBinaural = checked) }
                                }
                            },
                        )
                    }
                    WhispToggleRow(
                        label = "外放防社死拦截",
                        subtitle = "当前输出：${audioOutput.label} · 未接耳机外放前二次确认",
                        checked = settings.speakerGuard,
                        tag = "settings-speaker-guard",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(speakerGuard = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "按耳机自动切换听感",
                        subtitle = "自动记忆每副耳机的声道、夜间柔化与精细音量",
                        checked = settings.autoDeviceAudioProfile,
                        tag = "settings-device-profile",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(autoDeviceAudioProfile = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "摇一摇延长助眠定时",
                        subtitle = "仅在助眠定时期间启用传感器，轻晃手机延长 15 分钟",
                        checked = settings.shakeExtendTimer,
                        tag = "settings-shake-timer",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(shakeExtendTimer = checked) }
                            }
                        },
                    )
                }
            }
            item(key = "section-playback") {
                SettingsGroupCard("播放与字幕") {
                    Text(
                        "快进与后退步长",
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SupportedSkipSeconds.forEachIndexed { index, sec ->
                            SegmentedButton(
                                selected = settings.skipSeconds == sec,
                                onClick = {
                                    haptics.tick()
                                    scope.launch {
                                        graph.settings.update { it.copy(skipSeconds = sec) }
                                    }
                                },
                                shape =
                                    SegmentedButtonDefaults.itemShape(
                                        index,
                                        SupportedSkipSeconds.size,
                                    ),
                            ) {
                                Text(
                                    "${sec}秒",
                                    style =
                                        MaterialTheme.typography.labelMedium.merge(
                                            TabularNumeralStyle,
                                        ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.label_playback_speed),
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(settingsSpeeds, key = { it }) { speed ->
                            val label =
                                when (speed) {
                                    1.0f -> "1x"
                                    2.0f -> "2x"
                                    else -> "${speed}x"
                                }
                            FilterChip(
                                selected = abs(settings.playbackSpeed - speed) < 0.01f,
                                onClick = {
                                    haptics.tick()
                                    graph.playback.setPlaybackSpeed(speed)
                                },
                                label = {
                                    Text(
                                        label,
                                        style =
                                            MaterialTheme.typography.labelMedium.merge(
                                                TabularNumeralStyle,
                                            ),
                                    )
                                },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "字幕字号",
                            style = MaterialTheme.typography.labelLarge,
                            color = Muted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "样例 · ${settings.subtitleSize}sp",
                            color = Cream,
                            fontSize = settings.subtitleSize.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SyncedSlider(
                        settings.subtitleSize.toFloat(),
                        16f..30f,
                        steps = 13,
                        onValueChange = { value ->
                            scope.launch {
                                graph.settings.update {
                                    it.copy(subtitleSize = value.roundToInt())
                                }
                            }
                        },
                    )
                    Text(
                        stringResource(R.string.label_text_encoding),
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    EncodingChips(
                        selected = settings.textEncoding,
                        onSelect = { encoding ->
                            haptics.tick()
                            scope.launch {
                                graph.settings.update { it.copy(textEncoding = encoding) }
                            }
                        },
                    )
                    Text(
                        "多版本默认优先",
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    val editionOptions =
                        listOf(
                            EditionPreference.COMPACT_MP3 to "MP3 轻享",
                            EditionPreference.LOSSLESS_FIRST to "无损优先",
                            EditionPreference.DEFAULT to "目录顺序",
                        )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        editionOptions.forEachIndexed { index, (pref, label) ->
                            SegmentedButton(
                                selected = settings.editionPreference == pref,
                                onClick = {
                                    haptics.tick()
                                    scope.launch {
                                        graph.settings.update { it.copy(editionPreference = pref) }
                                    }
                                },
                                shape =
                                    SegmentedButtonDefaults.itemShape(index, editionOptions.size),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Text(
                        "主字幕语言偏好",
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SubtitleLang.primaryOptions, key = { it }) { code ->
                            FilterChip(
                                selected = settings.preferredPrimarySubtitleLang == code,
                                onClick = {
                                    haptics.tick()
                                    scope.launch {
                                        graph.settings.update {
                                            it.copy(preferredPrimarySubtitleLang = code)
                                        }
                                    }
                                },
                                label = { Text(SubtitleLang.label(code)) },
                            )
                        }
                    }
                    Text(
                        "自动双语副字幕语言",
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SubtitleLang.secondaryOptions, key = { it }) { code ->
                            FilterChip(
                                selected = settings.preferredSecondarySubtitleLang == code,
                                onClick = {
                                    haptics.tick()
                                    scope.launch {
                                        graph.settings.update {
                                            it.copy(preferredSecondarySubtitleLang = code)
                                        }
                                    }
                                },
                                label = { Text(SubtitleLang.label(code)) },
                            )
                        }
                    }
                }
            }
            item(key = "section-privacy") {
                SettingsGroupCard("隐私保护") {
                    WhispToggleRow(
                        label = "隐藏通知封面与标题",
                        subtitle = "系统媒体通知仅显示「正在收听」",
                        checked = settings.hideArtwork,
                        tag = "settings-notification-privacy",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(hideArtwork = checked) }
                            }
                        },
                    )
                    if (Build.VERSION.SDK_INT >= 33) {
                        WhispToggleRow(
                            label = "隐藏最近任务预览",
                            subtitle = "切换多任务卡片时隐藏应用画面",
                            checked = settings.hideRecents,
                            tag = "settings-recents-privacy",
                            onCheckedChange = { checked ->
                                scope.launch {
                                    graph.settings.update { it.copy(hideRecents = checked) }
                                }
                            },
                        )
                    }
                    WhispToggleRow(
                        label = "应用锁（生物识别 / 锁屏密码）",
                        subtitle = "启动或切后台超过 30 秒后验证解锁，不影响后台播放",
                        checked = settings.appLockEnabled,
                        tag = "settings-app-lock",
                        onCheckedChange = { checked ->
                            if (checked && !AppLockAuthenticator.isDeviceSecure(context)) {
                                graph.messages.tryEmit("请先在系统设置中开启锁屏密码或生物识别")
                            } else {
                                scope.launch {
                                    graph.settings.update { it.copy(appLockEnabled = checked) }
                                }
                            }
                        },
                    )
                }
            }
            item(key = "section-network-backup") {
                SettingsGroupCard("网络与数据迁移") {
                    WhispToggleRow(
                        label = "仅在 Wi-Fi 下离线下载",
                        subtitle = "避免无损音声与视频意外消耗移动数据流量",
                        checked = settings.wifiOnlyDownloads,
                        tag = "settings-wifi-only-downloads",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(wifiOnlyDownloads = checked) }
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            enabled = !backupBusy,
                            onClick = {
                                val stamp =
                                    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                exportBackup.launch("whisp-backup-$stamp.json")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(WhispIcons.Outlined.FileUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("导出备份")
                        }
                        OutlinedButton(
                            enabled = !backupBusy,
                            onClick = { importBackup.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(WhispIcons.Outlined.FileDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("恢复备份")
                        }
                    }
                    Text(
                        "备份包含设置、歌单、个人标签、书签、播放进度与单曲字幕偏移，不含任何 NAS 密码或账号 Cookie",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    val autoEnabled = settings.autoBackupUri.isNotBlank()
                    val lastSyncLabel =
                        remember(settings.lastAutoBackupAt) {
                            if (settings.lastAutoBackupAt > 0L)
                                SimpleDateFormat("MM-dd HH:mm", Locale.US)
                                    .format(Date(settings.lastAutoBackupAt))
                            else null
                        }
                    Text(
                        if (autoEnabled) {
                            "定时自动备份：已开启" + (lastSyncLabel?.let { " · 上次 $it" } ?: "")
                        } else "定时自动备份：未开启",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (autoEnabled) Rose else Muted,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            enabled = !backupBusy,
                            onClick = {
                                pickAutoBackupTarget.launch("whisp-auto-backup.json")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(if (autoEnabled) "更换备份文件" else "选择自动备份文件")
                        }
                        if (autoEnabled) {
                            FilledTonalButton(
                                enabled = !backupBusy,
                                onClick = {
                                    backupBusy = true
                                    scope.launch {
                                        try {
                                            LibraryBackup.writeToUri(
                                                context,
                                                settings.autoBackupUri,
                                                graph.library,
                                                graph.settings,
                                            )
                                            haptics.confirm()
                                            graph.messages.tryEmit("自动备份已同步")
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            graph.messages.tryEmit(e.message ?: "同步失败")
                                        } finally {
                                            backupBusy = false
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("立即同步")
                            }
                        }
                    }
                    if (autoEnabled) {
                        TextButton(
                            enabled = !backupBusy,
                            onClick = {
                                scope.launch {
                                    graph.settings.update { it.copy(autoBackupUri = "") }
                                    LibraryBackup.scheduleAutoBackup(context, enabled = false)
                                    graph.messages.tryEmit("已关闭定时自动备份")
                                }
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("关闭自动备份", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item(key = "section-storage") {
                val shape = RoundedCornerShape(20.dp)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .clickable(onClick = openStorage)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Rose.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            WhispIcons.Outlined.Storage,
                            null,
                            tint = Rose,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            "存储与缓存",
                            style = MaterialTheme.typography.titleMedium,
                            color = Cream,
                        )
                        Text(
                            totalBytes?.let {
                                "已占用 ${formatBytesCompact(it)} · 缓存上限 ${settings.streamingCacheMiB} MiB"
                            } ?: "查看离线下载与在线播放缓存",
                            style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                            color = Muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        WhispIcons.Outlined.ChevronRight,
                        null,
                        tint = Muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Rose,
        )
        content()
    }
}
