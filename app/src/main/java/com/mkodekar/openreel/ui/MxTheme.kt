// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mkodekar.openreel.R

/**
 * MX Player's design system, rebuilt from the shipped APK -- metrics and
 * typography 1:1, colours ours.
 *
 * This deliberately does NOT use Material3's ColorScheme/Typography. MX is not
 * a Material app: its rows, badges, tab strip and option sheets have their own
 * geometry, and routing them through Material components is what made the
 * earlier attempt look like "a Material app in MX colours". Everything here is
 * raw Compose over exact values read out of MX's layout XML.
 *
 * Sources (docs/MX_PLAYER_SPEC.md):
 *   list_row_media.xml       video row      thumb 96x56, pad 14/8, title 14sp
 *   list_row_listable.xml    folder row     icon 62dp, margins 16dp
 *   list_grid_media.xml      grid cell      card 1:0.564, title minLines 2
 *   styles.xml               NewText.*      15/14/12/10sp, Muli + Muli SemiBold
 */

// --- palette -----------------------------------------------------------------
// MX's own dark navy is #16202b / #26374c / #3c8cf0. Ours is a graphite +
// violet set so the app is clearly not a reskinned MX. Swap these values and
// nothing else: every screen reads its colours from here.

@Immutable
data class MxColors(
    val background: Color,
    /**
     * MX tints three separate tones -- status bar, toolbar and content -- and
     * using one flat colour for all of them is what made our chrome feel wrong.
     * MX dark: status #1f2d3c, toolbar #1c2939, content #16202b.
     */
    val statusBar: Color,
    val toolbar: Color,
    val surface: Color,        // rows, cards, thumbnails, option pills
    val surfaceRaised: Color,  // sheets, bars sitting above the list
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,         // selection, active tab, highlights
    val onAccent: Color,
    val divider: Color,
    val badge: Color,          // duration badge ("tag_dark" in MX)
    val badgeNew: Color,       // "NEW" tag ("tag_red" in MX)
    val isDark: Boolean,
)

val MxDark = MxColors(
    background = Color(0xFF14161C),
    statusBar = Color(0xFF1E212B),
    toolbar = Color(0xFF1A1D26),
    surface = Color(0xFF22262F),
    surfaceRaised = Color(0xFF1B1F27),
    textPrimary = Color(0xFFE6E8ED),
    textSecondary = Color(0xFF8E94A3),
    accent = Color(0xFF7C6BF0),
    onAccent = Color(0xFFFFFFFF),
    divider = Color(0xFF262A34),
    badge = Color(0xCC000000),
    badgeNew = Color(0xFFF0455F),
    isDark = true,
)

val MxLight = MxColors(
    background = Color(0xFFFFFFFF),
    statusBar = Color(0xFFE8EAF0),
    toolbar = Color(0xFFF7F8FB),
    surface = Color(0xFFF1F2F6),
    surfaceRaised = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF23252E),
    textSecondary = Color(0xFF787F8F),
    accent = Color(0xFF5B47E0),
    onAccent = Color(0xFFFFFFFF),
    divider = Color(0xFFE4E6EC),
    badge = Color(0xCC000000),
    badgeNew = Color(0xFFF0455F),
    isDark = false,
)

// --- typography (MX's NewText.* styles) --------------------------------------

private val Muli = FontFamily(
    Font(R.font.muli_regular, FontWeight.Normal),
    Font(R.font.muli_semibold, FontWeight.SemiBold),
    Font(R.font.muli_bold, FontWeight.Bold),
)

@Immutable
data class MxType(
    /** NewText.Primary -- folder title. */
    val primary: TextStyle,
    /** NewText.Medium.Primary -- file/video title. */
    val mediumPrimary: TextStyle,
    /** NewText.Secondary -- counts, paths, info lines. */
    val secondary: TextStyle,
    /** NewText.Medium.Tag -- duration / NEW badges. */
    val tag: TextStyle,
    /** Toolbar + section titles. */
    val toolbar: TextStyle,
    val tab: TextStyle,
)

private val MxTypeDefault = MxType(
    primary = TextStyle(
        fontFamily = Muli, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 19.sp,
    ),
    mediumPrimary = TextStyle(
        fontFamily = Muli, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp,
    ),
    secondary = TextStyle(
        fontFamily = Muli, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 15.sp,
    ),
    tag = TextStyle(
        fontFamily = Muli, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, letterSpacing = 0.5.sp,
    ),
    // toolbar.xml: 18sp, @font/font_muli_bold
    toolbar = TextStyle(
        fontFamily = Muli, fontWeight = FontWeight.Bold, fontSize = 18.sp,
    ),
    tab = TextStyle(
        fontFamily = Muli, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
    ),
)

// --- metrics straight out of MX's layouts -----------------------------------

object MxDim {
    // list_row_media.xml
    val rowMinHeight = 54.dp
    val rowPadStart = 14.dp
    val rowPadVertical = 8.dp
    val thumbWidth = 96.dp
    val thumbHeight = 56.dp
    val thumbCorner = 4.dp
    val rowTextGap = 8.dp
    val moreWidth = 40.dp

    // list_row_listable.xml
    val folderIcon = 62.dp        // @dimen/directory_width
    val folderMargin = 16.dp

    // list_grid_media.xml
    const val GRID_ASPECT = 1f / 0.564f   // app:layout_constraintDimensionRatio
    val gridPadVertical = 4.dp
    val gridTitleTopGap = 4.dp
    val gridTitleEndGap = 18.dp

    // badges (round_box_small)
    val badgeCorner = 2.dp
    val badgeMargin = 4.dp
}

// --- plumbing ----------------------------------------------------------------

val LocalMxColors: ProvidableCompositionLocal<MxColors> = staticCompositionLocalOf { MxDark }
val LocalMxType: ProvidableCompositionLocal<MxType> = staticCompositionLocalOf { MxTypeDefault }

/** Shorthand so screens read `Mx.colors.accent` / `Mx.type.secondary`. */
object Mx {
    val colors: MxColors @Composable get() = LocalMxColors.current
    val type: MxType @Composable get() = LocalMxType.current
}

/**
 * Resolves the Settings "theme" preference ("system" | "light" | "dark") to a
 * concrete choice. Without this the stored preference was simply ignored and
 * every screen followed the system setting.
 */
@Composable
fun rememberThemeIsDark(preference: String): Boolean = when (preference) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun MxTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMxColors provides if (dark) MxDark else MxLight,
        LocalMxType provides MxTypeDefault,
        content = content,
    )
}
