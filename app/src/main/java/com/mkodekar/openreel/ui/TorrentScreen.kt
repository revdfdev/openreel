// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.torrent.TorrentService
import kotlinx.coroutines.delay

/**
 * Torrent tab: paste a magnet, watch it fetch metadata and buffer, then hand
 * the file to the player while it keeps downloading.
 *
 * Styled with the MX widgets, and bound to the real TorrentService -- the
 * numbers on screen come from TorrentEngine.progress()/downloadRate(), not
 * from a placeholder.
 */
@Composable
fun TorrentScreen(onPlay: (String) -> Unit) {
    val context = LocalContext.current
    var magnet by remember { mutableStateOf("") }
    var service by remember { mutableStateOf<TorrentService?>(null) }
    var started by remember { mutableStateOf(false) }

    // live engine stats
    var progress by remember { mutableFloatStateOf(0f) }
    var rateBytes by remember { mutableIntStateOf(0) }
    var hasMeta by remember { mutableStateOf(false) }
    var readyToPlay by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf<String?>(null) }

    val valid = magnet.startsWith("magnet:", ignoreCase = true)

    // Bind to the service while this tab is on screen.
    DisposableEffect(Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
                service = (b as? TorrentService.LocalBinder)?.service
            }
            override fun onServiceDisconnected(n: ComponentName?) { service = null }
        }
        val intent = Intent(context, TorrentService::class.java)
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        onDispose { runCatching { context.unbindService(conn) } }
    }

    // Poll the engine once started.
    LaunchedEffect(started, service) {
        if (!started) return@LaunchedEffect
        val svc = service ?: return@LaunchedEffect
        while (true) {
            val e = svc.engine
            progress = e.progress()
            rateBytes = e.downloadRate()
            hasMeta = e.hasMetadata()
            readyToPlay = hasMeta && e.readyToStartPlayback()
            fileName = e.primaryVideoPath()?.name
            delay(700)
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        MxSectionLabel("Magnet link")
        MxField(
            value = magnet,
            onValueChange = { magnet = it },
            placeholder = "magnet:?xt=urn:btih:…",
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            MxButton(if (started) "Restart" else "Start", enabled = valid) {
                val intent = Intent(context, TorrentService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                service?.stream(magnet) { }
                started = true
            }
            if (readyToPlay) {
                Spacer(Modifier.width(10.dp))
                MxButton("Play now", filled = false) { onPlay(magnet) }
            }
        }

        AnimatedVisibility(visible = started) {
            Column(Modifier.padding(top = 22.dp)) {
                // Stage indicator: metadata -> buffering -> ready.
                TorrentStage(
                    hasMeta = hasMeta,
                    readyToPlay = readyToPlay,
                )
                Spacer(Modifier.height(18.dp))

                fileName?.let {
                    MxText(it, Mx.type.mediumPrimary, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                }

                MxProgress(progress, height = 4.dp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    MxText(
                        "${(progress * 100).toInt()}%",
                        Mx.type.secondary,
                        Mx.colors.textSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    MxText(rateLabel(rateBytes), Mx.type.secondary, Mx.colors.textSecondary)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        MxText(
            "Only download content you are legally entitled to.",
            Mx.type.secondary,
            Mx.colors.textSecondary,
        )
    }
}

/** Three-step pipeline with the active step pulsing. */
@Composable
private fun TorrentStage(hasMeta: Boolean, readyToPlay: Boolean) {
    val step = when {
        readyToPlay -> 2
        hasMeta -> 1
        else -> 0
    }
    val labels = listOf("Fetching metadata", "Buffering header", "Ready to play")
    Column {
        labels.forEachIndexed { i, label ->
            val done = i < step
            val active = i == step && !(readyToPlay && i == 2)
            Row(
                Modifier.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageDot(done = done || (readyToPlay && i == 2), active = active)
                Spacer(Modifier.width(10.dp))
                MxText(
                    label,
                    Mx.type.secondary,
                    when {
                        done || (readyToPlay && i == 2) -> Mx.colors.accent
                        active -> Mx.colors.textPrimary
                        else -> Mx.colors.textSecondary
                    },
                )
            }
        }
    }
}

@Composable
private fun StageDot(done: Boolean, active: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "dot",
    )
    val size = 9.dp
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(if (active) size * scale else size * 0.78f)
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        done -> Mx.colors.accent
                        active -> Mx.colors.accent
                        else -> Mx.colors.surface
                    }
                )
        )
    }
}

private fun rateLabel(bytesPerSec: Int): String = when {
    bytesPerSec >= 1 shl 20 -> "%.1f MB/s".format(bytesPerSec.toDouble() / (1 shl 20))
    bytesPerSec > 0 -> "%.0f KB/s".format(bytesPerSec.toDouble() / (1 shl 10))
    else -> "—"
}
