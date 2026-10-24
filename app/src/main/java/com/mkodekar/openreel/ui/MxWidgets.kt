// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MX-shaped primitives. Everything the browse/torrent/share screens draw comes
 * from here so there is exactly one place that defines "what an MX row looks
 * like". No Material3 widgets: MX's press feedback is a flat state-list
 * highlight, not a Material ripple, so [mxClickable] reproduces that instead.
 */

/** Flat press highlight, the way MX's ?attr/selectableItemBackground reads. */
@Composable
fun Modifier.mxClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val overlay by animateColorAsState(
        if (pressed) Mx.colors.textPrimary.copy(alpha = 0.08f) else Color.Transparent,
        tween(90), label = "press",
    )
    return this
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
        .background(overlay)
}

@Composable
fun MxText(
    text: String,
    style: TextStyle,
    color: Color = Mx.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    align: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        style = style.copy(color = color, textAlign = align ?: TextAlign.Unspecified),
        maxLines = maxLines,
        minLines = minLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun MxDivider(startIndent: Dp = 0.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(Mx.colors.divider)
    )
}

/**
 * MX's `round_box_small` tag: 2dp corners, 10sp semibold white text, used for
 * the duration badge (tag_dark) and the NEW badge (tag_red).
 */
@Composable
fun MxBadge(text: String, background: Color = Mx.colors.badge) {
    Box(
        Modifier
            .clip(RoundedCornerShape(MxDim.badgeCorner))
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        MxText(text, Mx.type.tag, color = Color.White)
    }
}

/**
 * Thumbnail card: MX wraps the thumb in a CardView with 4dp corners and 2dp
 * elevation, overlays a centred play glyph while no frame is loaded, and pins
 * the duration badge to the bottom-right with a 4dp margin.
 */
@Composable
fun MxThumb(
    modifier: Modifier,
    duration: String?,
    iconSize: Dp = 32.dp,
    isNew: Boolean = false,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(MxDim.thumbCorner))
            .background(Mx.colors.surface)
    ) {
        // Placeholder glyph until real frame extraction lands.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(iconSize)
                    .alpha(0.45f),
                contentAlignment = Alignment.Center,
            ) { MxPlayGlyph(iconSize * 0.62f, Mx.colors.textSecondary) }
        }
        if (isNew) {
            Box(Modifier.align(Alignment.TopStart).padding(MxDim.badgeMargin)) {
                MxBadge("NEW", Mx.colors.badgeNew)
            }
        }
        if (duration != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(MxDim.badgeMargin)) {
                MxBadge(duration)
            }
        }
    }
}

/** Simple vector-free play triangle, so we ship no extra drawables. */
@Composable
fun MxPlayGlyph(size: Dp, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, 0f)
            lineTo(w, h / 2f)
            lineTo(w * 0.12f, h)
            close()
        }
        drawPath(path, color)
    }
}

/** MX's folder glyph: a rounded tab-top rectangle. */
@Composable
fun MxFolderGlyph(size: Dp, color: Color, accent: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = w * 0.06f
        // tab
        drawRoundRect(
            color = accent,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        // body
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.28f),
            size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.46f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 1.6f, r * 1.6f),
        )
    }
}

/** Three-dot overflow glyph (MX's ic_more_option), 40x38 touch target. */
@Composable
fun MxOverflow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(MxDim.moreWidth, 38.dp)
            .clip(RoundedCornerShape(19.dp))
            .mxClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(3.5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Mx.colors.textSecondary)
                )
            }
        }
    }
}

/** Back chevron, drawn rather than shipped as a drawable. */
@Composable
fun MxBack(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .mxClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.14f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.68f, h * 0.08f)
                lineTo(w * 0.22f, h * 0.5f)
                lineTo(w * 0.68f, h * 0.92f)
            }
            drawPath(p, Color.Unspecified.takeOrElse { Color.White }, style = stroke)
        }
    }
}

private fun Color.takeOrElse(other: () -> Color) = if (this == Color.Unspecified) other() else this

/** MX's toolbar: title left, flat text/icon actions right, no elevation. */
@Composable
fun MxToolbar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Mx.colors.background)
            .height(56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            MxIconButton(onClick = onBack) { MxChevron() }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = if (onBack == null) 12.dp else 2.dp)
        ) {
            MxText(title, Mx.type.toolbar, maxLines = 1)
            if (subtitle != null) {
                MxText(subtitle, Mx.type.secondary, Mx.colors.textSecondary, maxLines = 1)
            }
        }
        actions()
    }
}

@Composable
fun MxIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .mxClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun MxChevron() {
    val c = Mx.colors.textPrimary
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width; val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.66f, h * 0.06f)
            lineTo(w * 0.24f, h * 0.5f)
            lineTo(w * 0.66f, h * 0.94f)
        }
        drawPath(
            p, c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.14f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
    }
}

/** Flat text action for the toolbar. */
@Composable
fun MxTextAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .mxClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) { MxText(label, Mx.type.tab, Mx.colors.textSecondary) }
}

/**
 * MX's tab strip: evenly divided labels with a 2dp accent indicator that
 * slides to the selected tab. Material's TabRow look (ripple + pill) is what
 * gave the old screen away, so this is drawn directly.
 */
@Composable
fun MxTabStrip(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Mx.colors.background)) {
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                val color by animateColorAsState(
                    if (active) Mx.colors.accent else Mx.colors.textSecondary,
                    tween(140), label = "tabColor",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .mxClickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { MxText(label.uppercase(), Mx.type.tab, color) }
            }
        }
        // sliding indicator
        BoxWithConstraints(Modifier.fillMaxWidth().height(2.dp)) {
            val tabWidth = maxWidth / labels.size.coerceAtLeast(1)
            val offset by animateDpAsState(tabWidth * selected, tween(180), label = "ind")
            Box(Modifier.fillMaxWidth().height(1.dp).background(Mx.colors.divider))
            Box(
                Modifier
                    .offset(x = offset)
                    .width(tabWidth)
                    .height(2.dp)
                    .background(Mx.colors.accent)
            )
        }
    }
}

/**
 * MX's option pill pair (used by the sort/layout rows). The selected half is
 * filled with the accent; the other sits on the surface colour.
 */
@Composable
fun MxPillPair(
    left: String,
    right: String,
    leftActive: Boolean,
    rightActive: Boolean,
    leftTrailing: String? = null,
    rightTrailing: String? = null,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        MxPill(left, leftActive, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp), leftTrailing, Modifier.weight(1f), onLeft)
        Spacer(Modifier.width(2.dp))
        MxPill(right, rightActive, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp), rightTrailing, Modifier.weight(1f), onRight)
    }
}

@Composable
private fun MxPill(
    text: String,
    active: Boolean,
    shape: RoundedCornerShape,
    trailing: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (active) Mx.colors.accent else Mx.colors.surface, tween(130), label = "pill",
    )
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .mxClickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MxText(
                text,
                Mx.type.secondary,
                if (active) Mx.colors.onAccent else Mx.colors.textSecondary,
                maxLines = 1,
            )
            if (trailing != null) {
                Spacer(Modifier.width(3.dp))
                MxText(
                    trailing,
                    Mx.type.secondary,
                    if (active) Mx.colors.onAccent else Mx.colors.textSecondary,
                )
            }
        }
    }
}

/** Section caption above a group of options, as MX labels its sheets. */
@Composable
fun MxSectionLabel(text: String) {
    MxText(
        text.uppercase(),
        Mx.type.tag.copy(letterSpacing = 1.sp),
        Mx.colors.textSecondary,
        modifier = Modifier.padding(start = 2.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** Outlined button in MX's flat style (used by the share/torrent screens). */
@Composable
fun MxButton(
    label: String,
    enabled: Boolean = true,
    filled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (filled) Modifier.background(Mx.colors.accent.copy(alpha = alpha))
                else Modifier.border(BorderStroke(1.dp, Mx.colors.divider), RoundedCornerShape(6.dp))
            )
            .mxClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        MxText(
            label,
            Mx.type.tab,
            if (filled) Mx.colors.onAccent.copy(alpha = alpha)
            else Mx.colors.textPrimary.copy(alpha = alpha),
        )
    }
}

/** Single-line text field without Material's floating-label chrome. */
@Composable
fun MxField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: androidx.compose.ui.text.input.KeyboardType =
        androidx.compose.ui.text.input.KeyboardType.Text,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Mx.colors.surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            MxText(placeholder, Mx.type.primary, Mx.colors.textSecondary, maxLines = 1)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Mx.type.primary.copy(color = Mx.colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Mx.colors.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Thin progress track in MX's flat style. */
@Composable
fun MxProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(240), label = "prog")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Mx.colors.surface)
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(Mx.colors.accent)
        )
    }
}
