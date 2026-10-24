// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.browse.LayoutMode
import com.mkodekar.openreel.browse.SortField
import com.mkodekar.openreel.browse.SortState
import com.mkodekar.openreel.browse.ViewMode

/**
 * MX Player's "View Options" menu, reproduced from the shipped APK.
 *
 * Structure comes from MX's view_options_* strings and its
 * layout_options_menu_* layouts (docs/MX_PLAYER_SPEC.md section 4):
 *
 *   View Mode   Files | Folders
 *   Layout      Grid  | List
 *   Sort        <field rows, each with its own direction labels>
 *
 * The sort rows mirror OptionsMenuSelectSortView: the selected side is filled
 * with the accent colour, the unselected side stays on the surface colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewOptionsSheet(
    viewMode: ViewMode,
    layout: LayoutMode,
    sort: SortState,
    onViewMode: (ViewMode) -> Unit,
    onLayout: (LayoutMode) -> Unit,
    onSort: (SortState) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            SectionLabel("View Mode")
            SegmentedPair(
                leftText = "Folders", rightText = "Files",
                leftSelected = viewMode == ViewMode.FOLDERS,
                onLeft = { onViewMode(ViewMode.FOLDERS) },
                onRight = { onViewMode(ViewMode.FILES) },
            )

            SectionLabel("Layout")
            SegmentedPair(
                leftText = "List", rightText = "Grid",
                leftSelected = layout == LayoutMode.LIST,
                onLeft = { onLayout(LayoutMode.LIST) },
                onRight = { onLayout(LayoutMode.GRID) },
            )

            SectionLabel("Sort by")
            // Each field row shows its own asc/desc wording, exactly as MX does.
            SortField.menuOrder.forEach { field ->
                val selected = sort.field == field
                SortRow(
                    field = field,
                    selected = selected,
                    descending = if (selected) sort.descending else field.defaultDescending,
                    onPick = { desc -> onSort(SortState(field, desc)) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** MX's two-up selector (left half / right half, selected side tinted). */
@Composable
private fun SegmentedPair(
    leftText: String,
    rightText: String,
    leftSelected: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        Half(leftText, leftSelected, Modifier.weight(1f), onLeft)
        Spacer(Modifier.width(2.dp))
        Half(rightText, !leftSelected, Modifier.weight(1f), onRight)
    }
}

@Composable
private fun Half(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One sort field with its two direction choices. MX pairs e.g. Size with
 * "Smallest / Largest" rather than a generic asc/desc toggle.
 */
@Composable
private fun SortRow(
    field: SortField,
    selected: Boolean,
    descending: Boolean,
    onPick: (Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))) {
            DirectionHalf(
                text = field.ascLabel,
                active = selected && !descending,
                modifier = Modifier.weight(1f),
            ) { onPick(false) }
            Spacer(Modifier.width(2.dp))
            DirectionHalf(
                text = field.descLabel,
                active = selected && descending,
                modifier = Modifier.weight(1f),
            ) { onPick(true) }
        }
    }
}

@Composable
private fun DirectionHalf(
    text: String,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active) {
                Spacer(Modifier.width(4.dp))
                // MX shows an arrow on the selected direction.
                Text(
                    text = if (text == "A to Z" || text.startsWith("Oldest") ||
                               text == "Ascending" || text == "Shortest" ||
                               text == "Smallest" || text == "Lowest") "↑" else "↓",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
    }
}
