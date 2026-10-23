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
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.browse.MediaRepository
import com.mkodekar.openreel.browse.VideoItem
import com.mkodekar.openreel.player.PlayerActivity
import com.mkodekar.openreel.share.ShareActivity
import com.mkodekar.openreel.stream.NetworkSource

/**
 * Home screen. Tabs map to the modules: Videos (local) | Network | Torrent.
 * Each hands a URI to PlayerActivity, which routes it (file / stream / magnet).
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MediaRepository(this)
        setContent {
            MaterialTheme {
                var tab by remember { mutableIntStateOf(0) }
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("OpenReel") },
                        actions = {
                            TextButton(onClick = {
                                startActivity(Intent(this@MainActivity, ShareActivity::class.java))
                            }) { Text("Share") }
                            TextButton(onClick = {
                                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                            }) { Text("Settings") }
                        },
                    )
                }) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        TabRow(tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("Videos") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("Network") })
                            Tab(tab == 2, { tab = 2 }, text = { Text("Torrent") })
                        }
                        when (tab) {
                            0 -> VideosTab(repo)
                            1 -> NetworkTab()
                            else -> TorrentTab()
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VideosTab(repo: MediaRepository) {
        var videos by remember { mutableStateOf(emptyList<VideoItem>()) }
        var granted by remember { mutableStateOf(hasMediaPermission()) }

        val request = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result -> granted = result.values.any { it } || hasMediaPermission() }

        LaunchedEffect(Unit) { if (!granted) request.launch(mediaPermissions()) }
        LaunchedEffect(granted) {
            if (granted) videos = runCatching { repo.queryVideos() }.getOrDefault(emptyList())
        }

        if (!granted) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("OpenReel needs permission to list your videos.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { request.launch(mediaPermissions()) }) { Text("Grant access") }
            }
            return
        }
        if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No videos found.")
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(videos) { v ->
                ListItem(
                    headlineContent = { Text(v.title) },
                    supportingContent = { Text(formatDuration(v.durationMs)) },
                    modifier = Modifier.clickable { open(v.uri.toString()) },
                )
                HorizontalDivider()
            }
        }
    }

    @Composable
    private fun NetworkTab() {
        var url by remember { mutableStateOf("") }
        val valid = NetworkSource.isSupported(url)
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Stream URL (http, smb, rtsp, ftpâ€¦)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { open(url) }, enabled = valid) { Text("Play") }
            if (url.isNotEmpty() && !valid) {
                Spacer(Modifier.height(8.dp))
                Text("Unsupported scheme. Supported: ${NetworkSource.supportedSchemes.joinToString()}")
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
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { open(magnet) }, enabled = valid) { Text("Stream while downloading") }
            Spacer(Modifier.height(12.dp))
            Text("Only download content you're legally entitled to.")
        }
    }

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

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }
}
