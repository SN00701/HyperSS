package com.hyperss.app.ui.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hyperss.app.R
import com.hyperss.app.ui.GlassDialog
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.liquidGlassLayer
import com.hyperss.app.ui.rememberBlackCanvasBackdrop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.hyperss_core.ImageInfo
import java.io.ByteArrayOutputStream

/**
 * 批量图片编辑器（批量管理模式下从右下角「编辑」按钮进入）：水印 + 马赛克 + 重命名。
 * 全屏黑底覆盖层，与 [ImageEditorScreen] 功能对齐，差异在于：
 * - 水印：设置对所有选中图片共享，左右滑动逐张实时预览；
 * - 马赛克：沿用图片编辑器的涂抹逻辑，但笔画归属于「当前图片」——
 *   左右滑动切换图片后在不同位置各自涂抹，不会对所有图片统一打码；
 * - 重命名：改的是项目名称（不是图片名称），面板内有明确说明。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BatchImageEditorScreen(
    images: List<ImageInfo>,
    projectName: String,
    viewModel: ProjectDetailViewModel,
    onDismiss: () -> Unit,
) {
    require(images.isNotEmpty())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dpi = context.resources.displayMetrics.densityDpi

    val pagerState = rememberPagerState(initialPage = 0) { images.size }
    val current = images[pagerState.currentPage.coerceIn(0, images.size - 1)]
    val currentId = current.id

    // —— 共享编辑状态：水印全局共享；马赛克按图片分别存放 ——
    val defaultWatermark = stringResource(R.string.editor_watermark_default)
    var waterText by remember { mutableStateOf(defaultWatermark) }
    var watermarkEnabled by remember { mutableStateOf(false) } // 水印开关，默认关
    var brushRadius by remember { mutableFloatStateOf(120f) }
    var activeStrokeIndex by remember { mutableStateOf(-1) } // 滑杆实时调大小的目标（当前图片最近一笔）
    var tool by remember { mutableStateOf(Tool.Watermark) }
    var strokeStore by remember { mutableStateOf(mutableMapOf<Long, List<MosaicStroke>>()) }
    var projectNameText by remember { mutableStateOf(projectName) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }

    val currentStrokes = strokeStore[currentId] ?: emptyList()

    // 切换图片后重置「待滑杆调大小的最近一笔」（笔画归属各自图片）
    LaunchedEffect(currentId) { activeStrokeIndex = -1 }

    fun putStrokes(id: Long, value: List<MosaicStroke>) {
        strokeStore = strokeStore.toMutableMap().apply { put(id, value) }
    }

    fun saveAll() {
        saving = true
        scope.launch {
            withContext(Dispatchers.IO) {
                val hasWatermark = watermarkEnabled && waterText.isNotBlank()
                for (image in images) {
                    val s = strokeStore[image.id] ?: emptyList()
                    if (!hasWatermark && s.isEmpty()) continue
                    val done = CompletableDeferred<Boolean>()
                    runCatching {
                        val base = decodeBase(image, viewModel) ?: return@runCatching
                        val edited = composeEdited(base, s, emptyList(), brushRadius, waterText, watermarkEnabled, dpi)
                        val out = ByteArrayOutputStream()
                        edited.compress(Bitmap.CompressFormat.PNG, 100, out)
                        edited.recycle()
                        viewModel.saveImagePng(image, out.toByteArray()) { ok -> done.complete(ok) }
                    }
                    done.await()
                }
            }
            saving = false
            showSaved = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 液态玻璃采样层：记录「黑底 + 当前页合成预览」，悬浮玻璃元素折射透出图片画面
        val canvasLayer = rememberBlackCanvasBackdrop()
        val chipTint = Color.Black.copy(alpha = 0.55f)

        // 左右滑动翻页（选中图片按展示顺序）；每页独立预览该图片的合成结果
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassLayer(canvasLayer),
            key = { images[it].id },
        ) { i ->
            val pageImage = images[i]
            BatchEditorPage(
                image = pageImage,
                strokes = strokeStore[pageImage.id] ?: emptyList(),
                tool = tool,
                brushRadius = brushRadius,
                waterText = waterText,
                watermarkOn = watermarkEnabled,
                dpi = dpi,
                viewModel = viewModel,
                onCommitStroke = { stroke ->
                    val list = (strokeStore[pageImage.id] ?: emptyList()) + stroke
                    putStrokes(pageImage.id, list)
                    activeStrokeIndex = list.lastIndex
                },
            )
        }

        // 页码指示
        GlassSurface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 116.dp),
            cornerRadius = 14.dp,
            background = chipTint,
            liquidBackdrop = canvasLayer,
            redrawKey = pagerState.currentPage,
        ) {
            Text(
                "${pagerState.currentPage + 1} / ${images.size}",
                style = MiuixTheme.textStyles.footnote1,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // 左右翻页按钮（到边界时隐藏）
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
                    Icon(MiuixIcons.ChevronBackward, contentDescription = stringResource(R.string.viewer_prev), tint = Color.White)
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
                    Icon(MiuixIcons.ChevronForward, contentDescription = stringResource(R.string.viewer_next), tint = Color.White)
                }
            }
        }

        // 顶部栏：返回（放弃）/ 标题+已选张数 / 保存
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassSurface(
                modifier = Modifier.size(40.dp),
                cornerRadius = 20.dp,
                background = chipTint,
                liquidBackdrop = canvasLayer,
                redrawKey = pagerState.currentPage,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.batch_editor_title),
                    style = MiuixTheme.textStyles.title2,
                    color = Color.White,
                )
                Text(
                    stringResource(R.string.batch_editor_scope, images.size),
                    style = MiuixTheme.textStyles.footnote2,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            TextButton(
                text = stringResource(R.string.save),
                onClick = { showSave = true },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }

        // 底部工具面板 + 工具条
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            when (tool) {
                Tool.Watermark -> GlassSurface(
                    cornerRadius = 14.dp,
                    background = chipTint,
                    liquidBackdrop = canvasLayer,
                    redrawKey = pagerState.currentPage,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = waterText,
                                onValueChange = { waterText = it.take(14) },
                                label = stringResource(R.string.editor_watermark_label),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "${waterText.length}/14",
                                style = MiuixTheme.textStyles.footnote2,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        Text(
                            stringResource(R.string.editor_watermark_hint),
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            stringResource(R.string.batch_editor_watermark_scope),
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        // 右下角：水印开关（默认关闭）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.editor_watermark_switch),
                                style = MiuixTheme.textStyles.footnote2,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Switch(
                                checked = watermarkEnabled,
                                onCheckedChange = { watermarkEnabled = it },
                            )
                        }
                    }
                }

                Tool.Mosaic -> GlassSurface(
                    cornerRadius = 14.dp,
                    background = chipTint,
                    liquidBackdrop = canvasLayer,
                    redrawKey = pagerState.currentPage,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.editor_mosaic_brush),
                                style = MiuixTheme.textStyles.footnote2,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                // 撤销当前图片的上一笔
                                if (currentStrokes.isNotEmpty()) putStrokes(currentId, currentStrokes.dropLast(1))
                                activeStrokeIndex = -1
                            }) {
                                Icon(MiuixIcons.Undo, contentDescription = stringResource(R.string.editor_mosaic_undo), tint = Color.White)
                            }
                            IconButton(onClick = {
                                putStrokes(currentId, emptyList())
                                activeStrokeIndex = -1
                            }) {
                                Icon(MiuixIcons.Clear, contentDescription = stringResource(R.string.editor_mosaic_clear), tint = Color.White)
                            }
                        }
                        // 滑杆：有最近一笔时实时调该笔圆的大小（仅当前图片）；否则设定下一次笔刷
                        Slider(
                            value = brushRadius,
                            onValueChange = { v ->
                                brushRadius = v
                                if (activeStrokeIndex in currentStrokes.indices) {
                                    putStrokes(
                                        currentId,
                                        currentStrokes.mapIndexed { i, s ->
                                            if (i == activeStrokeIndex) s.copy(radius = v) else s
                                        },
                                    )
                                } else {
                                    activeStrokeIndex = -1
                                }
                            },
                            valueRange = 40f..400f,
                        )
                        Text(
                            stringResource(R.string.batch_editor_mosaic_scope),
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }

                Tool.Rename -> GlassSurface(
                    cornerRadius = 14.dp,
                    background = chipTint,
                    liquidBackdrop = canvasLayer,
                    redrawKey = pagerState.currentPage,
                ) {
                    val nameEmptyMsg = stringResource(R.string.project_name_empty)
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 与单图编辑不同：此处改的是项目名称，需向用户说明
                        Text(
                            stringResource(R.string.batch_editor_rename_hint),
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White.copy(alpha = 0.65f),
                        )
                        TextField(
                            value = projectNameText,
                            onValueChange = {
                                projectNameText = it
                                renameError = null
                            },
                            label = stringResource(R.string.new_project_name_label),
                            singleLine = true,
                        )
                        renameError?.let {
                            Text(
                                it,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                text = stringResource(R.string.cancel),
                                onClick = {
                                    renameError = null
                                    tool = Tool.Watermark
                                },
                            )
                            Spacer(Modifier.size(12.dp))
                            TextButton(
                                text = stringResource(R.string.save),
                                onClick = {
                                    val name = projectNameText.trim()
                                    if (name.isEmpty()) {
                                        renameError = nameEmptyMsg
                                    } else {
                                        renameError = null
                                        viewModel.renameProject(name)
                                        tool = Tool.Watermark
                                    }
                                },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                EditorToolButton(
                    icon = MiuixIcons.Notes,
                    label = stringResource(R.string.editor_tool_watermark),
                    active = tool == Tool.Watermark,
                    onClick = { tool = Tool.Watermark },
                    liquidBackdrop = canvasLayer,
                    redrawKey = pagerState.currentPage,
                )
                Spacer(Modifier.size(10.dp))
                EditorToolButton(
                    icon = MiuixIcons.GridView,
                    label = stringResource(R.string.editor_tool_mosaic),
                    active = tool == Tool.Mosaic,
                    onClick = { tool = Tool.Mosaic },
                    liquidBackdrop = canvasLayer,
                    redrawKey = pagerState.currentPage,
                )
                Spacer(Modifier.size(10.dp))
                EditorToolButton(
                    icon = MiuixIcons.Rename,
                    label = stringResource(R.string.rename),
                    active = tool == Tool.Rename,
                    onClick = { tool = Tool.Rename },
                    liquidBackdrop = canvasLayer,
                    redrawKey = pagerState.currentPage,
                )
            }
        }

        // 保存确认（覆盖全部选中图片）
        if (showSave) {
            GlassDialog(
                show = true,
                title = stringResource(R.string.batch_editor_save_confirm_title),
                summary = stringResource(R.string.batch_editor_save_confirm_message, images.size),
                onDismissRequest = { showSave = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showSave = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(20.dp))
                    TextButton(
                        text = stringResource(R.string.save),
                        onClick = {
                            showSave = false
                            saveAll()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }

        // 保存进行中
        if (saving) {
            GlassDialog(
                show = true,
                title = stringResource(R.string.batch_editor_saving),
                summary = stringResource(R.string.batch_editor_saving_hint, images.size),
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

        // 保存完成
        if (showSaved) {
            GlassDialog(
                show = true,
                title = stringResource(R.string.batch_editor_saved),
                summary = stringResource(R.string.editor_save_confirm_message),
                onDismissRequest = { showSaved = false; onDismiss() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = stringResource(R.string.ok),
                        onClick = {
                            showSaved = false
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** 单页编辑画布：解码 + 实时合成预览（水印共享、马赛克归本图）+ 手势（双指缩放 / 单指涂抹 / 边缘翻页）。 */
@Composable
private fun BatchEditorPage(
    image: ImageInfo,
    strokes: List<MosaicStroke>,
    tool: Tool,
    brushRadius: Float,
    waterText: String,
    watermarkOn: Boolean,
    dpi: Int,
    viewModel: ProjectDetailViewModel,
    onCommitStroke: (MosaicStroke) -> Unit,
) {
    // 原图解码（4096 上限，长图 OOM 保护）
    var baseBitmap by remember(image.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(image.id) {
        baseBitmap = decodeBase(image, viewModel)
    }

    // 涂抹进行中笔尖（仅本页）
    var pendingPoints by remember(image.id) { mutableStateOf<List<Offset>>(emptyList()) }
    var drawing by remember(image.id) { mutableStateOf(false) }

    // 手势协程可能长期持有旧闭包（pointerInput 键不变时不重挂），
    // 因此用委托状态镜像最新参数值（与单图编辑器的写法一致）
    var activeTool by remember(image.id) { mutableStateOf(tool) }
    LaunchedEffect(tool) { activeTool = tool }
    var brush by remember(image.id) { mutableFloatStateOf(brushRadius) }
    LaunchedEffect(brushRadius) { brush = brushRadius }

    // 预览缩放 / 平移（仅本页）
    var scale by remember(image.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(image.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(image.id) { mutableFloatStateOf(0f) }
    var boxSize by remember(image.id) { mutableStateOf(IntSize.Zero) }

    // 实时合成预览（60ms 防抖，IO 线程）
    val editsKey = EditsKey(strokes, pendingPoints, brushRadius, waterText, watermarkOn)
    val composed by produceState<ImageBitmap?>(null, key1 = baseBitmap, key2 = editsKey) {
        val base = baseBitmap ?: return@produceState
        delay(60)
        value = withContext(Dispatchers.IO) {
            runCatching {
                composeEdited(base, strokes, pendingPoints, brushRadius, waterText, watermarkOn, dpi).asImageBitmap()
            }.getOrNull()
        }
    }

    // 边缘滑动区（未缩放时单指从此处拖动 = 翻页，其余区域 = 涂抹）
    val edgeZonePx = with(LocalDensity.current) { 36.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { boxSize = it.size }
            .pointerInput(image.id) {
                awaitEachGesture {
                    var swiping = false
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
                            continue
                        }
                        val ch = event.changes.firstOrNull() ?: continue
                        when {
                            activeTool == Tool.Mosaic -> when {
                                ch.pressed && !drawing && scale > 1.01f -> {
                                    // 已放大：单指平移（消费，避免 Pager 抢走手势）
                                    offsetX += ch.position.x - ch.previousPosition.x
                                    offsetY += ch.position.y - ch.previousPosition.y
                                    ch.consume()
                                }
                                ch.pressed && !drawing && !swiping -> {
                                    val atEdge = boxSize.width > 0f &&
                                        (ch.position.x < edgeZonePx || ch.position.x > boxSize.width - edgeZonePx)
                                    if (atEdge) {
                                        // 边缘滑动区：不消费，交给 Pager 翻页
                                        swiping = true
                                    } else {
                                        // 涂抹（点按 / 拖动）
                                        val imgPt = screenToImage(ch.position, baseBitmap, boxSize, scale, offsetX, offsetY)
                                        drawing = true
                                        pendingPoints = listOf(imgPt)
                                        ch.consume()
                                    }
                                }
                                ch.pressed && drawing -> {
                                    // 沿轨迹采样：与上一笔尖距离 ≥ 半径 80% 时补点（写字式涂抹）
                                    val imgPt = screenToImage(ch.position, baseBitmap, boxSize, scale, offsetX, offsetY)
                                    val last = pendingPoints.lastOrNull()
                                    if (last == null) {
                                        pendingPoints = listOf(imgPt)
                                    } else {
                                        val step = brush * 0.8f
                                        val dx = imgPt.x - last.x
                                        val dy = imgPt.y - last.y
                                        if (dx * dx + dy * dy >= step * step) {
                                            pendingPoints = pendingPoints + imgPt
                                        }
                                    }
                                    ch.consume()
                                }
                                ch.pressed && swiping -> {
                                    // 翻页滑动中：保持不消费，交给 Pager
                                }
                                drawing -> {
                                    // 抬手：提交本笔（只属于当前图片）
                                    if (pendingPoints.isNotEmpty()) {
                                        onCommitStroke(MosaicStroke(pendingPoints, brush))
                                    }
                                    pendingPoints = emptyList()
                                    drawing = false
                                    ch.consume()
                                }
                            }
                            else -> {
                                // 非马赛克工具：单指 = 翻页（不消费）；已放大时单指平移
                                if (scale > 1.01f && ch.pressed) {
                                    offsetX += ch.position.x - ch.previousPosition.x
                                    offsetY += ch.position.y - ch.previousPosition.y
                                    ch.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        composed?.let { bmp ->
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
        } ?: Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.pdf_opening), color = Color.White)
        }
    }
}

/** 原图解码（4096 上限，长图 OOM 保护），IO 线程。 */
private suspend fun decodeBase(image: ImageInfo, viewModel: ProjectDetailViewModel): Bitmap? =
    withContext(Dispatchers.IO) {
        val file = viewModel.imageFile(image)
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.let { bmp ->
            val s = minOf(1f, 4096f / maxOf(bmp.width, bmp.height))
            if (s < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * s).toInt(),
                    (bmp.height * s).toInt(),
                    true,
                ).also { bmp.recycle() }
            } else bmp
        }
    }
