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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mkodekar.openreel.browse.*
import com.mkodekar.openreel.player.PlayerActivity
import com.mkodekar.openreel.share.ShareActivity
import com.mkodekar.openreel.stream.NetworkSource

/**
 * Home screen, laid out like MX Player (docs/MX_PLAYER_SPEC.md).
 *
 * Chrome, rows and option sheets are the hand-drawn MX widgets in MxWidgets /
 * BrowseScreen -- no Material components, so the geometry and press feedback
 * match the real app rather than Material's defaults.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw under the status bar so MX's separate status tint is ours
        // to paint (MX gets this via android:fitsSystemWindows).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // The window paints its own statusBarColor on top of our content, so
        // clear it and let MxStatusBarScrim supply the tint.
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val repo = MediaRepository(this)
        val prefs = BrowsePrefs(this)
        val settings = com.mkodekar.openreel.settings.Settings(this)
        val themePref = settings.theme
        // A magnet or .torrent opened from outside belongs in the downloader.
        val incomingTorrent = incomingTorrent(intent)

        setContent {
            MxTheme(dark = rememberThemeIsDark(themePref)) {
                // Launcher shortcuts open straight into a section.
                var section by remember {
                    mutableStateOf(
                        when {
                            incomingTorrent != null -> Section.TORRENT
                            else -> runCatching {
                                Section.valueOf(intent?.getStringExtra("section").orEmpty())
                            }.getOrDefault(Section.VIDEOS)
                        }
                    )
                }
                var drawerOpen by remember { mutableStateOf(false) }
                // null = not searching; "" = search open but empty.
                var search by remember { mutableStateOf<String?>(null) }

                // Back should dismiss the drawer, then search, before leaving.
                androidx.activity.compose.BackHandler(enabled = drawerOpen || search != null) {
                    when {
                        drawerOpen -> drawerOpen = false
                        search != null -> search = null
                    }
                }

                MxDrawerScaffold(
                    open = drawerOpen,
                    section = section,
                    onOpenChange = { drawerOpen = it },
                    onSection = { section = it },
                    onShare = {
                        startActivity(Intent(this@MainActivity, ShareActivity::class.java))
                    },
                    onVault = {
                        startActivity(
                            Intent(
                                this@MainActivity,
                                com.mkodekar.openreel.vault.VaultActivity::class.java,
                            )
                        )
                    },
                    onSettings = {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    },
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Mx.colors.background)
                    ) {
                        // MX tints the status strip separately from the toolbar.
                        MxStatusBarScrim()
                        val query = search
                        if (query != null && section == Section.VIDEOS) {
                            MxSearchBar(
                                value = query,
                                onValueChange = { search = it },
                                onClose = { search = null },
                            )
                        } else {
                            MxToolbar(
                                title = section.title,
                                onMenu = { drawerOpen = true },
                            ) {
                                if (section == Section.VIDEOS) {
                                    MxIconButton(onClick = { search = "" }) { MxSearchGlyph() }
                                }
                            }
                        }
                        when (section) {
                            Section.VIDEOS -> VideosTab(repo, prefs, query)
                            Section.NETWORK -> NetworkTab()
                            Section.TORRENT -> TorrentScreen(
                                pending = incomingTorrent,
                                onPlay = { open(it) },
                            )
                        }
                    }
                }
            }
        }
    }

    // --- Videos --------------------------------------------------------------

    @Composable
    private fun VideosTab(repo: MediaRepository, prefs: BrowsePrefs, query: String?) {
        var videos by remember { mutableStateOf(emptyList<VideoItem>()) }
        var granted by remember { mutableStateOf(hasMediaPermission()) }
        var viewMode by remember { mutableStateOf(prefs.viewMode) }
        var layout by remember { mutableStateOf(prefs.layout) }
        var sort by remember { mutableStateOf(prefs.sort) }
        var openFolder by remember { mutableStateOf<String?>(null) }
        var showOptions by remember { mutableStateOf(false) }
        var refreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // One place to rescan, used by the initial load and pull-to-refresh.
        suspend fun reload() {
            videos = withContext(Dispatchers.IO) {
                runCatching { repo.queryVideos() }.getOrDefault(emptyList())
            }
        }

        val request = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { r -> granted = r.values.any { it } || hasMediaPermission() }

        LaunchedEffect(Unit) { if (!granted) request.launch(mediaPermissions()) }
        // LaunchedEffect runs on the Main dispatcher, so scanning MediaStore
        // here blocked the UI thread outright -- the activity never drew and
        // ActivityTaskManager reported a pause timeout. Do the query on IO.
        LaunchedEffect(granted) { if (granted) reload() }

        // MediaStore does not push changes, so also rescan when the screen
        // comes back: a video recorded in another app should be here.
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, granted) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && granted) {
                    scope.launch { reload() }
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }

        if (!granted) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MxText("OpenReel needs permission to list your videos.", Mx.type.primary)
                Spacer(Modifier.height(16.dp))
                MxButton("Grant access") { request.launch(mediaPermissions()) }
            }
            return
        }

        val sorted = remember(videos, sort) { videos.sortedWith(sort.comparator()) }
        // Searching shows matching files flat, ignoring the folder drill-down:
        // you are looking for a file, not a place.
        val searching = !query.isNullOrBlank()
        val results = remember(sorted, query) {
            if (!searching) sorted
            else sorted.filter { it.title.contains(query!!.trim(), ignoreCase = true) }
        }
        val inFolder = if (searching) null else openFolder

        Column(Modifier.fillMaxSize()) {
            // MX's header strip above the list: where you are + the live sort.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(start = if (inFolder != null) 2.dp else 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inFolder != null) {
                    MxIconButton(onClick = { openFolder = null }) { MxChevron() }
                }
                Column(Modifier.weight(1f).padding(start = if (inFolder != null) 2.dp else 0.dp)) {
                    MxText(
                        when {
                            searching -> "${results.size} result(s)"
                            inFolder != null ->
                                inFolder.substringAfterLast('/').ifBlank { "Internal storage" }
                            viewMode == ViewMode.FOLDERS -> "Folders"
                            else -> "All videos"
                        },
                        Mx.type.mediumPrimary,
                        maxLines = 1,
                    )
                    MxText(
                        "${sort.field.label} · ${sort.field.directionLabel(sort.descending)}",
                        Mx.type.secondary,
                        Mx.colors.textSecondary,
                        maxLines = 1,
                    )
                }
                MxOverflow({ showOptions = true })
            }
            MxDivider()

            MxPullToRefresh(
                refreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        reload()
                        refreshing = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
            when {
                searching && results.isEmpty() -> Centered("Nothing matches \"${query!!.trim()}\".")
                sorted.isEmpty() -> Centered("No videos found.")

                !searching && viewMode == ViewMode.FOLDERS && inFolder == null -> {
                    val folders = remember(sorted) {
                        sorted.groupBy { it.folderPath }
                            .toList()
                            .sortedBy { it.first.lowercase() }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(folders, key = { it.first }) { (path, inside) ->
                            FolderRow(
                                name = path.substringAfterLast('/').ifBlank { "Internal storage" },
                                count = inside.size,
                                path = path,
                            ) { openFolder = path }
                        }
                    }
                }

                else -> MediaCollection(
                    items = when {
                        searching -> results
                        inFolder != null -> sorted.filter { it.folderPath == inFolder }
                        else -> sorted
                    },
                    layout = layout,
                    onOpen = { open(it.uri.toString()) },
                    onOverflow = { open(it.uri.toString()) },
                )
            }
            }
        }

        ViewOptionsSheet(
            visible = showOptions,
            viewMode = viewMode,
            layout = layout,
            sort = sort,
            onViewMode = { viewMode = it; prefs.viewMode = it; openFolder = null },
            onLayout = { layout = it; prefs.layout = it },
            onSort = { sort = it; prefs.sort = it },
            onDismiss = { showOptions = false },
        )
    }

    // --- Network -------------------------------------------------------------

    @Composable
    private fun NetworkTab() {
        var url by remember { mutableStateOf("") }
        val valid = NetworkSource.isSupported(url)
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            MxSectionLabel("Stream URL")
            MxField(url, { url = it }, "http, smb, rtsp, ftp…", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(14.dp))
            MxButton("Play", enabled = valid) { open(url) }
            if (url.isNotEmpty() && !valid) {
                Spacer(Modifier.height(10.dp))
                MxText(
                    "Unsupported scheme. Supported: ${NetworkSource.supportedSchemes.joinToString()}",
                    Mx.type.secondary,
                    Mx.colors.textSecondary,
                )
            }
        }
    }

    @Composable
    private fun Centered(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MxText(text, Mx.type.primary, Mx.colors.textSecondary)
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun open(uri: String) {
        if (uri.isBlank()) return
        startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(uri)))
    }

    /**
     * A magnet link or .torrent handed to us from outside. Returns the magnet
     * itself, or the content/file URI of a torrent file, for the download
     * manager to pick up.
     */
    private fun incomingTorrent(intent: Intent?): String? {
        if (intent == null) return null
        val data = intent.dataString
        if (data != null) {
            if (data.startsWith("magnet:", true)) return data
            if (data.endsWith(".torrent", true)) return data
            if (intent.type == "application/x-bittorrent") return data
        }
        if (intent.action == Intent.ACTION_SEND) {
            if (intent.type == "application/x-bittorrent") {
                androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )?.let { return it.toString() }
            }
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.split(Regex("""\s+"""))
                ?.firstOrNull { it.startsWith("magnet:", true) }
                ?.let { return it }
        }
        return null
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
