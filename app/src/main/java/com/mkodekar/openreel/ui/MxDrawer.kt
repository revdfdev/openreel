// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp

/**
 * MX Player's navigation drawer.
 *
 * MX's main screen is a DrawerLayout (res/layout/list.xml) with a toolbar and
 * the folder list -- there is no tab strip anywhere in the app. An earlier
 * version of this screen invented a Videos/Network/Torrent TabRow, which is
 * the single biggest structural difference from the real thing; the sections
 * belong in the drawer instead.
 */

enum class Section(val title: String) {
    VIDEOS("Videos"),
    NETWORK("Network stream"),
    TORRENT("Torrent"),
}

@Composable
fun MxDrawerScaffold(
    open: Boolean,
    section: Section,
    onOpenChange: (Boolean) -> Unit,
    onSection: (Section) -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()

        val progress by animateFloatAsState(
            if (open) 1f else 0f, tween(210), label = "drawer",
        )

        if (progress > 0.001f) {
            // Scrim over the content.
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(progress * 0.6f)
                    .background(Color.Black)
                    .then(
                        if (open) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onOpenChange(false) } else Modifier
                    )
            )
            // Panel, sliding in from the start edge.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val panelWidth = minOf(maxWidth * 0.82f, 320.dp)
                Box(
                    Modifier
                        .offset(x = -panelWidth * (1f - progress))
                        .width(panelWidth)
                        .fillMaxHeight()
                        .background(Mx.colors.toolbar)
                ) {
                    DrawerContent(
                        section = section,
                        onSection = { onSection(it); onOpenChange(false) },
                        onShare = { onShare(); onOpenChange(false) },
                        onSettings = { onSettings(); onOpenChange(false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    section: Section,
    onSection: (Section) -> Unit,
    onShare: () -> Unit,
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
            DrawerRow(
                label = s.title,
                selected = s == section,
                onClick = { onSection(s) },
            )
        }

        Spacer(Modifier.height(8.dp))
        MxDivider(startIndent = 20.dp)
        Spacer(Modifier.height(8.dp))

        DrawerRow("Share files", selected = false, onClick = onShare)
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
