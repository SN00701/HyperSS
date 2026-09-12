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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import com.hyperss.app.R
import com.hyperss.app.ui.AmbientGlassLayer
import com.hyperss.app.ui.GlassChipButton
import com.hyperss.app.ui.GlassDialog
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.LiquidTopBar
import com.hyperss.app.ui.liquidGlassLayer
import com.hyperss.app.ui.rememberBlackCanvasBackdrop
import com.hyperss.app.ui.rememberLiquidBackdrop
import com.hyperss.app.ui.rememberLiquidContentBackdrop
import com.hyperss.app.ui.rememberTopBarBlurFraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
                message = if (ok) context.getString(R.string.pdf_imported) else context.getString(R.string.pdf_import_failed)
                refresh()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 顶部液态玻璃：内容滚动时玻璃标题栏渐入
    val liquidAmbient = rememberLiquidBackdrop()
    val liquidContent = rememberLiquidContentBackdrop()
    val listState = rememberLazyListState()
    val blurFraction = rememberTopBarBlurFraction(listState)
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 环境光斑玻璃层：PDF 列表卡片液态玻璃采样它
        AmbientGlassLayer(liquidAmbient)
        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pdf_loading), style = MiuixTheme.textStyles.body1)
            }
            pdfList.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.pdf_empty),
                    style = MiuixTheme.textStyles.body1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .liquidGlassLayer(liquidContent),
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
                        liquidBackdrop = liquidAmbient,
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
                                Icon(MiuixIcons.Rename, contentDescription = stringResource(R.string.rename))
                            }
                            IconButton(onClick = { saveTarget = item }) {
                                Icon(MiuixIcons.Copy, contentDescription = stringResource(R.string.pdf_save_copy))
                            }
                        }
                    }
                }
            }
        }
        LiquidTopBar(
            backdrop = liquidContent,
            fraction = blurFraction,
            refreshKey = listState.firstVisibleItemIndex * 1_000_000 + listState.firstVisibleItemScrollOffset,
            modifier = Modifier.align(Alignment.TopCenter),
            onHeightChanged = { barHeight = it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 4.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 返回玻璃圆钮：折射透出列表内容
                GlassChipButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp),
                    liquidBackdrop = liquidContent,
                    redrawKey = listState.firstVisibleItemIndex * 1_000_000 + listState.firstVisibleItemScrollOffset,
                    shape = CircleShape,
                ) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    stringResource(R.string.pdf_reader_title),
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.weight(1f),
                )
                GlassChipButton(
                    onClick = { importLauncher.launch(arrayOf("application/pdf")) },
                    liquidBackdrop = liquidContent,
                    redrawKey = listState.firstVisibleItemIndex * 1_000_000 + listState.firstVisibleItemScrollOffset,
                ) {
                    Text(
                        stringResource(R.string.pdf_import),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                GlassChipButton(
                    onClick = { refresh() },
                    liquidBackdrop = liquidContent,
                    redrawKey = listState.firstVisibleItemIndex * 1_000_000 + listState.firstVisibleItemScrollOffset,
                ) {
                    Text(
                        stringResource(R.string.pdf_refresh),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
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
        GlassDialog(
            show = true,
            title = stringResource(R.string.pdf_rename_title),
            onDismissRequest = { renameTarget = null },
        ) {
            Column {
                TextField(value = newName, onValueChange = { newName = it }, label = stringResource(R.string.pdf_file_name), singleLine = true)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = stringResource(R.string.cancel), onClick = { renameTarget = null })
                    Spacer(modifier = Modifier.size(12.dp))
                    TextButton(
                        text = stringResource(R.string.save),
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val ok = com.hyperss.app.util.PdfUtils.renamePdf(context, item, newName)
                            message = if (ok) context.getString(R.string.pdf_renamed) else context.getString(R.string.pdf_rename_failed)
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
            mutableStateOf(item.name.removeSuffix(".pdf") + context.getString(R.string.pdf_copy_suffix))
        }
        GlassDialog(
            show = true,
            title = stringResource(R.string.pdf_save_copy_title),
            onDismissRequest = { saveTarget = null },
        ) {
            Column {
                TextField(value = newName, onValueChange = { newName = it }, label = stringResource(R.string.pdf_new_file_name), singleLine = true)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = stringResource(R.string.cancel), onClick = { saveTarget = null })
                    Spacer(modifier = Modifier.size(12.dp))
                    TextButton(
                        text = stringResource(R.string.save),
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    com.hyperss.app.util.PdfUtils.saveCopy(context, item, newName)
                                }
                                message = if (uri != null) context.getString(R.string.pdf_saved) else context.getString(R.string.pdf_save_failed)
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
        GlassDialog(
            show = true,
            title = stringResource(R.string.pdf_importing),
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
        GlassDialog(
            show = true,
            title = stringResource(R.string.hint),
            summary = msg,
            onDismissRequest = { message = null },
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = stringResource(R.string.ok), onClick = { message = null })
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
    // 液态玻璃采样层：记录「黑底 + 当前页 PDF」，悬浮玻璃元素折射透出页面画面
    val canvasLayer = rememberBlackCanvasBackdrop()
    val chipTint = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        if (pages == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pdf_opening), color = androidx.compose.ui.graphics.Color.White)
            }
        } else if (pages!!.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pdf_cannot_open), color = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .liquidGlassLayer(canvasLayer),
            ) { page ->
                ZoomablePdfPage(bitmap = pages!![page])
            }
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
                cornerRadius = 14.dp,
                background = chipTint,
                liquidBackdrop = canvasLayer,
                redrawKey = pagerState.currentPage,
            ) {
                Text(
                    stringResource(
                        R.string.pdf_page_indicator,
                        item.name,
                        pagerState.currentPage + 1,
                        pages!!.size,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
        }

        GlassSurface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 26.dp, end = 16.dp)
                .size(40.dp),
            cornerRadius = 20.dp,
            background = chipTint,
            liquidBackdrop = canvasLayer,
            redrawKey = pagerState.currentPage,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(MiuixIcons.Close, contentDescription = stringResource(R.string.close), tint = androidx.compose.ui.graphics.Color.White)
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
