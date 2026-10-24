// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mkodekar.openreel.torrent.TorrentEngine
import com.mkodekar.openreel.torrent.TorrentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Torrent downloads.
 *
 * This is a download manager rather than a player front-end: add a magnet, see
 * progress, pause, resume and remove, and choose where finished files are
 * saved. Playing is one action on a download, not the only thing you can do
 * with it -- and because libtorrent can hand over the start of a file early,
 * Play works before the download finishes.
 *
 * On where files land: libtorrent writes through native code, so it needs a
 * real filesystem path and cannot write into a Storage Access Framework tree.
 * Downloads therefore run in the app's own external directory, and finished
 * files are copied into the folder you pick. That keeps the destination free
 * under scoped storage without asking for broad storage permissions.
 */

/** One finished download, kept so you can see what you got and where. */
data class CompletedDownload(
    val name: String,
    val sizeBytes: Long,
    val savedTo: String,
    val at: Long,
)

/** Remembers the destination folder and the history of finished downloads. */
class TorrentPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("torrent", Context.MODE_PRIVATE)

    var destination: Uri?
        get() = prefs.getString(KEY_DEST, null)?.let(Uri::parse)
        set(v) = prefs.edit().putString(KEY_DEST, v?.toString()).apply()

    /**
     * Finished downloads, newest first. Stored as a small tab-separated list
     * rather than pulling in a JSON dependency for four fields.
     */
    var history: List<CompletedDownload>
        get() = prefs.getStringSet(KEY_HISTORY, emptySet()).orEmpty()
            .mapNotNull { row ->
                val p = row.split('	')
                if (p.size < 4) null
                else CompletedDownload(
                    name = p[0],
                    sizeBytes = p[1].toLongOrNull() ?: 0L,
                    savedTo = p[2],
                    at = p[3].toLongOrNull() ?: 0L,
                )
            }
            .sortedByDescending { it.at }
        set(v) = prefs.edit()
            .putStringSet(
                KEY_HISTORY,
                v.take(50).map { "${it.name}	${it.sizeBytes}	${it.savedTo}	${it.at}" }.toSet(),
            )
            .apply()

    fun record(entry: CompletedDownload) {
        // Keyed by name so re-running a torrent does not duplicate the row.
        history = (listOf(entry) + history.filterNot { it.name == entry.name })
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    private companion object {
        const val KEY_DEST = "destination_tree"
        const val KEY_HISTORY = "history"
    }
}

@Composable
fun TorrentScreen(
    onPlay: (String) -> Unit,
    /** A magnet, or the URI of a .torrent, opened from outside the app. */
    pending: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { TorrentPrefs(context) }

    var magnet by remember { mutableStateOf("") }
    var service by remember { mutableStateOf<TorrentService?>(null) }
    var destination by remember { mutableStateOf(prefs.destination) }
    var note by remember { mutableStateOf<String?>(null) }
    val exported = remember { mutableStateMapOf<String, Boolean>() }
    var history by remember { mutableStateOf(prefs.history) }

    val items by (service?.engine?.items ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<TorrentEngine.Item>()) })
        .collectAsState()

    // Folder picker. Persist the grant so it survives a restart.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        destination = uri
        prefs.destination = uri
    }

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

    // Copy finished downloads into the chosen folder, once each.
    LaunchedEffect(items, destination) {
        val svc = service ?: return@LaunchedEffect
        val dest = destination ?: return@LaunchedEffect
        items.filter { it.finished && exported[it.id] != true }.forEach { item ->
            exported[item.id] = true
            val files = svc.engine.filesOf(item.id)
            val copied = withContext(Dispatchers.IO) { exportFiles(context, dest, files) }
            val where = folderLabel(context, dest)
            if (copied > 0) {
                prefs.record(
                    CompletedDownload(
                        name = item.name,
                        sizeBytes = item.totalBytes,
                        savedTo = where,
                        at = System.currentTimeMillis(),
                    )
                )
                history = prefs.history
                note = "Saved ${item.name} to $where"
            }
        }
    }

    // Something was opened from outside: queue it once the service is bound.
    var consumed by remember { mutableStateOf(false) }
    LaunchedEffect(pending, service) {
        val svc = service ?: return@LaunchedEffect
        val uri = pending ?: return@LaunchedEffect
        if (consumed) return@LaunchedEffect
        consumed = true
        startService(context)
        if (uri.startsWith("magnet:", true)) {
            svc.add(uri)
            note = "Added from link. Fetching metadata…"
        } else {
            // A .torrent arrives as a content:// URI we cannot hand to native
            // code, so copy it somewhere libtorrent can read.
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "incoming.torrent")
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { ins ->
                        tmp.outputStream().use { ins.copyTo(it) }
                    } ?: return@runCatching false
                    svc.engine.addTorrentFile(tmp)
                }.getOrDefault(false)
            }
            note = if (ok) "Added torrent file." else "Could not read that torrent file."
        }
    }

    val valid = magnet.startsWith("magnet:", ignoreCase = true)

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {

        MxSectionLabel("Save finished downloads to")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MxText(
                    destination?.let { folderLabel(context, it) } ?: "Not set",
                    Mx.type.mediumPrimary,
                    maxLines = 1,
                )
                MxText(
                    if (destination == null)
                        "Pick a folder or finished files stay inside the app"
                    else
                        "Finished files are copied here",
                    Mx.type.secondary,
                    Mx.colors.textSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            MxButton(if (destination == null) "Choose" else "Change", filled = destination == null) {
                runCatching { pickFolder.launch(null) }
            }
        }

        if (!isUnrestricted(context)) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MxText("Allow background downloading", Mx.type.mediumPrimary)
                    MxText(
                        "Android may pause downloads to save battery",
                        Mx.type.secondary,
                        Mx.colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                MxButton("Allow") { requestUnrestricted(context) }
            }
        }

        MxSectionLabel("Add a magnet")
        MxField(
            value = magnet,
            onValueChange = { magnet = it },
            placeholder = "magnet:?xt=urn:btih:…",
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(12.dp))
        MxButton("Download", enabled = valid) {
            startService(context)
            service?.add(magnet)
            magnet = ""
            note = "Added. Fetching metadata…"
        }

        // The service already polls on its own thread; nothing to do here.
        // (A second poll from the composition would be a main-thread call
        // into native libtorrent, which is exactly what crashed before.)

        Spacer(Modifier.height(6.dp))
        if (items.isEmpty() && history.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                MxText("No downloads yet.", Mx.type.primary, Mx.colors.textSecondary)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (items.isNotEmpty()) {
                    item { MxSectionLabel("Active") }
                }
                items(items, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        onPause = { service?.engine?.pause(item.id) },
                        onResume = { service?.engine?.resume(item.id) },
                        onRemove = { service?.engine?.remove(item.id, deleteFiles = false) },
                        onPlay = {
                            val svc = service ?: return@DownloadRow
                            scope.launch {
                                svc.engine.setStreaming(item.id)
                                note = "Buffering the start of ${item.name}…"
                                while (!(svc.engine.hasMetadata(item.id) &&
                                        svc.engine.readyToStartPlayback(item.id))
                                ) delay(500)
                                val f = svc.engine.primaryVideoPath(item.id)
                                if (f != null) { note = null; onPlay("file://${f.absolutePath}") }
                            }
                        },
                    )
                }

                if (history.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { MxSectionLabel("Completed") }
                            MxButton("Clear", filled = false) {
                                prefs.clearHistory(); history = emptyList()
                            }
                        }
                    }
                    items(history, key = { it.name + it.at }) { done ->
                        CompletedRow(done)
                    }
                }
            }
        }

        note?.let {
            MxText(it, Mx.type.secondary, Mx.colors.textSecondary)
            Spacer(Modifier.height(6.dp))
        }
        MxText(
            "Only download content you are legally entitled to.",
            Mx.type.secondary,
            Mx.colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** A finished download: what it was, how big, and where it ended up. */
@Composable
private fun CompletedRow(done: CompletedDownload) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        MxText(done.name, Mx.type.mediumPrimary, maxLines = 2)
        Spacer(Modifier.height(3.dp))
        MxText(
            listOf(
                formatBytes(done.sizeBytes).takeIf { done.sizeBytes > 0 },
                "Saved to ${done.savedTo}",
                relativeTime(done.at),
            ).filterNotNull().joinToString("  ·  "),
            Mx.type.secondary,
            Mx.colors.textSecondary,
            maxLines = 2,
        )
    }
    MxDivider()
}

/** "just now" / "12m ago" / "3h ago" / "2d ago" */
private fun relativeTime(at: Long): String {
    if (at <= 0) return ""
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

@Composable
private fun DownloadRow(
    item: TorrentEngine.Item,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MxText(item.name, Mx.type.mediumPrimary, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                MxText(statusLine(item), Mx.type.secondary, Mx.colors.textSecondary, maxLines = 1)
            }
            if (item.sequential) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Mx.colors.accent.copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) { MxText("STREAMING", Mx.type.tag, Mx.colors.accent) }
            }
        }

        Spacer(Modifier.height(8.dp))
        MxProgress(item.progress, height = 4.dp)
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Play works before completion: libtorrent can deliver the start
            // of the file first once the torrent is in sequential order.
            if (item.hasMetadata) {
                MxButton("Play", filled = false, onClick = onPlay)
            }
            if (item.paused) MxButton("Resume", filled = false, onClick = onResume)
            else if (!item.finished) MxButton("Pause", filled = false, onClick = onPause)
            Spacer(Modifier.weight(1f))
            MxButton("Remove", filled = false, onClick = onRemove)
        }
    }
    MxDivider()
}

private fun statusLine(item: TorrentEngine.Item): String {
    if (!item.hasMetadata) return "Fetching metadata…"
    val pct = "${(item.progress * 100).toInt()}%"
    val size = if (item.totalBytes > 0) formatBytes(item.totalBytes) else ""
    val rate = when {
        item.finished -> "Done"
        item.paused -> "Paused"
        else -> "${formatBytes(item.downloadRate.toLong())}/s"
    }
    return listOf(pct, size, rate, "${item.peers} peers")
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
    b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
    b > 0 -> "%.0f KB".format(b.toDouble() / (1L shl 10))
    else -> "0"
}

/** Starts the download service in the foreground so it survives backgrounding. */
private fun startService(context: Context) {
    val intent = Intent(context, TorrentService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

/** True when the system will not throttle our background work. */
private fun isUnrestricted(context: Context): Boolean = runCatching {
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    pm?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(true)

/**
 * Ask to be exempted from battery optimisation. Sends the user to the system
 * screen; Android gives no way to grant this silently.
 */
private fun requestUnrestricted(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }
}

/** Readable name for the picked tree, falling back to the raw last segment. */
private fun folderLabel(context: Context, tree: Uri): String =
    runCatching { DocumentFile.fromTreeUri(context, tree)?.name }.getOrNull()
        ?: tree.lastPathSegment?.substringAfterLast(':')?.ifBlank { "Chosen folder" }
        ?: "Chosen folder"

/**
 * Copy finished files into the user's folder. Returns how many were written.
 * Existing names are left alone rather than overwritten.
 */
private fun exportFiles(context: Context, tree: Uri, files: List<File>): Int {
    val dir = DocumentFile.fromTreeUri(context, tree) ?: return 0
    var count = 0
    files.filter { it.isFile && it.length() > 0 }.forEach { src ->
        if (dir.findFile(src.name) != null) return@forEach
        val mime = when (src.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
        val out = dir.createFile(mime, src.name) ?: return@forEach
        runCatching {
            context.contentResolver.openOutputStream(out.uri)?.use { os ->
                src.inputStream().use { it.copyTo(os) }
            }
            count++
        }
    }
    return count
}
