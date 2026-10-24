// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mkodekar.openreel.browse.*
import com.mkodekar.openreel.player.PlayerActivity
import com.mkodekar.openreel.share.ShareActivity
import com.mkodekar.openreel.stream.NetworkSource
import java.util.concurrent.TimeUnit

/**
 * Home screen, styled after MX Player (docs/MX_PLAYER_SPEC.md).
 *
 * Browse mirrors MX: a Folders view that drills into a folder, or a flat Files
 * view, each in List or Grid layout, ordered by MX's (field + direction) sort.
 * The overflow button opens the View Options sheet.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MediaRepository(this)
        val prefs = BrowsePrefs(this)

        setContent {
            MxTheme {
                var tab by remember { mutableIntStateOf(0) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        MxTopBar(
                            onShare = { startActivity(Intent(this@MainActivity, ShareActivity::class.java)) },
                            onSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                        )
                        MxTabs(tab) { tab = it }
                        when (tab) {
                            0 -> VideosTab(repo, prefs)
                            1 -> NetworkTab()
                            else -> TorrentTab()
                        }
                    }
                }
            }
        }
    }

    // --- chrome --------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MxTopBar(onShare: () -> Unit, onSettings: () -> Unit) {
        TopAppBar(
            title = {
                Text(
                    "OpenReel",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
            actions = {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onSettings) { Text("Settings") }
            },
        )
    }

    /** MX-style tab strip: accent underline on the active tab. */
    @Composable
    private fun MxTabs(selected: Int, onSelect: (Int) -> Unit) {
        TabRow(
            selectedTabIndex = selected,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            listOf("Videos", "Network", "Torrent").forEachIndexed { i, label ->
                Tab(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    text = {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected == i) FontWeight.Medium else FontWeight.Normal,
                        )
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // --- Videos --------------------------------------------------------------

    @Composable
    private fun VideosTab(repo: MediaRepository, prefs: BrowsePrefs) {
        var videos by remember { mutableStateOf(emptyList<VideoItem>()) }
        var granted by remember { mutableStateOf(hasMediaPermission()) }
        var viewMode by remember { mutableStateOf(prefs.viewMode) }
        var layout by remember { mutableStateOf(prefs.layout) }
        var sort by remember { mutableStateOf(prefs.sort) }
        var openFolder by remember { mutableStateOf<String?>(null) }
        var showOptions by remember { mutableStateOf(false) }

        val request = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result -> granted = result.values.any { it } || hasMediaPermission() }

        LaunchedEffect(Unit) { if (!granted) request.launch(mediaPermissions()) }
        LaunchedEffect(granted) {
            if (granted) videos = runCatching { repo.queryVideos() }.getOrDefault(emptyList())
        }

        if (!granted) {
            PermissionPrompt { request.launch(mediaPermissions()) }
            return
        }

        val sorted = remember(videos, sort) { videos.sortedWith(sort.comparator()) }

        Column(Modifier.fillMaxSize()) {
            // Options bar: current sort summary + overflow, like MX's header row.
            OptionsBar(
                breadcrumb = openFolder,
                subtitle = "${sort.field.label} · ${sort.field.directionLabel(sort.descending)}",
                onBack = { openFolder = null },
                onOverflow = { showOptions = true },
            )

            when {
                sorted.isEmpty() -> EmptyState("No videos found.")

                // Folders view, top level: one row per folder.
                viewMode == ViewMode.FOLDERS && openFolder == null -> {
                    val folders = remember(sorted) {
                        sorted.groupBy { it.folderPath }.toList().sortedBy { it.first.lowercase() }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(folders, key = { it.first }) { (path, items) ->
                            FolderRow(path, items.size) { openFolder = path }
                        }
                    }
                }

                else -> {
                    val shown = if (openFolder != null) sorted.filter { it.folderPath == openFolder } else sorted
                    if (layout == LayoutMode.GRID) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            contentPadding = PaddingValues(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(shown, key = { it.id }) { v ->
                                VideoGridCell(v) { open(v.uri.toString()) }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(shown, key = { it.id }) { v ->
                                VideoListRow(v) { open(v.uri.toString()) }
                            }
                        }
                    }
                }
            }
        }

        if (showOptions) {
            ViewOptionsSheet(
                viewMode = viewMode,
                layout = layout,
                sort = sort,
                onViewMode = { viewMode = it; prefs.viewMode = it; openFolder = null },
                onLayout = { layout = it; prefs.layout = it },
                onSort = { sort = it; prefs.sort = it },
                onDismiss = { showOptions = false },
            )
        }
    }

    @Composable
    private fun OptionsBar(
        breadcrumb: String?,
        subtitle: String,
        onBack: () -> Unit,
        onOverflow: () -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (breadcrumb != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack, contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = if (breadcrumb != null) 0.dp else 12.dp)) {
                Text(
                    text = breadcrumb?.substringAfterLast('/')?.ifBlank { "Internal storage" }
                        ?: "All videos",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOverflow) {
                Icon(
                    Icons.Default.MoreVert, contentDescription = "View options",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }

    @Composable
    private fun FolderRow(path: String, count: Int, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text("▶", color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = path.substringAfterLast('/').ifBlank { "Internal storage" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (count == 1) "1 video" else "$count videos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 74.dp),
        )
    }

    @Composable
    private fun VideoListRow(v: VideoItem, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(Modifier.size(width = 76.dp, height = 44.dp), v.durationMs)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = v.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metaLine(v),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 106.dp),
        )
    }

    @Composable
    private fun VideoGridCell(v: VideoItem, onClick: () -> Unit) {
        Column(
            Modifier
                .padding(6.dp)
                .clickable(onClick = onClick),
        ) {
            Thumb(Modifier.fillMaxWidth().aspectRatio(16f / 9f), v.durationMs)
            Spacer(Modifier.height(6.dp))
            Text(
                text = v.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLine(v),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }

    /**
     * Thumbnail slot with MX's duration badge. Real frame extraction is a
     * follow-up; the badge and geometry are already in place.
     */
    @Composable
    private fun Thumb(modifier: Modifier, durationMs: Long) {
        Box(
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("▶", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .padding(3.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(0xCC000000),
                        RoundedCornerShape(2.dp),
                    )
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }

    @Composable
    private fun PermissionPrompt(onGrant: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "OpenReel needs permission to list your videos.",
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrant) { Text("Grant access") }
        }
    }

    @Composable
    private fun EmptyState(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // --- Network / Torrent ---------------------------------------------------

    @Composable
    private fun NetworkTab() {
        var url by remember { mutableStateOf("") }
        val valid = NetworkSource.isSupported(url)
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Stream URL (http, smb, rtsp, ftp…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { open(url) }, enabled = valid) { Text("Play") }
            if (url.isNotEmpty() && !valid) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unsupported scheme. Supported: ${NetworkSource.supportedSchemes.joinToString()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun TorrentTab() {
        var magnet by remember { mutableStateOf("") }
        val valid = magnet.startsWith("magnet:", ignoreCase = true)
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            OutlinedTextField(
                value = magnet,
                onValueChange = { magnet = it },
                label = { Text("Magnet link") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Uri
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { open(magnet) }, enabled = valid) { Text("Stream while downloading") }
            Spacer(Modifier.height(12.dp))
            Text(
                "Only download content you're legally entitled to.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun open(uri: String) {
        if (uri.isBlank()) return
        startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(uri)))
    }

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasMediaPermission(): Boolean =
        mediaPermissions().any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}

/** "1:02:33" / "4:21", as MX labels its duration badge. */
private fun formatDuration(ms: Long): String {
    val total = TimeUnit.MILLISECONDS.toSeconds(ms)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
    bytes > 0 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> ""
}

/** MX's secondary line: duration, size, and resolution when known. */
private fun metaLine(v: VideoItem): String =
    listOf(formatDuration(v.durationMs), formatSize(v.sizeBytes), v.resolutionLabel)
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
