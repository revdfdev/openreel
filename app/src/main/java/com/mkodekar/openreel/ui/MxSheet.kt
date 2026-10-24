// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mkodekar.openreel.browse.LayoutMode
import com.mkodekar.openreel.browse.SortField
import com.mkodekar.openreel.browse.SortState
import com.mkodekar.openreel.browse.ViewMode

/**
 * Bottom sheet drawn by hand. Material's ModalBottomSheet brings a drag handle,
 * its own corner radii and tonal elevation -- all visibly Material. MX's sheet
 * is a flat panel with 12dp top corners that slides up over a scrim.
 */
@Composable
fun MxSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Popup(
        properties = PopupProperties(focusable = true),
        onDismissRequest = onDismiss,
    ) {
        Box(Modifier.fillMaxSize()) {
            // scrim
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        )
                )
            }
            AnimatedVisibility(
                visible = true,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    initialOffsetY = { it },
                ),
                exit = slideOutVertically(tween(160)) { it },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(Mx.colors.surfaceRaised)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/**
 * MX's View Options panel: View Mode / Layout / Sort by.
 *
 * Reproduced from MX's view_options_* strings and its
 * layout_options_menu_* layouts. The sort rows follow
 * OptionsMenuSelectSortView: one row per field, each with its own two
 * direction labels, the active one filled with the accent and carrying an
 * arrow. See docs/MX_PLAYER_SPEC.md sections 3 and 4.
 */
@Composable
fun ViewOptionsSheet(
    visible: Boolean,
    viewMode: ViewMode,
    layout: LayoutMode,
    sort: SortState,
    onViewMode: (ViewMode) -> Unit,
    onLayout: (LayoutMode) -> Unit,
    onSort: (SortState) -> Unit,
    onDismiss: () -> Unit,
) {
    MxSheet(visible, onDismiss) {
        Spacer(Modifier.height(14.dp))
        MxText("View options", Mx.type.toolbar.copy(fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)))

        MxSectionLabel("View mode")
        MxPillPair(
            left = "Folders", right = "Files",
            leftActive = viewMode == ViewMode.FOLDERS,
            rightActive = viewMode == ViewMode.FILES,
            onLeft = { onViewMode(ViewMode.FOLDERS) },
            onRight = { onViewMode(ViewMode.FILES) },
        )

        MxSectionLabel("Layout")
        MxPillPair(
            left = "List", right = "Grid",
            leftActive = layout == LayoutMode.LIST,
            rightActive = layout == LayoutMode.GRID,
            onLeft = { onLayout(LayoutMode.LIST) },
            onRight = { onLayout(LayoutMode.GRID) },
        )

        MxSectionLabel("Sort by")
        SortField.menuOrder.forEach { field ->
            val isField = sort.field == field
            Column(Modifier.padding(bottom = 10.dp)) {
                MxText(
                    field.label,
                    Mx.type.mediumPrimary,
                    if (isField) Mx.colors.accent else Mx.colors.textPrimary,
                )
                Spacer(Modifier.height(5.dp))
                MxPillPair(
                    left = field.ascLabel,
                    right = field.descLabel,
                    leftActive = isField && !sort.descending,
                    rightActive = isField && sort.descending,
                    leftTrailing = if (isField && !sort.descending) "↑" else null,
                    rightTrailing = if (isField && sort.descending) "↓" else null,
                    onLeft = { onSort(SortState(field, false)) },
                    onRight = { onSort(SortState(field, true)) },
                )
            }
        }
    }
}
