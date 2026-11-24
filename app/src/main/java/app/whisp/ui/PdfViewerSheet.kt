// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import app.whisp.AppGraph
import app.whisp.data.AttachmentEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink

private val NightInvertMatrix =
    ColorMatrix(
        floatArrayOf(
            -0.88f,
            0f,
            0f,
            0f,
            232f,
            0f,
            -0.88f,
            0f,
            0f,
            224f,
            0f,
            0f,
            -0.88f,
            0f,
            236f,
            0f,
            0f,
            0f,
            1f,
            0f,
        ),
    )

private class PdfSession(
    val file: File,
    val descriptor: ParcelFileDescriptor,
    val renderer: PdfRenderer,
) {
    val pageCount: Int = renderer.pageCount
    private val lock = Mutex()
    private val cache =
        object : LruCache<Int, Bitmap>(6) {
            override fun entryRemoved(
                evicted: Boolean,
                key: Int?,
                oldValue: Bitmap?,
                newValue: Bitmap?,
            ) {
                // Keep GC-managed to avoid recycling a bitmap still being drawn by Compose.
            }
        }

    suspend fun renderPage(index: Int, targetWidthPx: Int): ImageBitmap? {
        cache.get(index)?.let {
            return it.asImageBitmap()
        }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                cache.get(index)?.let {
                    return@withLock it.asImageBitmap()
                }
                if (index !in 0 until pageCount) return@withLock null
                runCatching {
                    renderer.openPage(index).use { page ->
                        val width = targetWidthPx.coerceIn(480, 2048)
                        val aspect =
                            if (page.width > 0) page.height.toFloat() / page.width.toFloat()
                            else 1.414f
                        val height = (width * aspect).toInt().coerceIn(320, 4096)
                        val bitmap = createBitmap(width, height)
                        Canvas(bitmap).drawColor(AndroidColor.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        cache.put(index, bitmap)
                        bitmap.asImageBitmap()
                    }
                }
                    .getOrNull()
            }
        }
    }

    fun close() {
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        runCatching { file.delete() }
    }
}

/**
 * Sandbox-contained PDF booklet/script viewer backed by Android's built-in [PdfRenderer], with
 * optional night-mode color inversion for dark-room reading and zero external file leakage.
 */
@Composable
internal fun PdfViewerDialog(
    attachment: AttachmentEntity,
    graph: AppGraph,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val targetWidthPx = remember(containerWidthPx) { containerWidthPx.coerceIn(720, 2048) }

    var session by remember(attachment.id) { mutableStateOf<PdfSession?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(true) }
    var error by remember(attachment.id) { mutableStateOf<String?>(null) }
    var nightInvert by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(attachment.id) {
        loading = true
        error = null
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "pdf-preview").apply { mkdirs() }
                    val temp = File(dir, "${UUID.randomUUID()}.pdf")
                    try {
                        temp.sink().buffer().use { sink ->
                            graph.library.streamAssetToSink(attachment.id, sink, 64 * 1024 * 1024)
                        }
                        val pfd =
                            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        PdfSession(temp, pfd, renderer)
                    } catch (t: Throwable) {
                        temp.delete()
                        throw t
                    }
                }
            session = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.takeIf(String::isNotBlank) ?: "无法解析此 PDF 文档"
        } finally {
            loading = false
        }
    }

    DisposableEffect(session) {
        val current = session
        onDispose { current?.close() }
    }

    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(Icons.Outlined.Close, "关闭文档", Cream, action = dismiss)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        attachment.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    session?.let { active ->
                        if (active.pageCount > 0) {
                            Text(
                                "第 ${currentPage.coerceIn(1, active.pageCount)} / ${active.pageCount} 页",
                                style =
                                    MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                                color = Muted,
                            )
                        }
                    }
                }
                IconAction(
                    if (nightInvert) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                    if (nightInvert) "切换为纸质原色" else "切换为夜间护眼反色",
                    tint = if (nightInvert) Rose else Cream,
                    haptic = HapticKind.Tick,
                ) {
                    nightInvert = !nightInvert
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    loading ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(color = Rose)
                            Text(
                                "正在加载文档",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Muted,
                            )
                        }
                    error != null ->
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    session != null -> {
                        val active = session!!
                        if (active.pageCount == 0) {
                            Text("文档为空", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding =
                                    PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(active.pageCount, key = { it }) { pageIndex ->
                                    PdfPageCard(
                                        session = active,
                                        pageIndex = pageIndex,
                                        targetWidthPx = targetWidthPx,
                                        nightInvert = nightInvert,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageCard(
    session: PdfSession,
    pageIndex: Int,
    targetWidthPx: Int,
    nightInvert: Boolean,
) {
    var bitmap by remember(session, pageIndex) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session, pageIndex, targetWidthPx) {
        bitmap = session.renderPage(pageIndex, targetWidthPx)
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (nightInvert) Color(0xFF141118) else Color.White)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            val aspect =
                if (current.height > 0) current.width.toFloat() / current.height.toFloat()
                else 0.707f
            Image(
                bitmap = current,
                contentDescription = "第 ${pageIndex + 1} 页",
                contentScale = ContentScale.FillWidth,
                colorFilter = if (nightInvert) ColorFilter.colorMatrix(NightInvertMatrix) else null,
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.35f, 2.5f)),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Rose, strokeWidth = 2.dp)
            }
        }
    }
}
