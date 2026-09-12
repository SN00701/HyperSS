package com.hyperss.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.noiseDither
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶部动态模糊栏（MIUI 相册 / haze 渐变风格）：
 * 页面处于顶部时完全透明；内容向上滑动时，标题栏背后的毛玻璃
 * 随滚动渐入，滚回顶部后渐隐。
 *
 * 材质采用 haze 的 HazeProgressive.verticalGradient 思路：
 * 顶部全强度 → 底部完全透明的垂直渐变遮罩，无硬边界。
 *
 * 用法：
 * ```
 * val backdrop = rememberLayerBackdrop()
 * val listState = rememberLazyListState()
 * val fraction = rememberTopBarBlurFraction(listState)
 * var barHeight by remember { mutableStateOf(0.dp) }
 *
 * Box(Modifier.fillMaxSize()) {
 *     LazyColumn(
 *         state = listState,
 *         modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
 *         contentPadding = PaddingValues(top = barHeight, ...),
 *     ) { ... }
 *     BlurTopBar(
 *         backdrop = backdrop,
 *         fraction = fraction,
 *         modifier = Modifier.align(Alignment.TopCenter),
 *         onHeightChanged = { barHeight = it },
 *     ) {
 *         // 原标题行
 *     }
 * }
 * ```
 */

/** 浅色模式毛玻璃遮罩：半透明白，遮盖度适中以保留模糊观感。 */
private val BlurBarTintLight = Color.White.copy(alpha = 0.5f)

/** 深色模式毛玻璃遮罩：偏实的黑色蒙层，压暗偏白的玻璃卡片模糊。 */
private val BlurBarTintDark = Color.Black.copy(alpha = 0.5f)

/**
 * 根据列表滚动位置计算 0f..1f 的模糊渐入系数：
 * 滚动 [fadeRange] 距离内线性渐入，越过首个条目后恒为 1f。
 */
@Composable
fun rememberTopBarBlurFraction(
    state: LazyListState,
    fadeRange: Dp = 72.dp,
): Float {
    val rangePx = with(LocalDensity.current) { fadeRange.toPx() }
    val fraction by remember(state, rangePx) {
        derivedStateOf {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / rangePx).coerceIn(0f, 1f)
        }
    }
    return fraction
}

/** [rememberTopBarBlurFraction] 的 LazyVerticalGrid 版本。 */
@Composable
fun rememberTopBarBlurFraction(
    state: LazyGridState,
    fadeRange: Dp = 72.dp,
): Float {
    val rangePx = with(LocalDensity.current) { fadeRange.toPx() }
    val fraction by remember(state, rangePx) {
        derivedStateOf {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / rangePx).coerceIn(0f, 1f)
        }
    }
    return fraction
}

/** [rememberTopBarBlurFraction] 的 [androidx.compose.foundation.verticalScroll] 版本（整页单一滚动）。 */
@Composable
fun rememberTopBarBlurFraction(
    state: ScrollState,
    fadeRange: Dp = 72.dp,
): Float {
    val rangePx = with(LocalDensity.current) { fadeRange.toPx() }
    val fraction by remember(state, rangePx) {
        derivedStateOf {
            (state.value / rangePx).coerceIn(0f, 1f)
        }
    }
    return fraction
}

/**
 * 悬浮在滚动内容之上的顶部栏。栏内容（标题/按钮）始终可见，
 * 仅背后的毛玻璃材质随 [fraction] 渐入渐隐，因此滚动到顶部时
 * 视觉与普通透明标题栏完全一致。
 *
 * 材质绘制参考 haze（github.com/chrisbanes/haze）的
 * HazeProgressive.verticalGradient：模糊层用垂直渐变 alpha 遮罩
 * （BlendMode.DstIn + 离屏合成）裁剪——顶部保持全强度，
 * 靠近底边平滑衰减到完全透明，因此没有任何硬切边。
 *
 * @param backdrop 由滚动内容上 `Modifier.layerBackdrop()` 标记的采样源
 * @param fraction 0f..1f 模糊渐入系数（来自 [rememberTopBarBlurFraction]）
 * @param blurRadius 满强度时的模糊半径
 * @param maxIntensity 滚动到底时材质的最大不透明度上限
 * @param onHeightChanged 栏高回调，用于给滚动内容的 contentPadding 赋值，
 *   使内容从栏下方开始又能滚动到栏背后
 */
@Composable
fun BlurTopBar(
    backdrop: LayerBackdrop,
    fraction: Float,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 30.dp,
    maxIntensity: Float = 1f,
    onHeightChanged: ((Dp) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // 依据当前主题背景亮度自动适配深浅色材质
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val tint = if (dark) BlurBarTintDark else BlurBarTintLight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                onHeightChanged?.invoke(with(density) { coords.size.height.toDp() })
            },
    ) {
        if (fraction > 0.01f) {
            // smoothstep 缓动，再限制到 maxIntensity 上限
            val eased = fraction * fraction * (3f - 2f * fraction)
            val intensity = eased * maxIntensity
            // 模糊半径随滚动从 35% 加深到 100%
            val blurPx = density.run { (blurRadius * (0.35f + 0.65f * fraction)).toPx() }
            // 过滤模糊中的黑灰：Screen 叠加半透明白等效 out=in*(1-a)+a，
            // 把深色照片等暗色内容压向亮背景。仅浅色主题使用——
            // 深色模式下白雾会填进毛玻璃的透明间隙，悬在暗背景上发白发灰
            val shadowLift = if (dark) null else Color.White.copy(alpha = 0.5f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // 离屏合成必须位于 drawWithContent 外层：
                    // DstIn 遮罩只裁剪本缓冲内的毛玻璃，不影响底层内容
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // haze HazeProgressive.verticalGradient(1f→0f, EaseIn) 等效遮罩：
                        // 前段保持强度，末段平滑衰减到透明，无硬边界
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = intensity),
                                0.55f to Color.Black.copy(alpha = intensity),
                                0.8f to Color.Black.copy(alpha = intensity * 0.5f),
                                1.0f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                // 毛玻璃采样单独成层：提亮只作用于模糊结果，不波及色调蒙版
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = {
                                blur(blurPx, blurPx)
                                noiseDither(0.12f)
                            },
                        )
                        .drawWithContent {
                            if (shadowLift != null) {
                                drawRect(shadowLift, blendMode = BlendMode.Screen)
                            }
                        },
                )
                // 色调蒙版作为兄弟层画在毛玻璃之上
                Box(modifier = Modifier.matchParentSize().background(tint))
            }
        }
        content()
    }
}
