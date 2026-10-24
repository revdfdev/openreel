// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * MX Player's navigation drawer.
 *
 * MX's main screen is a DrawerLayout (res/layout/list.xml) with a toolbar and
 * the folder list -- there is no tab strip anywhere in the app. An earlier
 * version of this screen invented a Videos/Network/Torrent TabRow, which was
 * the biggest structural difference from the real thing; the sections belong
 * in the drawer instead.
 *
 * Like a real DrawerLayout this opens by dragging in from the start edge as
 * well as from the toolbar handle, and the panel tracks your finger rather
 * than only animating between the two end states.
 */

enum class Section(val title: String) {
    VIDEOS("Videos"),
    NETWORK("Network stream"),
    TORRENT("Torrent"),
}

/** Width of the edge strip that starts an open-drag, as DrawerLayout uses. */
private val EDGE_GRAB = 20.dp

@Composable
fun MxDrawerScaffold(
    open: Boolean,
    section: Section,
    onOpenChange: (Boolean) -> Unit,
    onSection: (Section) -> Unit,
    onShare: () -> Unit,
    onVault: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = minOf(maxWidth * 0.82f, 320.dp)
        val panelPx = with(density) { panelWidth.toPx() }

        // 0 = closed, 1 = fully open. Driven by drag, settled on release.
        val progress = remember { Animatable(0f) }

        // Follow the caller's state when it changes from outside (handle tap,
        // back press, picking an item).
        LaunchedEffect(open) {
            progress.animateTo(if (open) 1f else 0f, tween(210))
        }

        fun settle(velocity: Float) {
            val target = when {
                velocity > 600f -> 1f
                velocity < -600f -> 0f
                else -> if (progress.value > 0.5f) 1f else 0f
            }
            scope.launch {
                progress.animateTo(target, tween(180))
                onOpenChange(target == 1f)
            }
        }

        val dragState = rememberDraggableState { delta ->
            scope.launch {
                progress.snapTo((progress.value + delta / panelPx).coerceIn(0f, 1f))
            }
        }

        content()

        val p = progress.value

        // Edge strip that starts an open-drag; only live while closed so it
        // never steals horizontal gestures from the list.
        if (p < 0.01f) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(EDGE_GRAB)
                    .fillMaxHeight()
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = { v -> settle(v) },
                    )
            )
        }

        if (p > 0.001f) {
            // Scrim; tapping it closes, dragging it follows the finger.
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(p * 0.6f)
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpenChange(false) }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = { v -> settle(v) },
                    )
            )
            // Panel.
            Box(
                Modifier
                    .offset { IntOffset((-panelPx * (1f - p)).roundToInt(), 0) }
                    .width(panelWidth)
                    .fillMaxHeight()
                    .background(Mx.colors.toolbar)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = { v -> settle(v) },
                    )
            ) {
                DrawerContent(
                    section = section,
                    onSection = { onSection(it); onOpenChange(false) },
                    onShare = { onShare(); onOpenChange(false) },
                    onVault = { onVault(); onOpenChange(false) },
                    onSettings = { onSettings(); onOpenChange(false) },
                )
            }
        }
    }
}

@Composable
private fun DrawerContent(
    section: Section,
    onSection: (Section) -> Unit,
    onShare: () -> Unit,
    onVault: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header sits under the status bar, in the status tone.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Mx.colors.statusBar)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 20.dp, top = 22.dp, bottom = 22.dp)
        ) {
            MxText("OpenReel", Mx.type.toolbar)
            Spacer(Modifier.height(3.dp))
            MxText(
                "Local · Network · Torrent",
                Mx.type.secondary,
                Mx.colors.textSecondary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Section.entries.forEach { s ->
            DrawerRow(s.title, selected = s == section) { onSection(s) }
        }

        Spacer(Modifier.height(8.dp))
        MxDivider(startIndent = 20.dp)
        Spacer(Modifier.height(8.dp))

        DrawerRow("Share files", selected = false, onClick = onShare)
        DrawerRow("Private Folder", selected = false, onClick = onVault)
        DrawerRow("Settings", selected = false, onClick = onSettings)
    }
}

@Composable
private fun DrawerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) Mx.colors.accent.copy(alpha = 0.16f) else Color.Transparent
            )
            .mxClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
    ) {
        MxText(
            label,
            Mx.type.primary,
            if (selected) Mx.colors.accent else Mx.colors.textPrimary,
        )
    }
}
