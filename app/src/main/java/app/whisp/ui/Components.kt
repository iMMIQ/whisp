// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.get
import app.whisp.R
import app.whisp.data.TextEncoding
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun WhispSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    clearLabel: String = "清空搜索",
    submitLabel: String = "搜索",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cream, fontSize = 15.sp),
        placeholder = { Text(placeholder, color = Muted.copy(alpha = 0.8f), fontSize = 15.sp) },
        leadingIcon = {
            Icon(WhispIcons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    IconAction(WhispIcons.Outlined.Close, clearLabel, Muted) {
                        if (onClear != null) onClear() else onValueChange("")
                    }
                }
                if (onSubmit != null) {
                    IconAction(WhispIcons.Outlined.Search, submitLabel, Rose, action = onSubmit)
                }
            }
        },
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = Rose.copy(alpha = 0.7f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = Rose,
            ),
        keyboardOptions =
            if (onSubmit != null) KeyboardOptions(imeAction = ImeAction.Search)
            else KeyboardOptions.Default,
        keyboardActions =
            if (onSubmit != null) KeyboardActions(onSearch = { onSubmit() })
            else KeyboardActions.Default,
    )
}

@Composable
internal fun <T> WhispTabBar(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (value, label) ->
                val isSelected = selected == value
                val bg by
                    animateColorAsState(
                        if (isSelected) Rose.copy(alpha = 0.16f) else Color.Transparent,
                        tween(if (reduced) 0 else 160),
                        label = "tab-bg",
                    )
                val fg by
                    animateColorAsState(
                        if (isSelected) Rose else Muted,
                        tween(if (reduced) 0 else 160),
                        label = "tab-fg",
                    )
                val border by
                    animateColorAsState(
                        if (isSelected) Rose.copy(alpha = 0.30f) else Color.Transparent,
                        tween(if (reduced) 0 else 160),
                        label = "tab-border",
                    )
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(bg)
                        .border(0.5.dp, border, RoundedCornerShape(999.dp))
                        .clickable { onSelect(value) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun WhispEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Rose.copy(alpha = 0.85f), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Cream,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
internal fun WhispSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = Rose,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (enabled) tint.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (enabled) tint else Muted.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Cream else Cream.copy(alpha = 0.4f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Muted else Muted.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            WhispIcons.Outlined.ChevronRight,
            null,
            tint = if (enabled) Muted.copy(alpha = 0.7f) else Muted.copy(alpha = 0.25f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun WhispToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tag: String? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberWhispHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { next ->
                    haptics.tick()
                    onCheckedChange(next)
                },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Cream else Cream.copy(alpha = 0.45f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun WhispConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = true,
    busy: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, color = Cream) },
        text = { Text(message, color = Muted) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else Rose,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    tint: Color = Cream,
    enabled: Boolean = true,
    feedback: Boolean = false,
    haptic: HapticKind = if (feedback) HapticKind.Confirm else HapticKind.None,
    action: () -> Unit,
) {
    val haptics = rememberWhispHaptics()
    val backdrop = LocalCoverControls.current
    IconButton(
        onClick = {
            when (haptic) {
                HapticKind.Confirm -> haptics.confirm()
                HapticKind.Tick -> haptics.tick()
                HapticKind.None -> Unit
            }
            action()
        },
        enabled = enabled,
        modifier =
            if (backdrop)
                Modifier.padding(2.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = .46f))
                    .border(0.5.dp, Cream.copy(alpha = 0.12f), CircleShape)
            else Modifier,
    ) {
        Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = .35f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhispSheet(
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val currentDismissible by rememberUpdatedState(dismissible)
    val sheet =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            confirmValueChange = { target -> target != SheetValue.Hidden || currentDismissible },
        )
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val dismiss = {
        if (!closing) {
            closing = true
            scope.launch {
                try {
                    sheet.hide()
                    if (!sheet.isVisible) onDismissRequest()
                } finally {
                    closing = false
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { if (currentDismissible) onDismissRequest() },
        sheetState = sheet,
        sheetGesturesEnabled = dismissible,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = Cream,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Muted.copy(alpha = 0.45f)) },
    ) {
        content(dismiss)
    }
}

@Composable
internal fun SyncedSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    key(range.start, range.endInclusive, steps) {
        val sliderState = rememberSliderState(value = value, trackRange = range, steps = steps)
        sliderState.value = value.coerceIn(range.start, range.endInclusive)
        Slider(state = sliderState, onValueChange = onValueChange)
    }
}

@Composable
internal fun EncodingChips(
    selected: String,
    onSelect: (String) -> Unit,
    horizontalPadding: Dp = 0.dp,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(TextEncoding.supported, key = { it }) { candidate ->
            FilterChip(
                selected = selected == candidate,
                onClick = { onSelect(candidate) },
                label = {
                    Text(
                        if (candidate == TextEncoding.AUTO)
                            stringResource(R.string.label_encoding_auto)
                        else candidate,
                        fontSize = 13.sp,
                    )
                },
            )
        }
    }
}

internal fun clock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

internal fun shortTitle(title: String): String {
    val trimmed = title.trim()
    val body =
        if ('～' in trimmed && !trimmed.startsWith('～')) trimmed.substringAfter('～') else trimmed
    return body.trim(' ', '～', '~', '【', '】', '「', '」', '『', '』')
}

/** Splits large script/attachment text into paragraph-aligned chunks for LazyColumn rendering. */
internal fun chunkLongText(text: String, maxChunkChars: Int = 1200): List<String> {
    if (text.isEmpty()) return emptyList()
    if (text.length <= maxChunkChars) return listOf(text)
    val chunks = mutableListOf<String>()
    val current = StringBuilder(minOf(text.length, maxChunkChars + 256))
    var start = 0
    while (start < text.length) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        val lineLen = end - start
        if (current.isNotEmpty() && current.length + 1 + lineLen > maxChunkChars) {
            chunks += current.toString()
            current.setLength(0)
        }
        if (lineLen > maxChunkChars) {
            // Hard-split pathologically long single lines so StaticLayout never stalls.
            var pos = start
            while (pos < end) {
                val sliceEnd = minOf(pos + maxChunkChars, end)
                if (current.isNotEmpty()) {
                    chunks += current.toString()
                    current.setLength(0)
                }
                chunks += text.substring(pos, sliceEnd)
                pos = sliceEnd
            }
        } else {
            if (current.isNotEmpty()) current.append('\n')
            current.append(text, start, end)
        }
        start = if (nl < 0) text.length else nl + 1
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}
