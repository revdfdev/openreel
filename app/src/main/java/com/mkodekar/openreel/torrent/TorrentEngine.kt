// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
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
        val doneBytes: Long,
        val paused: Boolean,
        val finished: Boolean,
        val hasMetadata: Boolean,
        val sequential: Boolean,
    )

    private val session = SessionManager()
    /**
     * Info hashes, not TorrentHandles.
     *
     * A cached TorrentHandle can outlive the native torrent_handle it wraps.
     * Calling status() on one then dereferences a dead weak_ptr and takes the
     * whole process down with a SIGSEGV inside libtorrent -- and because that
     * is a native fault, no runCatching around it will save you. Looking the
     * handle up through the session each time, and checking is_valid(), is
     * the only safe way to touch it.
     */
    private val hashes = linkedMapOf<String, Sha1Hash>()
    // We drive both transitions, so tracking them here is simpler and more
    // portable than decoding torrent_flags_t, whose helper methods vary
    // between libtorrent4j builds.
    private val pausedIds = mutableSetOf<String>()
    @Volatile private var streamingId: String? = null

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    /**
     * Stop sharing a torrent once it finishes. Seeding is what keeps the
     * radio and CPU busy long after the download is done, which is the main
     * battery cost of a torrent client; LibreTorrent defaults the same way.
     */
    var stopSeedingWhenDone: Boolean = true

    /** Pauses every download without tearing the session down. */
    fun pauseAll() {
        synchronized(hashes) { hashes.keys.toList() }.forEach { id ->
            withHandle(id) { it.pause() }
            synchronized(hashes) { pausedIds += id }
        }
        refresh()
    }

    /** Resumes everything that was paused by [pauseAll]. */
    fun resumeAll() {
        synchronized(hashes) { hashes.keys.toList() }.forEach { id ->
            withHandle(id) { it.resume() }
            synchronized(hashes) { pausedIds -= id }
        }
        refresh()
    }

    /** True when nothing is left to download. */
    fun allFinished(): Boolean =
        _items.value.let { it.isNotEmpty() && it.all { i -> i.finished } }

    fun start() {
        if (!session.isRunning) {
            session.start()
            session.addListener(object : AlertListener {
                override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig())
                override fun alert(alert: Alert<*>) {
                    if (alert !is AddTorrentAlert) return
                    val h = alert.handle() ?: return
                    val hash = h.infoHash()
                    val id = hash.toHex()
                    synchronized(hashes) { hashes[id] = hash }
                    h.resume()
                    refresh()
                }
            })
        }
    }

    fun stop() {
        val ids = synchronized(hashes) { hashes.keys.toList() }
        ids.forEach { id -> handle(id)?.let { runCatching { session.remove(it) } } }
        synchronized(hashes) { hashes.clear() }
        if (session.isRunning) session.stop()
    }

    /** Queue a magnet. Normal (non-sequential) order: this is a download. */
    fun add(magnet: String) {
        // The magnet overload requires the flags argument.
        runCatching { session.download(magnet, downloadDir, TorrentFlags.AUTO_MANAGED) }
    }

    /**
     * Queue a .torrent file. Unlike a magnet this already carries the
     * metadata, so the download starts without the metadata fetch.
     */
    fun addTorrentFile(file: File): Boolean = runCatching {
        val ti = org.libtorrent4j.TorrentInfo(file)
        session.download(ti, downloadDir)
        true
    }.getOrDefault(false)

    /** Aggregate progress across unfinished downloads, for the notification. */
    fun overallProgress(): Pair<Int, Int> {
        val list = _items.value.filter { !it.finished }
        if (list.isEmpty()) return 0 to 0
        val pct = (list.sumOf { it.progress.toDouble() } / list.size * 100).toInt()
        return list.size to pct
    }

    fun pause(id: String) = withHandle(id) {
        it.pause()
        synchronized(hashes) { pausedIds += id }
        refresh()
    }

    fun resume(id: String) = withHandle(id) {
        it.resume()
        synchronized(hashes) { pausedIds -= id }
        refresh()
    }

    fun remove(id: String, deleteFiles: Boolean) {
        val h = handle(id)
        synchronized(hashes) { pausedIds -= id; hashes.remove(id) }
        if (streamingId == id) streamingId = null
        if (h == null) { refresh(); return }
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
        val ids = synchronized(hashes) { hashes.keys.toList() }
        ids.forEach { key ->
            withHandle(key) { h ->
                if (key == id) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }
        }
        streamingId = id
        run {
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

    /**
     * Recomputes the snapshot the UI observes. Safe to poll, but call it off
     * the main thread: every entry crosses into native code.
     */
    fun refresh() {
        if (!session.isRunning) { _items.value = emptyList(); return }
        val ids = synchronized(hashes) { hashes.keys.toList() }
        val pausedSnapshot = synchronized(hashes) { pausedIds.toSet() }

        val live = ids.mapNotNull { id ->
            val h = handle(id) ?: return@mapNotNull null
            runCatching {
                val st = h.status() ?: return@runCatching null
                val ti = runCatching { h.torrentFile() }.getOrNull()
                Item(
                    id = id,
                    name = st.name()?.takeIf { it.isNotBlank() }
                        ?: ti?.name()?.takeIf { it.isNotBlank() }
                        ?: "Fetching metadata…",
                    progress = st.progress(),
                    downloadRate = st.downloadPayloadRate(),
                    uploadRate = st.uploadPayloadRate(),
                    peers = st.numPeers(),
                    totalBytes = ti?.totalSize() ?: 0L,
                    doneBytes = st.totalDone(),
                    paused = id in pausedSnapshot,
                    finished = st.isFinished,
                    hasMetadata = ti != null,
                    sequential = id == streamingId,
                )
            }.getOrNull()
        }
        _items.value = live

        // Finished and still sharing: pause it so the radio can sleep.
        if (stopSeedingWhenDone) {
            live.filter { it.finished && !it.paused }.forEach { done ->
                withHandle(done.id) { it.pause() }
                synchronized(hashes) { pausedIds += done.id }
            }
        }
    }

    /**
     * A handle that is safe to call into right now, or null. Never cache the
     * result: it is only good for the call you are about to make.
     */
    private fun handle(id: String): TorrentHandle? {
        if (!session.isRunning) return null
        val hash = synchronized(hashes) { hashes[id] } ?: return null
        return runCatching {
            session.find(hash)?.takeIf { it.swig()?.is_valid() == true && it.swig().in_session() }
        }.getOrNull()
    }

    private inline fun withHandle(id: String, body: (TorrentHandle) -> Unit) {
        handle(id)?.let { runCatching { body(it) } }
    }
}
