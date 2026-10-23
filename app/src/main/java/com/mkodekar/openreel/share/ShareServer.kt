// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.share

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Module 4: device-to-device share (the "MX Transfer / ShareIt" idea), done the
 * FOSS way (same model as LocalSend):
 *
 *   sender runs this local HTTP server over the shared Wi-Fi / Wi-Fi Direct group
 *   and shows a QR code encoding  http://<ip>:<port>/  ; the receiver scans it and
 *   pulls the file. No cloud, no account, no proprietary SDK.
 *
 * This handles the serving half. Peer discovery (Wi-Fi Direct group formation) and
 * QR scan/generate live in SharePairing (ZXing) -- see README.
 */
class ShareServer(
    port: Int,
    private val offered: List<File>,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri == "/index") return manifest()

        val idx = uri.removePrefix("/file/").toIntOrNull()
        val file = idx?.let { offered.getOrNull(it) }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no such file")

        return newChunkedResponse(
            Response.Status.OK,
            guessMime(file.name),
            FileInputStream(file),
        ).apply { addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"") }
    }

    /** Simple JSON list the receiver app reads to know what's on offer. */
    private fun manifest(): Response {
        val json = offered.mapIndexed { i, f ->
            """{"index":$i,"name":"${f.name}","size":${f.length()}}"""
        }.joinToString(",", prefix = "[", postfix = "]")
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun guessMime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp4", "mkv", "webm" -> "video/*"
        "mp3", "aac", "flac", "m4a" -> "audio/*"
        else -> "application/octet-stream"
    }
}
