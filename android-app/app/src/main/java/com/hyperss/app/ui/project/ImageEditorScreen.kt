package com.hyperss.app.ui.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hyperss.app.R
import com.hyperss.app.ui.GlassDialog
import com.hyperss.app.ui.GlassIconButton
import com.hyperss.app.ui.GlassSurface
import com.hyperss.app.ui.liquidGlassLayer
import com.hyperss.app.ui.rememberBlackCanvasBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.hyperss_core.ImageInfo
import java.io.ByteArrayOutputStream

/** 马赛克笔画：一次点按/拖动手势落下的一串圆形笔尖（坐标位于图片像素空间）。 */
internal data class MosaicStroke(val points: List<Offset>, val radius: Float)

/** 编辑器工具。 */
internal enum class Tool { Watermark, Mosaic, Rename }

/** 预览合成的入参（内容相等即不重算）。 */
internal data class EditsKey(
    val strokes: List<MosaicStroke>,
    val pending: List<Offset>,
    val pendingRadius: Float,
    val waterText: String,
    val watermarkOn: Boolean,
)

/** 马赛克像素化粒度（图片像素）：整图降采样再放大（最近邻），圆内取该层像素。 */
private const val MOSAIC_BLOCK = 20

/** 水印字号：4pt。 */
private const val WATERMARK_SIZE_PT = 4f

/** 水印倾斜角度（45°，右边在上、左边在下，即「/」方向）。 */
private const val WATERMARK_DEG = 45f

/** 水印颜色相对纯黑「浅 30%」后的不透明度（0..1）。 */
private const val WATERMARK_ALPHA = 0.7f

/** 平铺时单枚水印允许占用的最大图片宽度比例（超过则自动缩小字号）。 */
private const val WATERMARK_MAX_WIDTH_RATIO = 0.45f

/**
 * 图片编辑器：水印 + 马赛克 + 重命名。全屏黑底浮在查看器之上。
 * 保存时把编辑结果 PNG 原地写回原图文件（等比解码上限 4096，宽高比不变）。
 */
@Composable
fun ImageEditorScreen(
    image: ImageInfo,
    viewModel: ProjectDetailViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dpi = context.resources.displayMetrics.densityDpi

    // 原图解码（4096 上限，长图 OOM 保护）
    var baseBitmap by remember(image.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(image.id) {
        baseBitmap = withContext(Dispatchers.IO) {
            val file = viewModel.imageFile(image)
            BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                val scale = minOf(1f, 4096f / maxOf(bmp.width, bmp.height))
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt(),
                        (bmp.height * scale).toInt(),
                        true,
                    ).also { bmp.recycle() }
                } else bmp
            }
        }
    }

    // 编辑状态（坐标位于 baseBitmap 像素空间）
    val defaultWatermark = stringResource(R.string.editor_watermark_default)
    var waterText by remember(image.id) { mutableStateOf(defaultWatermark) }
    var watermarkEnabled by remember(image.id) { mutableStateOf(false) } // 水印开关，默认关
    var strokes by remember(image.id) { mutableStateOf<List<MosaicStroke>>(emptyList()) }
    var activeStroke by remember(image.id) { mutableStateOf(-1) } // 滑杆实时调大小的目标（最近一笔）
    var brushRadius by remember(image.id) { mutableFloatStateOf(120f) }
    var pendingPoints by remember(image.id) { mutableStateOf<List<Offset>>(emptyList()) }
    var tool by remember(image.id) { mutableStateOf(Tool.Watermark) }
    var alias by remember(image.id) { mutableStateOf(image.alias ?: "") }

    // 预览缩放 / 平移
    var scale by remember(image.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(image.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(image.id) { mutableFloatStateOf(0f) }
    var boxSize by remember(image.id) { mutableStateOf(IntSize.Zero) }
    var drawing by remember(image.id) { mutableStateOf(false) }

    // 实时合成预览（60ms 防抖，IO 线程）
    val editsKey = EditsKey(strokes, pendingPoints, brushRadius, waterText, watermarkEnabled)
    val composed by produceState<ImageBitmap?>(initialValue = null, key1 = baseBitmap, key2 = editsKey) {
        val base = baseBitmap ?: return@produceState
        delay(60)
        value = withContext(Dispatchers.IO) {
            runCatching {
                composeEdited(base, strokes, pendingPoints, brushRadius, waterText, watermarkEnabled, dpi).asImageBitmap()
            }.getOrNull()
        }
    }

    // 液态玻璃采样层：记录「黑底 + 画布预览」，悬浮玻璃元素折射透出图片画面
    val canvasLayer = rememberBlackCanvasBackdrop()
    val chipTint = Color.Black.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 预览画布：双指缩放/平移；马赛克工具下单指点按/拖动涂抹
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassLayer(canvasLayer)
                .onGloballyPositioned { boxSize = it.size }
                .pointerInput(image.id, tool) {
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
                                continue
                            }
                            if (tool != Tool.Mosaic) continue
                            val ch = event.changes.firstOrNull() ?: continue
                            when {
                                ch.pressed -> {
                                    val imgPt = screenToImage(ch.position, baseBitmap, boxSize, scale, offsetX, offsetY)
                                    if (!drawing) {
                                        drawing = true
                                        activeStroke = -1
                                        pendingPoints = listOf(imgPt)
                                    } else {
                                        // 沿轨迹采样：与上一笔尖距离 ≥ 半径 80% 时补点（写字式涂抹）
                                        val last = pendingPoints.last()
                                        val step = brushRadius * 0.8f
                                        val dx = imgPt.x - last.x
                                        val dy = imgPt.y - last.y
                                        if (dx * dx + dy * dy >= step * step) {
                                            pendingPoints = pendingPoints + imgPt
                                        }
                                    }
                                    ch.consume()
                                }
                                drawing -> {
                                    // 抬手：提交本笔（一笔 = 一次手势的所有笔尖）
                                    if (pendingPoints.isNotEmpty()) {
                                        strokes = strokes + MosaicStroke(pendingPoints, brushRadius)
                                        activeStroke = strokes.lastIndex
                                    }
                                    pendingPoints = emptyList()
                                    drawing = false
                                    ch.consume()
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

        // 顶部栏：返回（放弃）/ 标题 / 保存
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
                redrawKey = composed,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.editor_title),
                style = MiuixTheme.textStyles.title2,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            var showSave by remember { mutableStateOf(false) }
            TextButton(
                text = stringResource(R.string.save),
                onClick = { showSave = true },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            if (showSave) {
                GlassDialog(
                    show = true,
                    title = stringResource(R.string.editor_save_confirm_title),
                    summary = stringResource(R.string.editor_save_confirm_message),
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
                                val base = baseBitmap
                                if (base != null) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                val edited =
                                                    composeEdited(base, strokes, emptyList(), brushRadius, waterText, watermarkEnabled, dpi)
                                                val out = ByteArrayOutputStream()
                                                edited.compress(Bitmap.CompressFormat.PNG, 100, out)
                                                edited.recycle()
                                                viewModel.saveImagePng(image, out.toByteArray())
                                            }
                                        }
                                        onDismiss()
                                    }
                                } else {
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
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
                    redrawKey = composed,
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
                    redrawKey = composed,
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
                                // 撤销上一笔
                                if (strokes.isNotEmpty()) {
                                    strokes = strokes.dropLast(1)
                                    activeStroke = -1
                                }
                            }) {
                                Icon(MiuixIcons.Undo, contentDescription = stringResource(R.string.editor_mosaic_undo), tint = Color.White)
                            }
                            IconButton(onClick = {
                                strokes = emptyList()
                                activeStroke = -1
                                pendingPoints = emptyList()
                            }) {
                                Icon(MiuixIcons.Clear, contentDescription = stringResource(R.string.editor_mosaic_clear), tint = Color.White)
                            }
                        }
                        // 滑杆：有最近一笔时实时调该笔圆的大小；否则设定下一次笔刷
                        Slider(
                            value = brushRadius,
                            onValueChange = { v ->
                                brushRadius = v
                                if (activeStroke in strokes.indices) {
                                    strokes = strokes.mapIndexed { i, s ->
                                        if (i == activeStroke) s.copy(radius = v) else s
                                    }
                                } else {
                                    activeStroke = -1
                                }
                            },
                            valueRange = 40f..400f,
                        )
                        Text(
                            stringResource(R.string.editor_mosaic_hint),
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }

                Tool.Rename -> GlassSurface(
                    cornerRadius = 14.dp,
                    background = chipTint,
                    liquidBackdrop = canvasLayer,
                    redrawKey = composed,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            value = alias,
                            onValueChange = { alias = it },
                            label = stringResource(R.string.project_alias_hint),
                            singleLine = true,
                        )
                        Text(
                            stringResource(R.string.detail_filename_unchanged, image.fileName),
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(text = stringResource(R.string.cancel), onClick = { tool = Tool.Watermark })
                            Spacer(Modifier.size(12.dp))
                            TextButton(
                                text = stringResource(R.string.save),
                                onClick = {
                                    viewModel.renameAlias(image.id, alias.trim().ifEmpty { null })
                                    tool = Tool.Watermark
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
                    redrawKey = composed,
                )
                Spacer(Modifier.size(10.dp))
                EditorToolButton(
                    icon = MiuixIcons.GridView,
                    label = stringResource(R.string.editor_tool_mosaic),
                    active = tool == Tool.Mosaic,
                    onClick = { tool = Tool.Mosaic },
                    liquidBackdrop = canvasLayer,
                    redrawKey = composed,
                )
                Spacer(Modifier.size(10.dp))
                EditorToolButton(
                    icon = MiuixIcons.Rename,
                    label = stringResource(R.string.rename),
                    active = tool == Tool.Rename,
                    onClick = { tool = Tool.Rename },
                    liquidBackdrop = canvasLayer,
                    redrawKey = composed,
                )
            }
        }
    }
}

@Composable
internal fun EditorToolButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    liquidBackdrop: com.kyant.backdrop.Backdrop? = null,
    redrawKey: Any? = null,
) {
    GlassIconButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        size = 52.dp,
        active = active,
        liquidBackdrop = liquidBackdrop,
        redrawKey = redrawKey,
    )
}

/** 画布屏幕坐标 → 图片像素坐标（Fit 居中 + graphicsLayer 缩放/平移的逆变换）。 */
internal fun screenToImage(
    screen: Offset,
    base: Bitmap?,
    box: IntSize,
    scale: Float,
    offX: Float,
    offY: Float,
): Offset {
    if (base == null || box.width == 0 || box.height == 0 || scale <= 0f) return Offset.Zero
    val vw = box.width.toFloat()
    val vh = box.height.toFloat()
    val iw = base.width.toFloat()
    val ih = base.height.toFloat()
    val fit = minOf(vw / iw, vh / ih)
    if (fit <= 0f) return Offset.Zero
    val baseOX = (vw - iw * fit) / 2f
    val baseOY = (vh - ih * fit) / 2f
    val cx = vw / 2f
    val cy = vh / 2f
    // screen = center + (p - center) * scale + offset  →  p = ((screen - offset) - center) / scale + center
    val px = ((screen.x - cx - offX) / scale) + cx
    val py = ((screen.y - cy - offY) / scale) + cy
    return Offset((px - baseOX) / fit, (py - baseOY) / fit)
}

/**
 * 合成编辑结果：原图 → 马赛克层（圆内像素化；进行中笔画 50% 预览）→ 水印（中心旋转 40°）。
 * 仅在 IO 线程调用。
 */
internal fun composeEdited(
    base: Bitmap,
    strokes: List<MosaicStroke>,
    pending: List<Offset>,
    pendingRadius: Float,
    text: String,
    watermarkOn: Boolean,
    dpi: Int,
): Bitmap {
    val dst = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(dst)

    val hasMosaic = strokes.isNotEmpty() || pending.isNotEmpty()
    if (hasMosaic) {
        // 像素化层：整图降采样 block 倍（最近邻放大还原），一次生成、按圆裁剪
        val block = MOSAIC_BLOCK
        val sw = maxOf(1, base.width / block)
        val sh = maxOf(1, base.height / block)
        val small = Bitmap.createScaledBitmap(base, sw, sh, false)
        val layer = Bitmap.createScaledBitmap(small, base.width, base.height, false)
        small.recycle()

        val commitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
        fun clipCircles(points: List<Offset>, radius: Float) {
            if (points.isEmpty()) return
            val path = Path()
            for (p in points) path.addCircle(p.x, p.y, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(layer, 0f, 0f, commitPaint)
            canvas.restore()
        }

        for (s in strokes) clipCircles(s.points, s.radius)
        // 进行中笔画：半透明预览
        if (pending.isNotEmpty()) {
            val previewPaint = Paint(commitPaint).apply { color = AColor.argb(0x80, 0xFF, 0xFF, 0xFF) }
            val path = Path()
            for (p in pending) path.addCircle(p.x, p.y, pendingRadius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(layer, 0f, 0f, previewPaint)
            canvas.restore()
        }
        layer.recycle()
    }

    // 水印：从左上到右下平铺多枚，倾斜 45°（右边在上、左边在下，「/」方向），
    // 颜色比纯黑浅 30%（70% 不透明）；单枚宽度超出允许比例时自动缩小字号直到完整可见
    if (watermarkOn && text.isNotBlank()) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var fontSize = WATERMARK_SIZE_PT * dpi / 72f
        paint.textSize = fontSize
        val maxWidth = dst.width * WATERMARK_MAX_WIDTH_RATIO
        // 自动缩字：单枚宽度收进允许范围，保证文字完整显示
        var tw = paint.measureText(text)
        while (tw > maxWidth && fontSize > 6f) {
            fontSize *= 0.9f
            paint.textSize = fontSize
            tw = paint.measureText(text)
        }
        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        paint.color = AColor.argb((255 * WATERMARK_ALPHA).toInt(), 0, 0, 0)
        // 平铺间距：水平按文字宽、垂直按文字高，交错半格让排列更均匀
        val stepX = tw * 1.6f + 40f
        val stepY = textHeight * 6f + 120f
        var rowIndex = 0
        var fy = stepY * 0.5f
        while (fy < dst.height + stepY) {
            val stagger = if (rowIndex % 2 == 0) 0f else stepX / 2f
            var fx = stagger - stepX * 0.5f
            while (fx < dst.width + stepX) {
                val cx = fx + tw / 2f
                val cy = fy
                val baseline = cy - (fm.ascent + fm.descent) / 2f
                canvas.save()
                canvas.rotate(-WATERMARK_DEG, cx, cy) // 负角：右边朝上
                canvas.drawText(text, fx, baseline, paint)
                canvas.restore()
                fx += stepX
            }
            fy += stepY
            rowIndex++
        }
    }
    return dst
}
