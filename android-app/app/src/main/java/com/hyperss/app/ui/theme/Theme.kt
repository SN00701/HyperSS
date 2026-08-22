package com.hyperss.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private val LightColors = lightColorScheme(
    background = LightSurface,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceSecondary = LightTextSecondary,
    onSurfaceVariantSummary = LightTextSecondary,
)

private val DarkColors = darkColorScheme(
    background = DarkSurface,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceSecondary = DarkTextSecondary,
    onSurfaceVariantSummary = DarkTextSecondary,
)

@Composable
fun HyperSSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MiuixTheme(
        colors = if (darkTheme) DarkColors else LightColors,
        textStyles = HyperTextStyles,
        content = content,
    )
}