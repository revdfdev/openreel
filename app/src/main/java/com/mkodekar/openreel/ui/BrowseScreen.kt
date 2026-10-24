// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import android.util.Size as AndroidSize
import com.mkodekar.openreel.browse.Thumbnails
import com.mkodekar.openreel.browse.LayoutMode
import com.mkodekar.openreel.browse.VideoItem
import java.util.concurrent.TimeUnit

/**
 * The browse list, rebuilt against MX Player's own layouts rather than
 * approximated. Every dimension below is quoted from the decompiled XML:
 *
 *   list_row_media.xml      minHeight 54sp, padding 14/8/8, thumb 96x56 with a
 *                           32dp centre glyph, 8dp text gap, title 14sp up to
 *                           3 lines with 4dp extra leading, then the secondary
 *                           info line, then a 40x38 overflow target.
 *   list_row_listable.xml   62dp folder icon at 16dp margins, 16dp text inset,
 *                           title 15sp max 2 lines, 2dp gap, count line, then
 *                           a single-line path.
 *   list_grid_media.xml     card at ratio 1:0.564 with 4dp corners, title 4dp
 *                           below with minLines=2 so cells stay aligned, and
 *                           an 18dp end inset reserved for the overflow.
 */

/**
 * Thumbnail that resolves its own frame. A cached bitmap paints immediately;
 * anything else is decoded off the main thread and faded in, so scrolling a
 * long folder never blocks on MediaMetadataRetriever.
 */
@Composable
fun VideoThumb(
    v: VideoItem,
    modifier: Modifier,
    iconSize: androidx.compose.ui.unit.Dp,
    requestSize: AndroidSize,
) {
    val context = LocalContext.current
    var frame by remember(v.id) {
        mutableStateOf(Thumbnails.cached(v.id)?.asImageBitmap())
    }
    LaunchedEffect(v.id) {
        if (frame == null) {
            frame = Thumbnails.load(context, v, requestSize)?.asImageBitmap()
        }
    }
    MxThumb(
        modifier = modifier,
        duration = formatDuration(v.durationMs),
        iconSize = iconSize,
        frame = frame,
    )
}

/** list_row_media.xml */
@Composable
fun MediaRow(
    v: VideoItem,
    onClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MxDim.rowMinHeight)
            .mxClickable(onClick = onClick)
            .padding(
                start = MxDim.rowPadStart,
                top = MxDim.rowPadVertical,
                bottom = MxDim.rowPadVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumb(
            v = v,
            modifier = Modifier.size(MxDim.thumbWidth, MxDim.thumbHeight),
            iconSize = 32.dp,
            requestSize = AndroidSize(192, 112),   // 2x the 96x56 slot
        )
        Spacer(Modifier.width(MxDim.rowTextGap))
        Column(Modifier.weight(1f)) {
            MxText(v.title, Mx.type.mediumPrimary, maxLines = 3)
            Spacer(Modifier.height(2.dp))
            MxText(metaLine(v), Mx.type.secondary, Mx.colors.textSecondary, maxLines = 1)
        }
        MxOverflow(onOverflow)
    }
    MxDivider(startIndent = MxDim.rowPadStart + MxDim.thumbWidth + MxDim.rowTextGap)
}

/** list_row_listable.xml */
@Composable
fun FolderRow(
    name: String,
    count: Int,
    path: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .mxClickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(start = MxDim.folderMargin)
                .size(MxDim.folderIcon),
            contentAlignment = Alignment.Center,
        ) {
            MxFolderGlyph(MxDim.folderIcon * 0.62f, Mx.colors.surface, Mx.colors.accent)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = MxDim.folderMargin)
        ) {
            MxText(name, Mx.type.primary, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MxText(
                    if (count == 1) "1 video" else "$count videos",
                    Mx.type.secondary,
                    Mx.colors.textSecondary,
                )
            }
            if (path.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                MxText(path, Mx.type.secondary, Mx.colors.textSecondary, maxLines = 1)
            }
        }
    }
    MxDivider(startIndent = MxDim.folderIcon + MxDim.folderMargin * 2)
}

/** list_grid_media.xml */
@Composable
fun MediaGridCell(
    v: VideoItem,
    onClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    Column(
        Modifier
            .padding(vertical = MxDim.gridPadVertical, horizontal = 4.dp)
            .mxClickable(onClick = onClick)
    ) {
        VideoThumb(
            v = v,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(MxDim.GRID_ASPECT),
            iconSize = 40.dp,     // grid uses a 40dp glyph, list uses 32dp
            requestSize = AndroidSize(512, 288),
        )
        Spacer(Modifier.height(MxDim.gridTitleTopGap))
        Row {
            // minLines = 2 keeps every cell the same height, as MX does.
            MxText(
                v.title,
                Mx.type.mediumPrimary,
                maxLines = 2,
                minLines = 2,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(MxDim.gridTitleEndGap - 14.dp))
        }
        MxText(metaLine(v), Mx.type.secondary, Mx.colors.textSecondary, maxLines = 1)
    }
}

/** Shared list/grid switch used by the Videos tab. */
@Composable
fun MediaCollection(
    items: List<VideoItem>,
    layout: LayoutMode,
    onOpen: (VideoItem) -> Unit,
    onOverflow: (VideoItem) -> Unit,
) {
    if (layout == LayoutMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { v ->
                MediaGridCell(v, { onOpen(v) }, { onOverflow(v) })
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { v ->
                MediaRow(v, { onOpen(v) }, { onOverflow(v) })
            }
        }
    }
}

// --- formatting --------------------------------------------------------------

/** MX labels its duration badge "1:02:33" / "4:21". */
fun formatDuration(ms: Long): String {
    val total = TimeUnit.MILLISECONDS.toSeconds(ms)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
    bytes > 0 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> ""
}

/** MX's secondary line: size and resolution, separated by a middle dot. */
fun metaLine(v: VideoItem): String =
    listOf(formatSize(v.sizeBytes), v.resolutionLabel)
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
