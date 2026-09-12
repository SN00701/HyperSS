package com.hyperss.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop as liquidLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberLiquidLayerBackdrop
import com.kyant.backdrop.drawBackdrop as liquidDrawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens as backdropLens
import com.kyant.backdrop.effects.vibrancy as backdropVibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.hyperss.app.ui.theme.DarkSurface
import com.hyperss.app.ui.theme.LightSurface

/**
 * Liquid Glass 液态玻璃集成层（kyant/backdrop，见 AndroidLiquidGlass 项目）：
 * 对「采样层」做折射(lens) + 轻微模糊(blur) + 鲜艳度(vibrancy) + 玻璃受光高光(highlight)，
 * 玻璃感来自折射边缘与色彩提升，而非静态渐变填充。
 *
 * 用法（每个屏幕一次接线）：
 * 1. 建两个采样源：环境光斑层（卡片采样）、滚动内容层（顶栏/悬浮按钮采样）；
 * 2. 在采样内容节点上标记 [liquidGlassLayer]；
 * 3. 玻璃元素调用 [liquidGlass] / [LiquidTopBar] 完成玻璃化。
 *
 * 注意：被标记记录层内部的元素不能反向采样自身所在层（会采样到上一帧的
 * 自身渲染产生拖影），所以「卡片」采样环境层、「顶栏/悬浮按钮」采样内容层。
 */

/** kyant 采样层类型别名。 */
typealias LiquidLayerBackdrop = com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 创建液态玻璃采样源。
 *
 * [onDraw] 控制记录层画什么（默认只画节点自身内容）；
 * 需要给记录层补不透明底色时（深色模式模糊透明会发白）传入自定义实现。
 */
@Composable
fun rememberLiquidBackdrop(
    onDraw: (ContentDrawScope.() -> Unit)? = null,
): LiquidLayerBackdrop {
    return if (onDraw != null) {
        rememberLiquidLayerBackdrop(onDraw = onDraw)
    } else {
        rememberLiquidLayerBackdrop()
    }
}

/**
 * 滚动内容层采样源：记录「不透明底色 + 内容」——底色保证深色模式下
 * 玻璃模糊透明区域不发白（与 [ambientGlassBackground] 的原有作用一致）。
 * 标记在滚动 Column 上，供顶栏 / 悬浮按钮等「内容层之外的元素」采样。
 */
@Composable
fun rememberLiquidContentBackdrop(): LiquidLayerBackdrop {
    val base = if (isDarkTheme()) DarkSurface else LightSurface
    val onDraw: ContentDrawScope.() -> Unit = remember(base) {
        {
            drawRect(base)
            drawContent()
        }
    }
    return rememberLiquidLayerBackdrop(onDraw = onDraw)
}

/**
 * 「黑底画布」内容层采样源（图片查看器 / 编辑器等全屏黑底画面）：
 * 记录「黑色底 + 画布内容」，供画布之上的悬浮玻璃元素（页码指示、翻页按钮、
 * 工具面板等）做液态玻璃采样——折射透出自定义黑色底而非主题底色。
 */
@Composable
fun rememberBlackCanvasBackdrop(): LiquidLayerBackdrop =
    rememberLiquidBackdrop(
        onDraw = {
            drawRect(Color.Black)
            drawContent()
        },
    )

/** 标记「被液态玻璃采样」的内容层（画在节点内容之下，与环境背景同层）。 */
fun Modifier.liquidGlassLayer(backdrop: LiquidLayerBackdrop): Modifier =
    liquidLayerBackdrop(backdrop)

/**
 * 液态玻璃材质修饰符：折射 + 模糊 + 鲜艳度 + 受光高光，采样 [backdrop] 层。
 *
 * - [blurRadius] 小半径（1~2dp）：内容基本清晰，玻璃感主要由折射/鲜艳度承担；
 * - [refractionHeight]/[refractionAmount] 折射边缘厚度/强度（API33+ 生效）；
 * - [vibrance] 鲜艳度（内容色度提升，玻璃质感的灵魂）；
 * - [highlight] 玻璃受光高光描边（替代静态 glassStrokeBrush 的观感来源）；
 * - [withShadow] 是否加默认投影（本应用玻璃体系默认无阴影，仅悬浮按钮启用）；
 * - [onDrawSurface] 画在玻璃与内容之间的附加层（不透明 tint 等）；
 * - [redrawKey] 随采样内容变化的值（如滚动偏移）；变化时强制重绘玻璃层，
 *   让折射内容跟随时移内容移动（否则玻璃会冻结在最后一帧）。
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: () -> Shape,
    blurRadius: Dp = 1.dp,
    refractionHeight: Dp = 8.dp,
    refractionAmount: Dp = 16.dp,
    depthEffect: Boolean = false,
    vibrance: Boolean = true,
    highlight: Boolean = true,
    withShadow: Boolean = false,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    redrawKey: Any? = null,
): Modifier {
    val density = LocalDensity.current
    val base = if (redrawKey != null) redrawOn(redrawKey) else this
    return base.liquidDrawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            if (vibrance) backdropVibrancy()
            backdropBlur(density.run { blurRadius.toPx() })
            backdropLens(
                refractionHeight = density.run { refractionHeight.toPx() },
                refractionAmount = density.run { refractionAmount.toPx() },
                depthEffect = depthEffect,
            )
        },
        highlight = if (highlight) { { Highlight.Default } } else null,
        shadow = if (withShadow) { { Shadow.Default } } else null,
        onDrawSurface = onDrawSurface,
    )
}

/** 顶栏液态玻璃的底色：压暗/提亮保证标题可读，同时保留折射透出。 */
@Composable
fun liquidTopBarTint(): Color =
    if (isDarkTheme()) Color(0x66000000) else Color(0x8CFFFFFF)

/** 卡片液态玻璃的底色（较轻，避免盖住折射）。 */
@Composable
fun liquidCardTint(): Color =
    if (isDarkTheme()) Color(0x26FFFFFF) else Color(0x33FFFFFF)

/**
 * 当 [value] 变化时强制节点重绘（不触发布局/合成）。
 *
 * 液态玻璃层只在自身重绘时才重新采样 backdrop——而滚动内容变化不会
 * 自动标记静态玻璃元素重绘，导致折射画面冻结在最后一帧。把采样内容的
 * 滚动值（如 scrollState.value）作为 [value] 传入，滚动时玻璃层实时重绘，
 * 折射即跟随时移内容移动。
 */
fun Modifier.redrawOn(value: Any): Modifier =
    this then RedrawOnElement(value)

private class RedrawOnElement(
    val value: Any,
) : ModifierNodeElement<RedrawOnNode>() {

    override fun create(): RedrawOnNode = RedrawOnNode(value)

    override fun update(node: RedrawOnNode) {
        if (node.value != value) {
            node.value = value
            node.invalidateDraw()
        }
    }

    override fun hashCode(): Int = value.hashCode()

    override fun equals(other: Any?): Boolean = other is RedrawOnElement && other.value == value
}

private class RedrawOnNode(var value: Any) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

/**
 * 液态玻璃顶栏：替代 [BlurTopBar] 的材质，保留滚动渐显行为
 * （顶部完全透明 → 滚动后材质渐入），材质为折射 + 轻微模糊 + 鲜艳度 + 受光高光。
 *
 * - 栏内容（标题/按钮）始终可见，只有玻璃材质随 [fraction] 渐入渐隐
 *   （与 [BlurTopBar] 行为一致）；
 * - [refreshKey] 传采样内容的滚动值（每次滚动帧都变化），
 *   玻璃层随之实时重绘，折射内容跟随页面滑动而非冻结；
 * - [backdrop] 采样滚动内容层（[liquidGlassLayer] 标记的层），
 *   fraction / onHeightChanged 语义与 [BlurTopBar] 一致。
 */
@Composable
fun LiquidTopBar(
    backdrop: Backdrop,
    fraction: Float,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    onHeightChanged: ((Dp) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val tint = liquidTopBarTint()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                onHeightChanged?.invoke(with(density) { coords.size.height.toDp() })
            },
    ) {
        if (fraction > 0.01f) {
            // 玻璃材质层：smoothstep 缓动渐入（与 BlurTopBar 一致）；只作用于材质，不盖内容
            val eased = fraction * fraction * (3f - 2f * fraction)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(eased)
                    .liquidGlass(
                        backdrop = backdrop,
                        // 顶栏贴顶：只圆底边，向上延伸无边界
                        shape = { RoundedCornerShape(0.dp, 0.dp, 24.dp, 24.dp) },
                        blurRadius = 2.dp,
                        refractionHeight = 10.dp,
                        refractionAmount = 20.dp,
                        onDrawSurface = { drawRect(tint) },
                        redrawKey = refreshKey,
                    ),
            )
        }
        // 栏内容（标题/按钮）始终可见
        content()
    }
}

/**
 * 环境光斑玻璃层：整页静态的环境光背景（[ambientGlassBackground]）独立成节点，
 * 标记为 [liquidAmbient] 采样源——列表内的玻璃卡片采样它，
 * 光斑透过折射边缘形成「玻璃悬浮于环境光」的观感。
 * 直接画在页面根 Box 的最底层。
 */
@Composable
fun AmbientGlassLayer(
    liquidAmbient: LiquidLayerBackdrop,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .liquidGlassLayer(liquidAmbient),
    ) {
        Box(Modifier.fillMaxSize().ambientGlassBackground())
    }
}
