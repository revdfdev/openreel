// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.ui.*
import kotlinx.coroutines.delay

/** Transient gesture feedback, mirroring MX's brightness/volume/seek HUDs. */
sealed interface Hud {
    data class Brightness(val fraction: Float) : Hud
    data class Volume(val fraction: Float) : Hud
    data class Seek(val targetMs: Long, val deltaMs: Long) : Hud
    data class Message(val text: String) : Hud
}

/**
 * The player chrome, laid out like MX Player's ActivityScreen:
 *
 *   top bar     (56dp, @dimen/player_action_bar_height)
 *               back | title | lock | PiP | overflow
 *   centre      prev-10s | play/pause | next-10s, shown with the bars
 *   bottom bar  (48dp min, @dimen/bottom_bar_min_height)
 *               elapsed | scrubber | remaining, then the action row:
 *               audio/subtitle tracks, speed, zoom, rotate
 *
 * Bars auto-hide after [AUTO_HIDE_MS] of no interaction, as MX does, and any
 * tap toggles them back. Everything is drawn with the MX widgets so the player
 * matches the browse UI.
 */
private const val AUTO_HIDE_MS = 3500L

@Composable
fun PlayerOverlay(
    title: String,
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    locked: Boolean,
    hud: Hud?,
    /** True while mpv is opening, seeking or waiting on the network cache. */
    buffering: Boolean,
    /** mpv's cache-buffering-state, or -1 when it is not a cache stall. */
    bufferPercent: Int,
    onTogglePause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Int) -> Unit,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onPip: () -> Unit,
    onTracks: () -> Unit,
    onOptions: () -> Unit,
    onZoom: () -> Unit,
    seekStepSeconds: Int,
) {
    var visible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    fun poke() { visible = true; interactionTick++ }

    // Auto-hide, restarted on every interaction; held open while scrubbing.
    LaunchedEffect(interactionTick, scrubbing, paused) {
        if (!visible || scrubbing) return@LaunchedEffect
        delay(AUTO_HIDE_MS)
        visible = false
    }

    MxTheme(dark = true) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { if (!locked) { if (visible) visible = false else poke() } },
                        onDoubleTap = { if (!locked) { onTogglePause(); poke() } },
                    )
                }
        ) {
            // --- buffering spinner (independent of the bars) ---
            if (buffering) {
                Box(Modifier.align(Alignment.Center)) {
                    BufferingSpinner(bufferPercent)
                }
            }

            // --- gesture HUD (independent of the bars) ---
            HudLayer(hud)

            if (locked) {
                // Locked: only a single unlock affordance, as MX does.
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(18.dp)
                ) {
                    GlyphButton(onClick = { onToggleLock(); poke() }) { LockGlyph(locked = true) }
                }
                return@Box
            }

            // --- top bar ---
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically(tween(180)) { -it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(150)) { -it } + fadeOut(tween(150)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                            )
                        )
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .height(56.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphButton(onClick = onBack) { MxChevron() }
                    MxText(
                        title,
                        Mx.type.mediumPrimary,
                        Color.White,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    GlyphButton(onClick = { onToggleLock(); poke() }) { LockGlyph(false) }
                    GlyphButton(onClick = onPip) { PipGlyph() }
                    GlyphButton(onClick = { onOptions(); poke() }) { DotsGlyph() }
                }
            }

            // --- centre transport ---
            // Hidden while buffering: the spinner occupies the same centre
            // spot, and drawing both puts the ring behind the play button.
            AnimatedVisibility(
                visible = visible && !buffering,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(150)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(34.dp),
                ) {
                    CircleButton(44.dp, onClick = { onSeekBy(-seekStepSeconds); poke() }) {
                        SkipGlyph(forward = false, seconds = seekStepSeconds)
                    }
                    CircleButton(66.dp, onClick = { onTogglePause(); poke() }) {
                        if (paused) MxPlayGlyph(24.dp, Color.White) else PauseGlyph(22.dp)
                    }
                    CircleButton(44.dp, onClick = { onSeekBy(seekStepSeconds); poke() }) {
                        SkipGlyph(forward = true, seconds = seekStepSeconds)
                    }
                }
            }

            // --- bottom bar ---
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(180)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(150)) { it } + fadeOut(tween(150)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                            )
                        )
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(horizontal = 14.dp)
                        .padding(top = 10.dp, bottom = 8.dp),
                ) {
                    val shownFraction =
                        if (scrubbing) scrubFraction
                        else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MxText(
                            fmtTime(if (scrubbing) (shownFraction * durationMs).toLong() else positionMs),
                            Mx.type.tag.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
                            Color.White,
                        )
                        Spacer(Modifier.width(10.dp))
                        Scrubber(
                            fraction = shownFraction,
                            modifier = Modifier.weight(1f),
                            onScrubStart = { scrubbing = true; poke() },
                            onScrub = { scrubFraction = it; poke() },
                            onScrubEnd = {
                                scrubbing = false
                                if (durationMs > 0) onSeekTo((scrubFraction * durationMs).toLong())
                                poke()
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        MxText(
                            "-" + fmtTime(
                                (durationMs - (if (scrubbing) (shownFraction * durationMs).toLong() else positionMs))
                                    .coerceAtLeast(0)
                            ),
                            Mx.type.tag.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
                            Color.White.copy(alpha = 0.75f),
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    // MX's action row under the scrubber.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ActionItem("Tracks") { onTracks(); poke() }
                        ActionItem("Speed") { onOptions(); poke() }
                        ActionItem("Zoom") { onZoom(); poke() }
                    }
                }
            }
        }
    }
}

// --- scrubber ---------------------------------------------------------------

/**
 * Flat MX-style scrubber: thin track, accent fill, round knob. Drawn rather
 * than using a platform SeekBar, whose thumb and ripple are unmistakable.
 */
@Composable
private fun Scrubber(
    fraction: Float,
    modifier: Modifier,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    var width by remember { mutableIntStateOf(1) }
    Box(
        modifier
            .height(28.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onScrubStart(); onScrub((offset.x / width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubEnd() },
                    onDragCancel = { onScrubEnd() },
                ) { change, _ ->
                    onScrub((change.position.x / width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val accent = Mx.colors.accent
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val cy = size.height / 2f
            val h = 3.dp.toPx()
            // track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(0f, cy - h / 2),
                size = androidx.compose.ui.geometry.Size(size.width, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h, h),
            )
            // played
            drawRoundRect(
                color = accent,
                topLeft = Offset(0f, cy - h / 2),
                size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h, h),
            )
            // knob
            drawCircle(
                color = accent,
                radius = 6.5.dp.toPx(),
                center = Offset(size.width * fraction.coerceIn(0f, 1f), cy),
            )
        }
    }
}

/**
 * Indeterminate ring, plus the cache percentage when mpv reports one. A
 * network stream can take several seconds to open, and with no indicator the
 * black screen reads as a failure.
 */
@Composable
private fun BufferingSpinner(percent: Int) {
    val t = rememberInfiniteTransition(label = "buffer")
    val angle by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spin",
    )
    val accent = Mx.colors.accent
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(27.dp))
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(28.dp)) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 3.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.22f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke,
                )
                drawArc(
                    color = accent,
                    startAngle = angle, sweepAngle = 90f, useCenter = false, style = stroke,
                )
            }
        }
        if (percent in 0..100) {
            Spacer(Modifier.height(8.dp))
            MxText("$percent%", Mx.type.secondary, Color.White)
        }
    }
}

// --- HUD --------------------------------------------------------------------

@Composable
private fun BoxScope.HudLayer(hud: Hud?) {
    AnimatedVisibility(
        visible = hud != null,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(220)),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.66f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val h = hud) {
                is Hud.Brightness -> HudBar("Brightness", h.fraction)
                is Hud.Volume -> HudBar("Volume", h.fraction)
                is Hud.Seek -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MxText(fmtTime(h.targetMs), Mx.type.toolbar, Color.White)
                    Spacer(Modifier.height(3.dp))
                    MxText(
                        (if (h.deltaMs >= 0) "+" else "-") + fmtTime(kotlin.math.abs(h.deltaMs)),
                        Mx.type.secondary,
                        Color.White.copy(alpha = 0.8f),
                    )
                }
                is Hud.Message -> MxText(h.text, Mx.type.mediumPrimary, Color.White)
                null -> Unit
            }
        }
    }
}

@Composable
private fun HudBar(label: String, fraction: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MxText(label, Mx.type.secondary, Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(132.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Mx.colors.accent)
            )
        }
        Spacer(Modifier.height(5.dp))
        MxText("${(fraction * 100).toInt()}%", Mx.type.tag, Color.White)
    }
}

// --- small pieces -----------------------------------------------------------

@Composable
private fun GlyphButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .mxClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CircleButton(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.34f))
            .mxClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ActionItem(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .mxClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) { MxText(label, Mx.type.tab, Color.White) }
}

@Composable private fun PauseGlyph(size: androidx.compose.ui.unit.Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(size * 0.26f)) {
        repeat(2) {
            Box(Modifier.width(size * 0.3f).height(size).background(Color.White))
        }
    }
}

@Composable private fun LockGlyph(locked: Boolean) {
    Canvas(Modifier.size(17.dp)) {
        val w = size.width; val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.11f)
        // shackle
        drawArc(
            color = Color.White,
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.26f, if (locked) h * 0.08f else h * 0.02f),
            size = androidx.compose.ui.geometry.Size(w * 0.48f, h * 0.46f),
            style = stroke,
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.17f, h * 0.44f),
            size = androidx.compose.ui.geometry.Size(w * 0.66f, h * 0.48f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.09f, w * 0.09f),
        )
    }
}

@Composable private fun PipGlyph() {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(
            color = Color.White,
            topLeft = Offset.Zero,
            size = androidx.compose.ui.geometry.Size(w, h * 0.84f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f, w * 0.1f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f),
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.46f, h * 0.38f),
            size = androidx.compose.ui.geometry.Size(w * 0.48f, h * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f, w * 0.06f),
        )
    }
}

@Composable private fun DotsGlyph() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) {
            Box(Modifier.size(3.5.dp).clip(RoundedCornerShape(50)).background(Color.White))
        }
    }
}

@Composable private fun SkipGlyph(forward: Boolean, seconds: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            repeat(2) {
                Box(Modifier.size(9.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width; val h = size.height
                        val p = androidx.compose.ui.graphics.Path().apply {
                            if (forward) {
                                moveTo(0f, 0f); lineTo(w, h / 2f); lineTo(0f, h)
                            } else {
                                moveTo(w, 0f); lineTo(0f, h / 2f); lineTo(w, h)
                            }
                            close()
                        }
                        drawPath(p, Color.White)
                    }
                }
            }
        }
        MxText("$seconds", Mx.type.tag, Color.White.copy(alpha = 0.85f))
    }
}

/** Hours only when the file is long enough, as MX does. */
fun fmtTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
