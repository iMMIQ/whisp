// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisp.data.*
import kotlin.math.roundToInt

@Composable
internal fun SubtitleActionSheet(
    cue: Cue,
    subtitleSize: Int,
    canSeek: Boolean,
    onPlayFromHere: () -> Unit,
    onSaveCue: () -> Unit,
    onMarkLoopStart: () -> Unit,
    onLoopCue: (() -> Unit)?,
    onCopyCue: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(
                clock(cue.startMs),
                style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                color = Rose,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                cue.text,
                color = Cream,
                fontSize = subtitleSize.sp,
                lineHeight = (subtitleSize + 10).sp,
                modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(6.dp))
        WhispSheetRow(
            icon = WhispIcons.Outlined.PlayArrow,
            title = "从这里播放",
            enabled = canSeek,
            onClick = {
                onPlayFromHere()
                dismiss()
            },
        )
        WhispSheetRow(
            icon = WhispIcons.Outlined.BookmarkAdd,
            title = "保存这句",
            onClick = {
                onSaveCue()
                dismiss()
            },
        )
        WhispSheetRow(
            icon = WhispIcons.Outlined.Flag,
            title = "设为 A–B 起点",
            enabled = canSeek,
            onClick = {
                onMarkLoopStart()
                dismiss()
            },
        )
        if (onLoopCue != null) {
            WhispSheetRow(
                icon = WhispIcons.Outlined.RepeatOne,
                title = "单句循环",
                enabled = canSeek,
                onClick = {
                    onLoopCue()
                    dismiss()
                },
            )
        }
        WhispSheetRow(
            icon = WhispIcons.Outlined.ContentCopy,
            title = "复制台词",
            onClick = {
                onCopyCue()
                dismiss()
            },
        )
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
internal fun LiveSubtitleTuneOverlay(
    settings: Settings,
    trackOffsetMs: Long,
    onTrackOffsetChange: (Long) -> Unit,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    hasCandidates: Boolean = false,
    onOpenPicker: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
) {
    val haptics = rememberWhispHaptics()
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
            .border(0.5.dp, Rose.copy(alpha = 0.25f), shape)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "字幕实时微调",
                style = MaterialTheme.typography.titleMedium,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            IconAction(WhispIcons.Outlined.Search, "搜索台词", Muted, haptic = HapticKind.Tick) {
                onClose()
                onOpenSearch()
            }
            if (hasCandidates) {
                IconAction(
                    WhispIcons.Outlined.Translate,
                    "切换字幕与双语",
                    Muted,
                    haptic = HapticKind.Tick,
                ) {
                    onClose()
                    onOpenPicker()
                }
            }
            TextButton(onClick = onClose) { Text("完成", fontWeight = FontWeight.SemiBold) }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("本曲偏移", style = MaterialTheme.typography.labelLarge, color = Muted)
                Text(
                    "${trackOffsetMs} ms",
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                    color = if (trackOffsetMs != 0L) Rose else Muted,
                )
            }
            OutlinedButton(
                onClick = {
                    haptics.tick()
                    onTrackOffsetChange(trackOffsetMs - 250L)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "−250",
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                )
            }
            if (trackOffsetMs != 0L) {
                TextButton(
                    onClick = {
                        haptics.tick()
                        onTrackOffsetChange(0L)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("归零")
                }
            }
            OutlinedButton(
                onClick = {
                    haptics.tick()
                    onTrackOffsetChange(trackOffsetMs + 250L)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "+250",
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "字号",
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
                modifier = Modifier.width(44.dp),
            )
            Box(Modifier.weight(1f)) {
                SyncedSlider(
                    settings.subtitleSize.toFloat(),
                    16f..30f,
                    steps = 13,
                    onValueChange = { value ->
                        onUpdateSettings { it.copy(subtitleSize = value.roundToInt()) }
                    },
                )
            }
            Text(
                "${settings.subtitleSize}sp",
                style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                color = Rose,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        EncodingChips(
            selected = settings.textEncoding,
            onSelect = { encoding ->
                haptics.tick()
                onUpdateSettings { it.copy(textEncoding = encoding) }
            },
        )
    }
}

@Composable
internal fun SubtitlePickerSheet(
    candidates: List<AttachmentEntity>,
    activePrimaryId: String?,
    activeSecondaryId: String?,
    hasOverride: Boolean,
    onSelect: (primaryId: String?, secondaryId: String?) -> Unit,
    onResetAuto: () -> Unit,
    onImportExternal: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val haptics = rememberWhispHaptics()
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "字幕轨与双语",
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            if (hasOverride) {
                TextButton(
                    onClick = {
                        haptics.tick()
                        onResetAuto()
                        dismiss()
                    },
                ) {
                    Text("恢复自动")
                }
            }
        }
        if (onImportExternal != null) {
            WhispSheetRow(
                icon = WhispIcons.Outlined.FileUpload,
                title = "从本地导入外挂字幕",
                subtitle = "支持 .vtt / .srt / .lrc / .ass，导入后永久绑定本作品",
                onClick = {
                    dismiss()
                    onImportExternal()
                },
            )
        }
        if (candidates.isEmpty()) {
            Text(
                "当前作品暂无内置字幕，可点击上方从本机导入",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        } else {
            Text(
                "点选设为主字幕；点右侧「双语」可叠加第二语言对照",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).navigationBarsPadding()) {
                items(candidates, key = { it.id }) { item ->
                    val isPrimary = item.id == activePrimaryId
                    val isSecondary = item.id == activeSecondaryId
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                haptics.tick()
                                val nextSecondary =
                                    if (activeSecondaryId == item.id) null else activeSecondaryId
                                onSelect(item.id, nextSecondary)
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                item.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isPrimary) Rose else Cream,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val folder = item.relativePath.substringBeforeLast('/', "")
                            if (folder.isNotBlank()) {
                                Text(
                                    folder,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (isPrimary) {
                            Text(
                                "主字幕",
                                style = MaterialTheme.typography.labelMedium,
                                color = Rose,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        } else if (activePrimaryId != null) {
                            FilterChip(
                                selected = isSecondary,
                                onClick = {
                                    haptics.tick()
                                    onSelect(activePrimaryId, if (isSecondary) null else item.id)
                                },
                                label = {
                                    Text(
                                        if (isSecondary) "副字幕 ✓" else "双语",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubtitleSearchSheet(
    cues: List<DisplayCue>,
    offsetMs: Long,
    onJump: (Cue) -> Unit,
    onSearchWork: (suspend (String) -> List<WorkSubtitleMatch>)? = null,
    onJumpTrack: ((TrackEntity, Cue) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searchAllTracks by remember { mutableStateOf(false) }
    var workMatches by remember { mutableStateOf<List<WorkSubtitleMatch>>(emptyList()) }
    var searchingWork by remember { mutableStateOf(false) }
    val haptics = rememberWhispHaptics()
    val matches =
        remember(cues, query) {
            val q = query.trim()
            if (q.isEmpty()) cues.take(50)
            else
                cues.filter { cue ->
                    cue.text.contains(q, ignoreCase = true) ||
                        cue.secondaryText?.contains(q, ignoreCase = true) == true
                }
        }
    LaunchedEffect(searchAllTracks, query, onSearchWork) {
        if (!searchAllTracks || onSearchWork == null) {
            workMatches = emptyList()
            searchingWork = false
            return@LaunchedEffect
        }
        val q = query.trim()
        if (q.isEmpty()) {
            workMatches = emptyList()
            searchingWork = false
            return@LaunchedEffect
        }
        searchingWork = true
        kotlinx.coroutines.delay(220L)
        workMatches = runCatching { onSearchWork(q) }.getOrDefault(emptyList())
        searchingWork = false
    }
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text(
                "搜索台词",
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            if (onSearchWork != null && onJumpTrack != null) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    SegmentedButton(
                        selected = !searchAllTracks,
                        onClick = { searchAllTracks = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) {
                        Text("当前曲目")
                    }
                    SegmentedButton(
                        selected = searchAllTracks,
                        onClick = { searchAllTracks = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) {
                        Text("整部作品")
                    }
                }
            }
            WhispSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = if (searchAllTracks) "输入关键词搜索整部作品字幕" else "输入台词关键词",
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (searchAllTracks) {
                when {
                    query.isBlank() ->
                        Text(
                            "输入关键词即可跨曲检索整部作品的字幕",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                        )
                    searchingWork ->
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(24.dp),
                                color = Rose,
                                strokeWidth = 2.dp,
                            )
                        }
                    workMatches.isEmpty() ->
                        Text(
                            "整部作品中未找到匹配台词",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                        )
                    else ->
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            items(
                                workMatches.size,
                                key = {
                                    "${workMatches[it].track.id}_${workMatches[it].cue.startMs}_$it"
                                },
                            ) { idx ->
                                val hit = workMatches[idx]
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            haptics.tick()
                                            onJumpTrack?.invoke(hit.track, hit.cue)
                                            dismiss()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        clock(hit.cue.startMs),
                                        style =
                                            MaterialTheme.typography.labelMedium.merge(
                                                TabularNumeralStyle,
                                            ),
                                        color = Rose,
                                        modifier = Modifier.width(58.dp).padding(top = 2.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            hit.track.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            hit.cue.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Cream,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                }
            } else if (matches.isEmpty()) {
                Text(
                    "未找到匹配台词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(matches.size, key = { "${matches[it].startMs}_$it" }) { idx ->
                        val item = matches[idx]
                        val displayTime = (item.startMs + offsetMs).coerceAtLeast(0L)
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptics.tick()
                                    onJump(item.toCue())
                                    dismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                clock(displayTime),
                                style =
                                    MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                                color = Rose,
                                modifier = Modifier.width(58.dp).padding(top = 2.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Cream,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                item.secondaryText?.let { sub ->
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
