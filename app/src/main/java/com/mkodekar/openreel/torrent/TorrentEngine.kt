package com.mkodekar.openreel.torrent

import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.AddTorrentAlert
import java.io.File

/**
 * Module 3: torrent download + stream-while-downloading, on libtorrent4j (FOSS).
 *
 * "Stream while downloading" = sequential download + deadline-prioritising the
 * pieces right after the current playback position, so the start of the video is
 * fetched first and the player can open it before the torrent finishes.
 */
class TorrentEngine(private val downloadDir: File) {

    private val session = SessionManager()
    @Volatile private var handle: TorrentHandle? = null

    fun start() {
        if (!session.isRunning) session.start()
    }

    fun stop() {
        handle?.let { session.remove(it) }
        if (session.isRunning) session.stop()
    }

    /** Add a magnet link and begin a sequential (streamable) download. */
    fun addMagnet(magnet: String, onReady: (TorrentHandle) -> Unit) {
        session.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig())
            override fun alert(alert: Alert<*>) {
                if (alert is AddTorrentAlert) {
                    val h = alert.handle()
                    h.setFlags(h.flags().or_(org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD))
                    h.resume()
                    handle = h
                    onReady(h)
                }
            }
        })
        session.download(magnet, downloadDir)
    }

    /**
     * Prioritise the pieces around the user's current playback point so seeking
     * mid-stream stays responsive. Call this as playback position moves.
     */
    fun prioritizeAround(fileIndex: Int, playedFraction: Double) {
        val h = handle ?: return
        val ti = h.torrentFile() ?: return
        val numPieces = ti.numPieces()
        val from = (numPieces * playedFraction).toInt().coerceIn(0, numPieces - 1)
        val window = 24 // pieces to race ahead of the playhead
        for (i in from until minOf(from + window, numPieces)) {
            h.piecePriority(i, Priority.TOP_PRIORITY)
            h.setPieceDeadline(i, (i - from) * 1000)
        }
    }

    fun progress(): Float = handle?.status()?.progress() ?: 0f
    fun downloadRate(): Int = handle?.status()?.downloadPayloadRate() ?: 0

    /** Largest file in the torrent -- usually the video to hand to the player. */
    fun primaryVideoPath(): File? {
        val h = handle ?: return null
        val ti = h.torrentFile() ?: return null
        var bestIdx = 0; var bestLen = -1L
        for (i in 0 until ti.numFiles()) {
            val len = ti.files().fileSize(i)
            if (len > bestLen) { bestLen = len; bestIdx = i }
        }
        return File(downloadDir, ti.files().filePath(bestIdx))
    }
}
