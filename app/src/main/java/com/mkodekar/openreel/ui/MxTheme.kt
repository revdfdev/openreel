// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MX Player's "Aurora Dark" (dark navy) palette, taken from the shipped APK's
 * mxskin__*__dark resources. See docs/MX_PLAYER_SPEC.md section 2.
 */
object Mx {
    // --- dark navy (MX's default) ---
    val Background = Color(0xFF16202B)   // mxskin__aurora_background__dark
    val Surface = Color(0xFF26374C)      // list item / card / divider
    val SurfaceDisabled = Color(0xFF2D3D52)
    val TextPrimary = Color(0xFFDADDE4)
    val TextSecondary = Color(0xFF85929C)
    val Accent = Color(0xFF3C8CF0)       // selected / highlight blue
    val Brand = Color(0xFF1467CD)        // colorPrimary
    val Divider = Color(0xFF26374C)

    // --- light (Aurora Light) ---
    val LightBackground = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF2F4F7)
    val LightTextPrimary = Color(0xFF35344C)
    val LightTextSecondary = Color(0xFF85929C)
}

private val MxDarkColors = darkColorScheme(
    primary = Mx.Accent,
    onPrimary = Color.White,
    secondary = Mx.Accent,
    background = Mx.Background,
    onBackground = Mx.TextPrimary,
    surface = Mx.Background,
    onSurface = Mx.TextPrimary,
    surfaceVariant = Mx.Surface,
    onSurfaceVariant = Mx.TextSecondary,
    outline = Mx.Divider,
)

private val MxLightColors = lightColorScheme(
    primary = Mx.Brand,
    onPrimary = Color.White,
    secondary = Mx.Accent,
    background = Mx.LightBackground,
    onBackground = Mx.LightTextPrimary,
    surface = Mx.LightBackground,
    onSurface = Mx.LightTextPrimary,
    surfaceVariant = Mx.LightSurface,
    onSurfaceVariant = Mx.LightTextSecondary,
    outline = Color(0xFFE1E4EA),
)

/**
 * MX uses the Muli typeface. We don't ship it (it's bundled in their APK), so
 * we match its metrics with the platform sans-serif at MX's sizes instead.
 */
private val MxTypography = Typography(
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun MxTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) MxDarkColors else MxLightColors,
        typography = MxTypography,
        content = content,
    )
}
