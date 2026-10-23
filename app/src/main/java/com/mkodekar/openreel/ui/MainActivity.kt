package com.mkodekar.openreel.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.browse.MediaRepository
import com.mkodekar.openreel.browse.VideoItem
import com.mkodekar.openreel.player.PlayerActivity
import com.mkodekar.openreel.share.ShareActivity

/**
 * Home / browse screen. Tabs mirror the four modules:
 *   Videos (local) | Network | Torrent | Share
 * Only the Videos list is fleshed out here; the rest are entry points.
 */
class MainActivity : ComponentActivity() {

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MediaRepository(this)
        setContent {
            MaterialTheme {
                var videos by remember { mutableStateOf(emptyList<VideoItem>()) }
                // TODO: request READ_MEDIA_VIDEO before querying; do query off main thread.
                LaunchedEffect(Unit) { videos = runCatching { repo.queryVideos() }.getOrDefault(emptyList()) }

                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("OpenReel") },
                        actions = {
                            TextButton(onClick = {
                                startActivity(Intent(this@MainActivity, ShareActivity::class.java))
                            }) { Text("Share") }
                        },
                    )
                }) { pad ->
                    LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                        items(videos) { v ->
                            ListItem(
                                headlineContent = { Text(v.title) },
                                supportingContent = { Text(formatDuration(v.durationMs)) },
                                modifier = Modifier.clickable { open(v.uri) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    private fun open(uri: Uri) {
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }
}
