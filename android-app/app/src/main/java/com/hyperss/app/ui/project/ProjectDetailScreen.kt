package com.hyperss.app.ui.project

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperss.app.ui.BlurTopBar
import com.hyperss.app.ui.ambientGlassBackground
import com.hyperss.app.ui.GlassFloatingButton
import com.hyperss.app.ui.glassStrokeBrush
import com.hyperss.app.ui.rememberTopBarBlurFraction
import com.hyperss.app.ui.util.rememberScaledBitmap
import com.hyperss.app.util.PdfUtils
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import uniffi.hyperss_core.ImageInfo

@Composable
fun ProjectDetailScreen(
    projectId: Long,
    onBack: () -> Unit,
    viewModel: ProjectDetailViewModel = viewModel(),
) {
    val images by viewModel.images.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val error by viewModel.error.collectAsState()
    val sortDesc by viewModel.sortDesc.collectAsState()
    val batchMode = selected.isNotEmpty()
    // 展示顺序统一按命名序号排序（升/降序由排序按钮切换）
    val sortedImages = remember(images, sortDesc) {
        if (sortDesc) images.sortedByDescending { it.seq } else images.sortedBy { it.seq }
    }

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var renameTarget by remember { mutableStateOf<ImageInfo?>(null) }
    var showExportResult by remember { mutableStateOf(false) }
    var showBatchExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) { viewModel.load(projectId) }

    // 顶部动态模糊：内容滚动时毛玻璃标题栏渐入
    val backdrop = rememberLayerBackdrop()
    val gridState = rememberLazyGridState()
    val blurFraction = rememberTopBarBlurFraction(gridState)
    var barHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (images.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = barHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无图片", style = MiuixTheme.textStyles.body1)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop).ambientGlassBackground(),
                contentPadding = PaddingValues(
                    top = barHeight + 16.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(sortedImages, key = { it.id }) { image ->
                        ImageThumb(
                            image = image,
                            selected = image.id in selected,
                            onClick = {
                                if (batchMode) {
                                    viewModel.toggleSelect(image.id)
                                } else {
                                    viewerIndex = sortedImages.indexOfFirst { it.id == image.id }.takeIf { it >= 0 }
                                }
                            },
                            onLongPress = {
                                if (!batchMode) {
                                    viewModel.clearSelection()
                                    viewModel.toggleSelect(image.id)
                                }
                            },
                        )
                    }
                }
            }

        // 批量管理模式：右下角「导出」玻璃按钮（按当前勾选导出）
        if (batchMode && selected.isNotEmpty()) {
            GlassFloatingButton(
                icon = MiuixIcons.Download,
                contentDescription = "导出选中图片",
                onClick = { showBatchExport = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                size = 56.dp,
            )
        }

        // 顶部栏最后声明：绘制在网格之上，滚动图片不会盖住它，触摸也优先于网格
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
                Text(
                    "项目详情",
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.weight(1f),
                )
                if (batchMode) {
                    TextButton(
                        text = if (selected.size == images.size) "取消全选" else "全选",
                        onClick = { viewModel.toggleSelectAll() },
                    )
                    IconButton(onClick = {
                        viewModel.deleteSelected()
                        viewModel.clearSelection()
                    }) {
                        Icon(MiuixIcons.Delete, contentDescription = "删除选中", tint = MiuixTheme.colorScheme.error)
                    }
                    TextButton(text = "完成", onClick = { viewModel.clearSelection() })
                } else {
                    TextButton(
                        text = if (sortDesc) "9→1" else "1→9",
                        onClick = { viewModel.toggleSort() },
                    )
                    TextButton(text = "批量管理", onClick = {
                        if (images.isNotEmpty()) viewModel.toggleSelect(images.first().id)
                    })
                }
            }
        }
    }

    // 批量导出弹窗（图片 / PDF）
    if (showBatchExport) {
        val selectedImages = sortedImages.filter { it.id in selected }
        BatchExportDialog(
            imageCount = selectedImages.size,
            onExportImages = {
                showBatchExport = false
                exporting = true
                viewModel.exportImagesToGallery(selectedImages) {
                    exporting = false
                    showExportResult = true
                }
            },
            onExportPdf = { perPage, name ->
                showBatchExport = false
                exporting = true
                viewModel.exportImagesToPdf(selectedImages, perPage, name) { ok ->
                    exporting = false
                    if (ok) showExportResult = true
                }
            },
            onPreview = { perPage, onPages ->
                viewModel.buildPdfPreview(selectedImages, perPage) { onPages(it) }
            },
            onDismiss = { showBatchExport = false },
        )
    }

    // 导出进行中加载弹窗
    if (exporting) {
        OverlayDialog(
            show = true,
            title = "正在导出…",
            summary = "图片较多时可能需要一点时间，请勿退出应用",
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

    // 全屏查看器（左右翻页）
    viewerIndex?.let { idx ->
        FullScreenViewer(
            images = sortedImages,
            initialIndex = idx,
            onDismiss = { viewerIndex = null },
            onRename = { renameTarget = it },
            onExport = { image ->
                viewModel.exportToGallery(image) { ok -> showExportResult = ok }
                viewerIndex = null
            },
        )
    }

    if (showExportResult) {
        OverlayDialog(
            show = true,
            title = "导出",
            summary = "已导出到系统相册的 Pictures/HyperSS 目录。",
            onDismissRequest = { showExportResult = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "知道了", onClick = { showExportResult = false })
            }
        }
    }

    // 重命名别名
    renameTarget?.let { image ->
        var alias by remember(image.id) { mutableStateOf(image.alias ?: "") }
        OverlayDialog(
            show = true,
            title = "重命名显示别名",
            onDismissRequest = { renameTarget = null },
        ) {
            Column {
                TextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = "显示别名（不影响文件名）",
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "文件名保持 ${image.fileName} 不变",
                    style = MiuixTheme.textStyles.body2,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = "取消",
                    onClick = { renameTarget = null },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "保存",
                    onClick = {
                        viewModel.renameAlias(image.id, alias)
                        renameTarget = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumb(
    image: ImageInfo,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val ratio = (image.widthPx.toFloat() / image.heightPx.toFloat()).coerceIn(0.2f, 1.6f)
    val thumbShape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(thumbShape)
            // 玻璃边框替代阴影：边缘由受光描边定义，无投影
            .border(1.dp, glassStrokeBrush(), thumbShape),
    ) {
        val bmp = rememberScaledBitmap(
            path = java.io.File(
                com.hyperss.app.data.RustBridge.storageRoot(com.hyperss.app.data.RustBridge.appContext),
                image.filePath,
            ).absolutePath,
            maxDim = 512,
        )
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = image.fileName,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongPress,
                    ),
                contentScale = ContentScale.FillBounds,
            )
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = onClick, onLongClick = onLongPress),
                color = MiuixTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(image.fileName, style = MiuixTheme.textStyles.footnote2)
                }
            }
        }

        if (selected) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp),
                shape = RoundedCornerShape(11.dp),
                color = MiuixTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✓", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp),
                shape = RoundedCornerShape(11.dp),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
            ) {}
        }
    }
}

/** 全屏查看器：HorizontalPager 左右切换上一张/下一张，双指缩放，箭头按钮翻页。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullScreenViewer(
    images: List<ImageInfo>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onRename: (ImageInfo) -> Unit,
    onExport: (ImageInfo) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, images.size - 1)) {
        images.size
    }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { images[it].id },
        ) { page ->
            ZoomableImage(image = images[page])
        }

        // 当前图片操作：作用于正在查看的这一张
        val current = images[pagerState.currentPage.coerceIn(0, images.size - 1)]

        // 页码指示
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp),
            shape = RoundedCornerShape(14.dp),
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
        ) {
            Text(
                "${pagerState.currentPage + 1} / ${images.size}",
                style = MiuixTheme.textStyles.footnote1,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
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

        // 左右翻页按钮（到边界时隐藏）；两端使用同类型的箭头图标
        if (pagerState.currentPage > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(44.dp),
                shape = RoundedCornerShape(22.dp),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
            ) {
                IconButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }) {
                    Icon(MiuixIcons.ChevronBackward, contentDescription = "上一张", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        if (pagerState.currentPage < images.size - 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(44.dp),
                shape = RoundedCornerShape(22.dp),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
            ) {
                IconButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }) {
                    Icon(MiuixIcons.ChevronForward, contentDescription = "下一张", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
            ) {
                IconButton(onClick = { onRename(current) }) {
                    Icon(MiuixIcons.Edit, contentDescription = "重命名", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
            ) {
                IconButton(onClick = { onExport(current) }) {
                    Icon(MiuixIcons.Share, contentDescription = "导出", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

/** 单页可缩放图片：双指缩放/平移；单指滑动不消费，交给 Pager 翻页。 */
@Composable
private fun ZoomableImage(image: ImageInfo) {
    var scale by remember(image.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(image.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(image.id) { mutableFloatStateOf(0f) }
    val path = remember(image.id) {
        java.io.File(
            com.hyperss.app.data.RustBridge.storageRoot(com.hyperss.app.data.RustBridge.appContext),
            image.filePath,
        ).absolutePath
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(image.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            // 双指：缩放 + 平移，消费事件
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
                        // 单指：不消费，Pager 正常翻页
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val bmp = rememberScaledBitmap(path, maxDim = 4096)
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = image.fileName,
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
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(image.fileName, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
/** 批量导出弹窗：图片导出 / PDF 导出（可选每页 1~4 张、布局预览、命名）。 */
@Composable
private fun BatchExportDialog(
    imageCount: Int,
    onExportImages: () -> Unit,
    onExportPdf: (perPage: Int, name: String) -> Unit,
    onPreview: (perPage: Int, onPages: (List<android.graphics.Bitmap>?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var pdfMode by remember { mutableStateOf(false) }
    var perPage by remember { mutableStateOf(1) }
    var fileName by remember { mutableStateOf(com.hyperss.app.util.PdfUtils.defaultPdfName()) }
    var previewPages by remember { mutableStateOf<List<android.graphics.Bitmap>?>(null) }
    var previewLoading by remember { mutableStateOf(false) }

    OverlayDialog(
        show = true,
        title = "导出 $imageCount 张图片",
        summary = if (pdfMode) {
            "PDF 每页排列 $perPage 张，导出到 Download/HyperSSDL"
        } else {
            "图片将导出到系统相册 Pictures/HyperSS"
        },
        onDismissRequest = onDismiss,
    ) {
        Column {
            // 模式选择
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "图片导出",
                    onClick = onExportImages,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = if (pdfMode) "收起 PDF 选项" else "PDF 导出",
                    onClick = {
                        pdfMode = !pdfMode
                        previewPages = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (pdfMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("每页图片数（按比例缩放排列）", style = MiuixTheme.textStyles.body2)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 4).forEach { n ->
                        val active = perPage == n
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceVariant,
                            onClick = {
                                perPage = n
                                previewPages = null
                            },
                        ) {
                            Text(
                                "$n 张/页",
                                style = MiuixTheme.textStyles.body2,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = "PDF 文件名",
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = if (previewLoading) "生成预览…" else "预览布局",
                        onClick = {
                            previewLoading = true
                            onPreview(perPage) { pages ->
                                previewPages = pages
                                previewLoading = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "导出 PDF",
                        onClick = { onExportPdf(perPage, fileName.trim().ifEmpty { com.hyperss.app.util.PdfUtils.defaultPdfName() }) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }

                previewPages?.let { pages ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "共 ${pages.size} 页（A4 纵向）",
                        style = MiuixTheme.textStyles.footnote2,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(pages.size) { i ->
                            androidx.compose.foundation.Image(
                                bitmap = pages[i].asImageBitmap(),
                                contentDescription = "第 ${i + 1} 页预览",
                                modifier = Modifier
                                    .width(96.dp)
                                    .aspectRatio(PdfUtils.PAGE_W / PdfUtils.PAGE_H)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
    }
}
