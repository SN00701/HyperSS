package com.hyperss.app.ui.project

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperss.app.R
import com.hyperss.app.ui.AmbientGlassLayer
import com.hyperss.app.ui.GlassButton
import com.hyperss.app.ui.GlassChipButton
import com.hyperss.app.ui.GlassDialog
import com.hyperss.app.ui.GlassFloatingButton
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.glassStrokeBrush
import com.hyperss.app.ui.LiquidTopBar
import com.hyperss.app.ui.liquidGlassLayer
import com.hyperss.app.ui.redrawOn
import com.hyperss.app.ui.rememberBlackCanvasBackdrop
import com.hyperss.app.ui.rememberLiquidBackdrop
import com.hyperss.app.ui.rememberLiquidContentBackdrop
import com.hyperss.app.ui.rememberTopBarBlurFraction
import com.hyperss.app.ui.util.rememberScaledBitmap
import com.hyperss.app.util.PdfUtils
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import uniffi.hyperss_core.ImageInfo

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectDetailScreen(
    projectId: Long,
    onBack: () -> Unit,
    viewModel: ProjectDetailViewModel = viewModel(),
) {
    val images by viewModel.images.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val project by viewModel.project.collectAsState()
    val error by viewModel.error.collectAsState()
    val sortDesc by viewModel.sortDesc.collectAsState()
    val imagesRevision by viewModel.imagesRevision.collectAsState()
    val batchMode = selected.isNotEmpty()
    // 展示顺序统一按命名序号排序（升/降序由排序按钮切换）
    val sortedImages = remember(images, sortDesc) {
        if (sortDesc) images.sortedByDescending { it.seq } else images.sortedBy { it.seq }
    }

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var editorTarget by remember { mutableStateOf<ImageInfo?>(null) }
    var showBatchEditor by remember { mutableStateOf(false) }
    var showExportResult by remember { mutableStateOf(false) }
    var showBatchExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) { viewModel.load(projectId) }

    // 顶部液态玻璃：内容滚动时玻璃标题栏渐入
    val liquidAmbient = rememberLiquidBackdrop()
    val liquidContent = rememberLiquidContentBackdrop()
    // 瀑布流：按累计高宽比贪心分到两列，列内紧排无空隙；整页单一滚动（所有图片一起滑动）
    val masonry = remember(sortedImages) { splitMasonry(sortedImages) }
    val scrollState = rememberScrollState()
    val blurFraction = rememberTopBarBlurFraction(scrollState)
    var barHeight by remember { mutableStateOf(0.dp) }

    // 切换排序后内容重排，统一回到顶部，避免停在异常位置
    LaunchedEffect(sortDesc) {
        scrollState.scrollTo(0)
    }

    val onThumbClick: (ImageInfo) -> Unit = { image ->
        if (batchMode) {
            viewModel.toggleSelect(image.id)
        } else {
            viewerIndex = sortedImages.indexOfFirst { it.id == image.id }.takeIf { it >= 0 }
        }
    }
    val onThumbLongPress: (ImageInfo) -> Unit = { image ->
        if (!batchMode) {
            viewModel.clearSelection()
            viewModel.toggleSelect(image.id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 环境光斑玻璃层：批量编辑器等悬浮元素液态玻璃采样它
        AmbientGlassLayer(liquidAmbient)
        if (images.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = barHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.detail_no_images), style = MiuixTheme.textStyles.body1)
            }
        } else {
            // 瀑布流：两列各自从上到下紧排（保留原图宽高比，列内无空隙），
            // 整体放在单一 verticalScroll 容器里 —— 滑动时所有图片一起移动。
            // 禁用边缘 overscroll 弹动：平台弹动位移是绘制期变换（EdgeEffect/RenderNode），
            // 采样录层与玻璃坐标均不可见——弹动期间顶栏/悬浮玻璃折射会冻结在上一帧造成错位；
            // 禁用后边缘滑动无位移，玻璃折射始终与可见内容一致。
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        // redrawOn：每次 scrollState.value 变化时强制重绘，
                        // 配合各玻璃元素的 redrawKey 让消费层重新采样。
                        .redrawOn(scrollState.value)
                        .liquidGlassLayer(liquidContent)
                        .padding(start = 16.dp, end = 16.dp, top = barHeight + 16.dp, bottom = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MasonryColumn(
                            images = masonry.first,
                            selected = selected,
                            revision = imagesRevision,
                            onThumbClick = onThumbClick,
                            onThumbLongPress = onThumbLongPress,
                            modifier = Modifier.weight(1f),
                        )
                        MasonryColumn(
                            images = masonry.second,
                            selected = selected,
                            revision = imagesRevision,
                            onThumbClick = onThumbClick,
                            onThumbLongPress = onThumbLongPress,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 批量管理模式：右下角「导出」玻璃按钮（按当前勾选导出）
        if (batchMode && selected.isNotEmpty()) {
            // 「编辑」在「导出」上方：进入批量图片编辑器（打开第一张选中图，可左右滑动其余选中图）
            GlassFloatingButton(
                icon = MiuixIcons.Edit,
                contentDescription = stringResource(R.string.detail_edit_selected),
                onClick = { showBatchEditor = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 92.dp),
                size = 56.dp,
                liquidBackdrop = liquidContent,
                redrawKey = scrollState.value,
            )
            GlassFloatingButton(
                icon = MiuixIcons.Download,
                contentDescription = stringResource(R.string.detail_export_selected),
                onClick = { showBatchExport = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                size = 56.dp,
                liquidBackdrop = liquidContent,
                redrawKey = scrollState.value,
            )
        }

        // 顶部栏最后声明：绘制在网格之上，滚动图片不会盖住它，触摸也优先于网格
        // refreshKey 传滚动偏移：顶栏玻璃层随滚动实时重绘，折射内容跟随时移画面移动
        LiquidTopBar(
            backdrop = liquidContent,
            fraction = blurFraction,
            refreshKey = scrollState.value,
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
                // 返回玻璃圆钮：折射透出滚动内容
                GlassChipButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp),
                    liquidBackdrop = liquidContent,
                    redrawKey = scrollState.value,
                    shape = CircleShape,
                ) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    stringResource(R.string.detail_title),
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (batchMode) {
                    GlassChipButton(
                        onClick = { viewModel.toggleSelectAll() },
                        liquidBackdrop = liquidContent,
                        redrawKey = scrollState.value,
                    ) {
                        Text(
                            stringResource(
                                if (selected.size == images.size) R.string.detail_deselect_all
                                else R.string.detail_select_all,
                            ),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    GlassChipButton(
                        onClick = {
                            viewModel.deleteSelected()
                            viewModel.clearSelection()
                        },
                        modifier = Modifier.size(40.dp),
                        liquidBackdrop = liquidContent,
                        redrawKey = scrollState.value,
                        shape = CircleShape,
                    ) {
                        Icon(
                            MiuixIcons.Delete,
                            contentDescription = stringResource(R.string.project_delete_selected),
                            tint = MiuixTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    GlassChipButton(
                        onClick = { viewModel.clearSelection() },
                        liquidBackdrop = liquidContent,
                        redrawKey = scrollState.value,
                    ) {
                        Text(
                            stringResource(R.string.project_done),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    // 排序玻璃按钮：只显示当前排列方向（顺序 / 逆序），点击切换
                    GlassChipButton(
                        onClick = { viewModel.toggleSort() },
                        liquidBackdrop = liquidContent,
                        redrawKey = scrollState.value,
                    ) {
                        Text(
                            stringResource(
                                if (sortDesc) R.string.detail_sort_desc else R.string.detail_sort_asc,
                            ),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    GlassChipButton(
                        onClick = {
                            if (images.isNotEmpty()) viewModel.toggleSelect(images.first().id)
                        },
                        liquidBackdrop = liquidContent,
                        redrawKey = scrollState.value,
                    ) {
                        Text(
                            stringResource(R.string.project_batch),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
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
        GlassDialog(
            show = true,
            title = stringResource(R.string.detail_exporting),
            summary = stringResource(R.string.detail_exporting_hint),
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
            revision = imagesRevision,
            onDismiss = { viewerIndex = null },
            onEdit = { image -> editorTarget = image },
            onExport = { image ->
                viewModel.exportToGallery(image) { ok -> showExportResult = ok }
                viewerIndex = null
            },
        )
    }

    if (showExportResult) {
        GlassDialog(
            show = true,
            title = stringResource(R.string.export),
            summary = stringResource(R.string.detail_export_done),
            onDismissRequest = { showExportResult = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = stringResource(R.string.ok), onClick = { showExportResult = false })
            }
        }
    }

    // 图片编辑器（水印 / 马赛克 / 重命名）
    editorTarget?.let { image ->
        ImageEditorScreen(
            image = image,
            viewModel = viewModel,
            onDismiss = { editorTarget = null },
        )
    }

    // 批量图片编辑器（选中图片左右滑动；水印共享、马赛克按图涂抹、重命名改项目名）
    if (showBatchEditor) {
        val selectedImages = sortedImages.filter { it.id in selected }
        if (selectedImages.isEmpty()) {
            // 勾选被清空时自动退出，避免停在无内容的编辑器上
            LaunchedEffect(Unit) { showBatchEditor = false }
        } else {
            BatchImageEditorScreen(
                images = selectedImages,
                projectName = project?.displayName ?: "",
                viewModel = viewModel,
                onDismiss = { showBatchEditor = false },
            )
        }
    }
}

/** 瀑布流分列：按累计高宽比贪心分配到较矮的一列，两列总高尽量均衡。 */
private fun splitMasonry(images: List<ImageInfo>): Pair<List<ImageInfo>, List<ImageInfo>> {
    val left = mutableListOf<ImageInfo>()
    val right = mutableListOf<ImageInfo>()
    var leftH = 0f
    var rightH = 0f
    for (img in images) {
        // 列宽相同 → 相对高度 = 高/宽（与 ImageThumb 的 aspectRatio 裁剪一致）
        val relH = if (img.widthPx > 0) {
            (img.heightPx.toFloat() / img.widthPx.toFloat()).coerceIn(0.625f, 5f)
        } else {
            1f
        }
        if (leftH <= rightH) {
            left.add(img)
            leftH += relH
        } else {
            right.add(img)
            rightH += relH
        }
    }
    return left to right
}

/** 瀑布流单列：普通 Column（非懒加载），自然宽高比堆叠，列内 12dp 间距、无空隙。 */
@Composable
private fun MasonryColumn(
    images: List<ImageInfo>,
    selected: Set<Long>,
    revision: Int,
    onThumbClick: (ImageInfo) -> Unit,
    onThumbLongPress: (ImageInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        images.forEach { image ->
            ImageThumb(
                image = image,
                revision = revision,
                selected = image.id in selected,
                onClick = { onThumbClick(image) },
                onLongPress = { onThumbLongPress(image) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumb(
    image: ImageInfo,
    revision: Int,
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
            version = revision,
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
    revision: Int,
    onDismiss: () -> Unit,
    onEdit: (ImageInfo) -> Unit,
    onExport: (ImageInfo) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, images.size - 1)) {
        images.size
    }
    val scope = rememberCoroutineScope()
    // 液态玻璃采样层：记录「黑底 + 当前页图片」，悬浮玻璃元素折射透出图片画面
    val canvasLayer = rememberBlackCanvasBackdrop()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassLayer(canvasLayer),
            key = { images[it].id },
        ) { page ->
            ZoomableImage(image = images[page], revision = revision)
        }

        // 当前图片操作：作用于正在查看的这一张
        val current = images[pagerState.currentPage.coerceIn(0, images.size - 1)]
        // 悬浮玻璃元素的下垫色：压暗保证白字/白图标可读，同时保留折射透出
        val chipTint = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)

        // 页码指示
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
                "${pagerState.currentPage + 1} / ${images.size}",
                style = MiuixTheme.textStyles.footnote1,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
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

        // 左右翻页按钮（到边界时隐藏）；两端使用同类型的箭头图标
        if (pagerState.currentPage > 0) {
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(44.dp),
                cornerRadius = 22.dp,
                background = chipTint,
                liquidBackdrop = canvasLayer,
                redrawKey = pagerState.currentPage,
            ) {
                IconButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }) {
                    Icon(MiuixIcons.ChevronBackward, contentDescription = stringResource(R.string.viewer_prev), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        if (pagerState.currentPage < images.size - 1) {
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(44.dp),
                cornerRadius = 22.dp,
                background = chipTint,
                liquidBackdrop = canvasLayer,
                redrawKey = pagerState.currentPage,
            ) {
                IconButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }) {
                    Icon(MiuixIcons.ChevronForward, contentDescription = stringResource(R.string.viewer_next), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassSurface(
                cornerRadius = 20.dp,
                background = chipTint,
                liquidBackdrop = canvasLayer,
                redrawKey = pagerState.currentPage,
            ) {
                IconButton(onClick = { onEdit(current) }) {
                    Icon(MiuixIcons.Edit, contentDescription = stringResource(R.string.edit), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            GlassSurface(
                cornerRadius = 20.dp,
                background = chipTint,
                liquidBackdrop = canvasLayer,
                redrawKey = pagerState.currentPage,
            ) {
                IconButton(onClick = { onExport(current) }) {
                    Icon(MiuixIcons.Share, contentDescription = stringResource(R.string.export), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

/** 单页可缩放图片：双指缩放/平移；单指滑动不消费，交给 Pager 翻页。 */
@Composable
private fun ZoomableImage(image: ImageInfo, revision: Int) {
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
        val bmp = rememberScaledBitmap(path, maxDim = 4096, version = revision)
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

    GlassDialog(
        show = true,
        title = stringResource(R.string.detail_batch_export_title, imageCount),
        summary = if (pdfMode) {
            stringResource(R.string.detail_batch_desc_pdf, perPage)
        } else {
            stringResource(R.string.detail_batch_desc_images)
        },
        onDismissRequest = onDismiss,
    ) {
        Column {
            // 模式选择
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.detail_export_images_btn),
                    onClick = onExportImages,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(if (pdfMode) R.string.detail_pdf_collapse else R.string.detail_export_pdf_btn),
                    onClick = {
                        pdfMode = !pdfMode
                        previewPages = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (pdfMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.detail_per_page_label), style = MiuixTheme.textStyles.body2)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 4).forEach { n ->
                        val active = perPage == n
                        GlassButton(
                            onClick = {
                                perPage = n
                                previewPages = null
                            },
                            modifier = Modifier.weight(1f),
                            cornerRadius = 12.dp,
                            background = if (active) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                stringResource(R.string.detail_per_page, n),
                                style = MiuixTheme.textStyles.body2,
                                color = if (active) androidx.compose.ui.graphics.Color.White
                                else MiuixTheme.colorScheme.onSurface,
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
                    label = stringResource(R.string.detail_pdf_name_label),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(
                            if (previewLoading) R.string.detail_preview_generating else R.string.detail_preview_btn,
                        ),
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
                        text = stringResource(R.string.detail_export_pdf_btn),
                        onClick = { onExportPdf(perPage, fileName.trim().ifEmpty { com.hyperss.app.util.PdfUtils.defaultPdfName() }) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }

                previewPages?.let { pages ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.detail_pdf_pages, pages.size),
                        style = MiuixTheme.textStyles.footnote2,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(pages.size) { i ->
                            androidx.compose.foundation.Image(
                                bitmap = pages[i].asImageBitmap(),
                                contentDescription = stringResource(R.string.detail_page_preview, i + 1),
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
