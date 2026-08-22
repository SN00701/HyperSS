package com.hyperss.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hyperss.app.ui.theme.DarkSurface
import com.hyperss.app.ui.theme.LightSurface
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
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    background: Color? = null,
    content: @Composable () -> Unit,
) {
    val bg: Brush = background?.let { Brush.verticalGradient(listOf(it, it)) }
        ?: if (isDarkTheme()) {
            // 深色玻璃：白色叠加提亮，顶部受光略强
            Brush.verticalGradient(listOf(Color(0x2BFFFFFF), Color(0x16FFFFFF)))
        } else {
            // 浅色柔光玻璃：蒙层较实（参考 MIUI 相册/设置），
            // 顶部受光 89% → 底部 70%，底部透出环境光斑色彩
            Brush.verticalGradient(listOf(Color(0xE3FFFFFF), Color(0xB3FFFFFF)))
        }
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, glassStrokeBrush(), shape),
    ) {
        content()
    }
}

/**
 * 悬浮玻璃按钮：主色半透明玻璃底 + 白色受光描边 +
 * 点击缩放至 0.95（开发文档 6.2 按钮交互）。
 */
@Composable
fun GlassFloatingButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    background: Color = Color.Unspecified,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "btn-scale")
    val bg = if (background != Color.Unspecified) background
    else MiuixTheme.colorScheme.primary.copy(alpha = 0.92f)

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
