// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.AlertType
import java.io.File

/**
 * Module 3: a torrent download manager built on libtorrent4j (FOSS), with the
 * option to play a download while it is still running.
 *
 * This used to hold exactly one TorrentHandle and existed only to feed the
 * player. It is now a proper downloader: several torrents at once, each with
 * its own progress, pause/resume and removal, and a sequential-download toggle
 * that is only turned on for the one you choose to stream.
 *
 * Sequential download is what makes streaming possible (pieces arrive in play
 * order) but it is slower overall, so downloads default to normal order and
 * only switch when you ask to play.
 */
class TorrentEngine(private val downloadDir: File) {

    /** A snapshot of one download, for the UI. */
    data class Item(
        val id: String,                 // info hash
        val name: String,
        val progress: Float,            // 0..1
        val downloadRate: Int,          // bytes/sec
        val uploadRate: Int,
        val peers: Int,
        val totalBytes: Long,
        val paused: Boolean,
        val finished: Boolean,
        val hasMetadata: Boolean,
        val sequential: Boolean,
    )

    private val session = SessionManager()
    private val handles = linkedMapOf<String, TorrentHandle>()
    // We drive both transitions, so tracking them here is simpler and more
    // portable than decoding torrent_flags_t, whose helper methods vary
    // between libtorrent4j builds.
    private val pausedIds = mutableSetOf<String>()
    @Volatile private var streamingId: String? = null

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    fun start() {
        if (!session.isRunning) {
            session.start()
            session.addListener(object : AlertListener {
                override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig())
                override fun alert(alert: Alert<*>) {
                    if (alert !is AddTorrentAlert) return
                    val h = alert.handle() ?: return
                    val id = h.infoHash().toHex()
                    synchronized(handles) { handles[id] = h }
                    h.resume()
                    refresh()
                }
            })
        }
    }

    fun stop() {
        synchronized(handles) {
            handles.values.forEach { runCatching { session.remove(it) } }
            handles.clear()
        }
        if (session.isRunning) session.stop()
    }

    /** Queue a magnet. Normal (non-sequential) order: this is a download. */
    fun add(magnet: String) {
        // The magnet overload requires the flags argument.
        runCatching { session.download(magnet, downloadDir, TorrentFlags.AUTO_MANAGED) }
    }

    fun pause(id: String) = withHandle(id) {
        it.pause()
        synchronized(handles) { pausedIds += id }
        refresh()
    }

    fun resume(id: String) = withHandle(id) {
        it.resume()
        synchronized(handles) { pausedIds -= id }
        refresh()
    }

    fun remove(id: String, deleteFiles: Boolean) {
        val h = synchronized(handles) { pausedIds -= id; handles.remove(id) } ?: return
        if (streamingId == id) streamingId = null
        runCatching {
            if (deleteFiles) session.remove(h, org.libtorrent4j.swig.session_handle.delete_files)
            else session.remove(h)
        }
        refresh()
    }

    /**
     * Switch one torrent to sequential order so it can be streamed. Only one
     * at a time: sequential costs throughput, so the others stay normal.
     */
    fun setStreaming(id: String) {
        synchronized(handles) {
            handles.forEach { (key, h) ->
                runCatching {
                    if (key == id) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                }
            }
            streamingId = id
            run {
                run {
                }
            }
        }
        refresh()
    }

    /**
     * Prioritise pieces around the playback point so seeking mid-stream stays
     * responsive. Call as the position moves.
     */
    fun prioritizeAround(id: String, playedFraction: Double) = withHandle(id) { h ->
        val ti = h.torrentFile() ?: return@withHandle
        val numPieces = ti.numPieces()
        val from = (numPieces * playedFraction).toInt().coerceIn(0, numPieces - 1)
        val window = 24 // pieces to race ahead of the playhead
        for (i in from until minOf(from + window, numPieces)) {
            h.piecePriority(i, Priority.TOP_PRIORITY)
            h.setPieceDeadline(i, (i - from) * 1000)
        }
    }

    fun hasMetadata(id: String): Boolean = handle(id)?.torrentFile() != null

    /**
     * True once enough of the start of the file is on disk to begin playback:
     * mpv needs a header plus some initial frames.
     */
    fun readyToStartPlayback(id: String, minLeadingPieces: Int = 6): Boolean {
        val h = handle(id) ?: return false
        val ti = h.torrentFile() ?: return false
        val n = minOf(minLeadingPieces, ti.numPieces())
        for (i in 0 until n) if (!h.havePiece(i)) return false
        return true
    }

    /** Largest file in the torrent -- usually the video worth playing. */
    fun primaryVideoPath(id: String): File? {
        val h = handle(id) ?: return null
        val ti = h.torrentFile() ?: return null
        var bestIdx = 0
        var bestLen = -1L
        for (i in 0 until ti.numFiles()) {
            val len = ti.files().fileSize(i)
            if (len > bestLen) { bestLen = len; bestIdx = i }
        }
        return File(downloadDir, ti.files().filePath(bestIdx))
    }

    /** Every completed file of a torrent, for copying to the chosen folder. */
    fun filesOf(id: String): List<File> {
        val h = handle(id) ?: return emptyList()
        val ti = h.torrentFile() ?: return emptyList()
        return (0 until ti.numFiles()).map { File(downloadDir, ti.files().filePath(it)) }
    }

    /** Recomputes the snapshot list the UI observes. Cheap; poll it. */
    fun refresh() {
        val snapshot = synchronized(handles) { handles.toMap() }
        val pausedSnapshot = synchronized(handles) { pausedIds.toSet() }
        _items.value = snapshot.mapNotNull { (id, h) ->
            runCatching {
                val st = h.status() ?: return@runCatching null
                val ti = h.torrentFile()
                Item(
                    id = id,
                    name = st.name()?.takeIf { it.isNotBlank() } ?: "Fetching metadata…",
                    progress = st.progress(),
                    downloadRate = st.downloadPayloadRate(),
                    uploadRate = st.uploadPayloadRate(),
                    peers = st.numPeers(),
                    totalBytes = ti?.totalSize() ?: 0L,
                    paused = id in pausedSnapshot,
                    finished = st.isFinished,
                    hasMetadata = ti != null,
                    sequential = id == streamingId,
                )
            }.getOrNull()
        }
    }

    private fun handle(id: String): TorrentHandle? = synchronized(handles) { handles[id] }

    private inline fun withHandle(id: String, body: (TorrentHandle) -> Unit) {
        handle(id)?.let { runCatching { body(it) } }
    }
}
