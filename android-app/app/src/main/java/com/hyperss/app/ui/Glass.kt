package com.hyperss.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hyperss.app.ui.theme.DarkSurface
import com.hyperss.app.ui.theme.LightSurface
import com.hyperss.app.ui.theme.LightTextSecondary
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max

/**
 * 全局玻璃材质体系（无阴影）：
 * 卡片与按钮统一用半透明填充 + 受光渐变描边表达层次，
 * 深浅色主题各自适配，替代原有的 elevation 投影。
 * 环境光斑背景（[AmbientGlassBackground]）透过玻璃形成"柔光"观感，
 * 对齐 MIUI 相册 / HyperOS 设置的柔光玻璃材质。
 */

/** 主题是否为深色。 */
@Composable
fun isDarkTheme(): Boolean =
    MiuixTheme.colorScheme.background.luminance() < 0.5f

/**
 * 环境柔光背景修饰符：不透明底色 + 两团光斑，画在节点内容之下。
 *
 * 用在带 `layerBackdrop` 的滚动容器上有双重作用：
 * 1. 作为页面的环境光背景，光斑透过玻璃卡片形成柔光材质；
 * 2. 让 backdrop 记录层不透明——若记录层有透明区域，`drawBackdrop`
 *    模糊时会把透明当作白色填充，深色模式下顶栏就会发白。
 */
@Composable
fun Modifier.ambientGlassBackground(): Modifier {
    val dark = isDarkTheme()
    val base = if (dark) DarkSurface else LightSurface
    // 右上主光斑：品牌蓝；左下次光斑：暖白/暗紫，对角呼应
    val spotMain = if (dark) Color(0x2E3482FF) else Color(0x263482FF)
    val spotSub = if (dark) Color(0x1F7A5CFF) else Color(0x16FFD9A0)
    return this.drawBehind {
        drawRect(base)
        val radius = max(size.width, size.height) * 0.85f
        drawRect(
            Brush.radialGradient(
                colors = listOf(spotMain, Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.06f),
                radius = radius,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(spotSub, Color.Transparent),
                center = Offset(size.width * 0.08f, size.height * 0.92f),
                radius = radius,
            ),
        )
    }
}

/**
 * 玻璃描边：顶部受光（亮）→ 底部融入背景（暗）的渐变，
 * 是无阴影玻璃卡片的边缘定义来源。
 */
@Composable
fun glassStrokeBrush(): Brush =
    if (isDarkTheme()) {
        Brush.verticalGradient(listOf(Color(0x52FFFFFF), Color(0x14FFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0xEBFFFFFF), Color(0x73FFFFFF)))
    }

/**
 * 玻璃拟态卡片：纵向受光渐变填充（顶部微亮 → 底部更透）+
 * 受光渐变描边，无阴影。层次完全由玻璃材质表达；
 * 半透明填充透出底下的 [AmbientGlassBackground] 光斑，即为柔光玻璃。
 *
 * `background` 传 null 使用主题玻璃材质，传具体颜色则平铺该色
 * （用于需要更高可读性的场景，如弹窗）。
 *
 * 传入 [liquidBackdrop] 时改用液态玻璃材质（kyant/backdrop）：
 * 折射 + 轻微模糊 + 鲜艳度 + 受光高光，`background` 降级为玻璃下垫色
 * （null 时用主题自适应的轻垫色 [liquidCardTint]），静态渐变填充停用。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    background: Color? = null,
    liquidBackdrop: com.kyant.backdrop.Backdrop? = null,
    redrawKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val glassModifier =
        if (liquidBackdrop != null) {
            val tint = background ?: liquidCardTint()
            modifier
                .liquidGlass(
                    backdrop = liquidBackdrop,
                    shape = { shape },
                    onDrawSurface = { drawRect(tint) },
                    redrawKey = redrawKey,
                )
                .clip(shape)
        } else {
            val bg: Brush = background?.let { Brush.verticalGradient(listOf(it, it)) }
                ?: if (isDarkTheme()) {
                    // 深色玻璃：白色叠加提亮，顶部受光略强
                    Brush.verticalGradient(listOf(Color(0x2BFFFFFF), Color(0x16FFFFFF)))
                } else {
                    // 浅色柔光玻璃：蒙层较实（参考 MIUI 相册/设置），
                    // 顶部受光 89% → 底部 70%，底部透出环境光斑色彩
                    Brush.verticalGradient(listOf(Color(0xE3FFFFFF), Color(0xB3FFFFFF)))
                }
            modifier
                .clip(shape)
                .background(bg)
        }
    Box(
        modifier = glassModifier
            .border(1.dp, glassStrokeBrush(), shape),
    ) {
        content()
    }
}

/**
 * 悬浮玻璃按钮：主色半透明玻璃底 + 白色受光描边 +
 * 点击缩放至 0.95（开发文档 6.2 按钮交互）。
 *
 * 传入 [liquidBackdrop] 时改用液态玻璃材质（折射 + 模糊 + 鲜艳度 +
 * 受光高光 + 默认投影）；`background` 作为玻璃下垫色自动降为半透明，
 * 未指定时以主色轻垫色承托折射。[redrawKey] 随背后内容变化（滚动位置等），
 * 折射画面实时更新而非冻结在首帧。
 */
@Composable
fun GlassFloatingButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    background: Color = Color.Unspecified,
    liquidBackdrop: com.kyant.backdrop.Backdrop? = null,
    redrawKey: Any? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "btn-scale")
    val bg = if (background != Color.Unspecified) background
    else MiuixTheme.colorScheme.primary.copy(alpha = 0.92f)
    // 液态模式下的玻璃下垫色：高透明（28%）蓝色玻璃垫——玻璃体基本不遮挡画面，
    // 背后内容清晰可见，观感由折射边缘 / 高光 / 轻模糊 + 蓝色垫色承担
    val liquidTint = if (background != Color.Unspecified) background.copy(alpha = 0.28f)
    else MiuixTheme.colorScheme.primary.copy(alpha = 0.28f)

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .then(
                if (liquidBackdrop != null) {
                    Modifier.liquidGlass(
                        backdrop = liquidBackdrop,
                        shape = { CircleShape },
                        blurRadius = 2.dp,
                        refractionHeight = 10.dp,
                        refractionAmount = 20.dp,
                        withShadow = true,
                        onDrawSurface = { drawRect(liquidTint) },
                        redrawKey = redrawKey,
                    )
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .then(if (liquidBackdrop == null) Modifier.background(bg) else Modifier)
            .then(
                if (liquidBackdrop == null) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(16.dp),
    ) {
        // 图标颜色主题自适应：近全透明玻璃下，浅色主题用深色图标保证可读
        val iconTint = if (isDarkTheme()) Color.White else MiuixTheme.colorScheme.onSurface
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (liquidBackdrop != null) iconTint else Color.White,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * 玻璃材质矩形按钮（CTA / 工具行）：
 * 传入 [liquidBackdrop] 时走液态玻璃（折射 + 轻微模糊 + 鲜艳度 + 受光高光 + 可选投影，
 * 下垫色默认主色轻垫，与 [GlassFloatingButton] 一致）；
 * 未传时走静态玻璃：半透明实心填充 + 顶部受光高光带 + 受光渐变描边，无投影。
 * 点击缩放至 0.97，禁用时整体降透明。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    liquidBackdrop: com.kyant.backdrop.Backdrop? = null,
    redrawKey: Any? = null,
    background: Color? = null,
    cornerRadius: Dp = 20.dp,
    withShadow: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, label = "glass-btn-scale")
    val baseColor = background ?: MiuixTheme.colorScheme.primary
    val tint = baseColor.copy(alpha = if (liquidBackdrop != null) 0.45f else 0.92f)

    Row(
        modifier = modifier
            .scale(scale)
            .then(if (enabled) Modifier else Modifier.alpha(0.5f))
            .then(
                if (liquidBackdrop != null) {
                    Modifier.liquidGlass(
                        backdrop = liquidBackdrop,
                        shape = { shape },
                        blurRadius = 2.dp,
                        refractionHeight = 10.dp,
                        refractionAmount = 20.dp,
                        withShadow = withShadow,
                        onDrawSurface = { drawRect(tint) },
                        redrawKey = redrawKey,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .then(
                if (liquidBackdrop == null) {
                    Modifier
                        .background(tint)
                        .drawBehind {
                            // 静态玻璃的受光高光带：顶部一条淡白光，玻璃质感来源
                            drawRect(
                                Brush.verticalGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                                    endY = size.height * 0.55f,
                                ),
                            )
                        }
                } else {
                    Modifier
                },
            )
            .border(1.dp, glassStrokeBrush(), shape)
            .clickable(
                interactionSource = interactionSource,
                enabled = enabled,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * 玻璃材质图标按钮（工具行 / 悬浮控制）：圆角方块玻璃，
 * [active] 时主色玻璃垫色、否则深色玻璃垫色；液态/静态行为同 [GlassButton]。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    active: Boolean = false,
    liquidBackdrop: com.kyant.backdrop.Backdrop? = null,
    redrawKey: Any? = null,
) {
    val shape = RoundedCornerShape(size / 3f)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "glass-icon-btn-scale")
    val liquid = liquidBackdrop != null
    val tint = when {
        active -> MiuixTheme.colorScheme.primary.copy(alpha = if (liquid) 0.55f else 1f)
        else -> Color.Black.copy(alpha = 0.55f)
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .then(
                if (liquid) {
                    Modifier.liquidGlass(
                        backdrop = liquidBackdrop,
                        shape = { shape },
                        blurRadius = 2.dp,
                        refractionHeight = 10.dp,
                        refractionAmount = 20.dp,
                        onDrawSurface = { drawRect(tint) },
                        redrawKey = redrawKey,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .then(if (liquid) Modifier else Modifier.background(tint))
            .border(1.dp, glassStrokeBrush(), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * 液态玻璃 chip 按钮（顶栏按钮 / 返回按钮等场景）：
 * 折射 + 轻模糊 + 鲜艳度 + 受光高光 + 受光渐变描边，
 * [background] 作为半透明玻璃下垫色——指定色取 45% 不透明度，
 * 未指定时用主题自适应中性玻璃垫（深色 35% 黑 / 浅色 40% 白），
 * 背后内容透过玻璃可见。
 *
 * [liquidBackdrop] 传屏幕的内容层（如 rememberLiquidContentBackdrop 的结果）；
 * [redrawKey] 传滚动位置等随内容变化的值，折射画面实时更新。
 *
 * 用法：文本按钮自内带水平内边距；图标按钮传 [size] 定圆钮尺寸、内容传图标。
 */
@Composable
fun GlassChipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    liquidBackdrop: com.kyant.backdrop.Backdrop? = null,
    redrawKey: Any? = null,
    background: Color = Color.Unspecified,
    shape: Shape = RoundedCornerShape(50),
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.94f else 1f, label = "chip-scale")
    val liquid = liquidBackdrop != null
    val tint: Color = when {
        background != Color.Unspecified -> background.copy(alpha = if (liquid) 0.45f else 0.9f)
        liquid -> if (isDarkTheme()) Color(0x59000000) else Color(0x66FFFFFF)
        else -> MiuixTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = modifier
            .scale(scale)
            .then(if (enabled) Modifier else Modifier.alpha(0.5f))
            .then(
                if (liquid) {
                    Modifier.liquidGlass(
                        backdrop = liquidBackdrop,
                        shape = { shape },
                        blurRadius = 2.dp,
                        refractionHeight = 8.dp,
                        refractionAmount = 16.dp,
                        onDrawSurface = { drawRect(tint) },
                        redrawKey = redrawKey,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .then(if (liquid) Modifier else Modifier.background(tint))
            .border(1.dp, glassStrokeBrush(), shape)
            .clickable(
                interactionSource = interactionSource,
                enabled = enabled,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 玻璃面板弹窗：miuix OverlayDialog 的替代，层次由半透明下垫 + 受光渐变描边表达，
 * 透出背后压暗的画面，与卡片玻璃体系一致。
 *
 * 弹窗运行在独立窗口，[com.kyant.backdrop.LayerBackdrop] 无法跨窗口采样
 * 背后屏幕内容，因此面板使用静态玻璃材质（与 [NewProjectDialog] 一致），
 * 折射玻璃保留给同窗口内的卡片 / 顶栏 / 悬浮元素。
 *
 * 参数与 miuix OverlayDialog 对齐（show / title / summary / onDismissRequest / content），
 * 调用方可原地替换。
 */
@Composable
fun GlassDialog(
    show: Boolean,
    title: String?,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    if (!show) return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassSurface(
                modifier = modifier
                    .fillMaxWidth(0.86f)
                    .padding(vertical = 24.dp),
                cornerRadius = 28.dp,
                // 弹窗面板：较高不透明度玻璃保证可读性，仍透出压暗的画面
                background = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    title?.let {
                        Text(it, style = MiuixTheme.textStyles.title2)
                    }
                    summary?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            it,
                            style = MiuixTheme.textStyles.body2,
                            color = LightTextSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    content()
                }
            }
        }
    }
}
