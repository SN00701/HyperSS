package com.hyperss.app.ui.reader

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import com.hyperss.app.ui.BlurTopBar
import com.hyperss.app.ui.ambientGlassBackground
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.rememberTopBarBlurFraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF 阅读器：列出 Download/HyperSSDL 下的 PDF，点击进入分页查看，
 * 支持重命名与另存副本。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfReaderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var pdfList by remember { mutableStateOf<List<com.hyperss.app.util.PdfUtils.PdfItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var opening by remember { mutableStateOf<com.hyperss.app.util.PdfUtils.PdfItem?>(null) }
    var renameTarget by remember { mutableStateOf<com.hyperss.app.util.PdfUtils.PdfItem?>(null) }
    var saveTarget by remember { mutableStateOf<com.hyperss.app.util.PdfUtils.PdfItem?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            pdfList = withContext(Dispatchers.IO) { com.hyperss.app.util.PdfUtils.listPdfs(context) }
            loading = false
        }
    }

    // 从系统文件选择器导入 PDF 副本到 Download/HyperSSDL
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                val ok = withContext(Dispatchers.IO) {
                    com.hyperss.app.util.PdfUtils.importPdf(context, uri)
                }
                importing = false
                message = if (ok) "已导入到 Download/HyperSSDL" else "导入失败（不是有效的 PDF）"
                refresh()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 顶部动态模糊：内容滚动时毛玻璃标题栏渐入
    val backdrop = rememberLayerBackdrop()
    val listState = rememberLazyListState()
    val blurFraction = rememberTopBarBlurFraction(listState)
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", style = MiuixTheme.textStyles.body1)
            }
            pdfList.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无 PDF\n在项目里批量导出后会出现在这里",
                    style = MiuixTheme.textStyles.body1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop).ambientGlassBackground(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = barHeight + 16.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pdfList, key = { it.uri }) { item ->
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(onClick = { opening = item }),
                        cornerRadius = 14.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MiuixTheme.textStyles.body1, maxLines = 1)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    formatSize(item.sizeBytes) + " · " +
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                            .format(Date(item.lastModified)),
                                    style = MiuixTheme.textStyles.footnote2,
                                )
                            }
                            IconButton(onClick = { renameTarget = item }) {
                                Icon(MiuixIcons.Rename, contentDescription = "重命名")
                            }
                            IconButton(onClick = { saveTarget = item }) {
                                Icon(MiuixIcons.Copy, contentDescription = "另存副本")
                            }
                        }
                    }
                }
            }
        }
        BlurTopBar(
            backdrop = backdrop,
            fraction = blurFraction,
            modifier = Modifier.align(Alignment.TopCenter),
            onHeightChanged = { barHeight = it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 4.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = "返回")
                }
                Text("PDF 阅读器", style = MiuixTheme.textStyles.title1, modifier = Modifier.weight(1f))
                TextButton(text = "导入", onClick = { importLauncher.launch(arrayOf("application/pdf")) })
                TextButton(text = "刷新", onClick = { refresh() })
            }
        }
    }

    // PDF 分页查看
    opening?.let { item ->
        PdfViewerOverlay(
            item = item,
            onDismiss = { opening = null },
        )
    }

    // 重命名
    renameTarget?.let { item ->
        var newName by remember(item.uri) {
            mutableStateOf(item.name.removeSuffix(".pdf"))
        }
        OverlayDialog(
            show = true,
            title = "重命名 PDF",
            onDismissRequest = { renameTarget = null },
        ) {
            Column {
                TextField(value = newName, onValueChange = { newName = it }, label = "文件名", singleLine = true)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", onClick = { renameTarget = null })
                    Spacer(modifier = Modifier.size(12.dp))
                    TextButton(
                        text = "保存",
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val ok = com.hyperss.app.util.PdfUtils.renamePdf(context, item, newName)
                            message = if (ok) "已重命名" else "重命名失败"
                            renameTarget = null
                            refresh()
                        },
                    )
                }
            }
        }
    }

    // 另存副本
    saveTarget?.let { item ->
        var newName by remember(item.uri) {
            mutableStateOf(item.name.removeSuffix(".pdf") + "_副本")
        }
        OverlayDialog(
            show = true,
            title = "另存 PDF 副本",
            onDismissRequest = { saveTarget = null },
        ) {
            Column {
                TextField(value = newName, onValueChange = { newName = it }, label = "新文件名", singleLine = true)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", onClick = { saveTarget = null })
                    Spacer(modifier = Modifier.size(12.dp))
                    TextButton(
                        text = "保存",
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    com.hyperss.app.util.PdfUtils.saveCopy(context, item, newName)
                                }
                                message = if (uri != null) "已保存到 Download/HyperSSDL" else "保存失败"
                                saveTarget = null
                                refresh()
                            }
                        },
                    )
                }
            }
        }
    }

    // 导入中加载弹窗
    if (importing) {
        OverlayDialog(
            show = true,
            title = "正在导入 PDF…",
            onDismissRequest = { },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                top.yukonga.miuix.kmp.basic.CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                )
            }
        }
    }

    message?.let { msg ->
        OverlayDialog(
            show = true,
            title = "提示",
            summary = msg,
            onDismissRequest = { message = null },
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "知道了", onClick = { message = null })
            }
        }
    }
}

/** 全屏 PDF 查看层：黑底铺满整页、分页浏览 + 双指缩放 + 页码指示。 */
@Composable
private fun PdfViewerOverlay(
    item: com.hyperss.app.util.PdfUtils.PdfItem,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pages by produceState<List<Bitmap>?>(initialValue = null, key1 = item.uri) {
        value = withContext(Dispatchers.IO) {
            com.hyperss.app.util.PdfUtils.renderPdfPages(context, item.uri)
        }
    }
    val pagerState = rememberPagerState { pages?.size ?: 0 }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        if (pages == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("打开中…", color = androidx.compose.ui.graphics.Color.White)
            }
        } else if (pages!!.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无法打开该 PDF", color = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ZoomablePdfPage(bitmap = pages!![page])
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
                shape = RoundedCornerShape(14.dp),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
            ) {
                Text(
                    item.name + "  (${pagerState.currentPage + 1}/${pages!!.size})",
                    style = MiuixTheme.textStyles.footnote1,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 26.dp, end = 16.dp)
                .size(40.dp),
            shape = RoundedCornerShape(20.dp),
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
        ) {
            IconButton(onClick = onDismiss) {
                Icon(MiuixIcons.Close, contentDescription = "关闭", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

/** 单页 PDF：双指缩放/平移，单指滑动交给 Pager。 */
@Composable
private fun ZoomablePdfPage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                scale = (scale * zoomChange).coerceIn(1f, 8f)
                                if (scale > 1f) {
                                    offsetX += panChange.x
                                    offsetY += panChange.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                    transformOrigin = TransformOrigin.Center,
                ),
            contentScale = ContentScale.Fit,
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
