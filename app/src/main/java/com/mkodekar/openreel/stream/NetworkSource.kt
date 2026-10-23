package com.mkodekar.openreel.stream

/**
 * Module 2: network streaming. libmpv opens these URL schemes directly, so this is
 * mostly validation + building the URL mpv expects. No extra decoding code needed.
 */
object NetworkSource {

    val supportedSchemes = setOf("http", "https", "rtsp", "rtmp", "ftp", "smb", "dav", "davs")

    fun isSupported(url: String): Boolean =
        supportedSchemes.any { url.startsWith("$it://", ignoreCase = true) }

    /**
     * SMB with credentials -> mpv's libsmb URL form:  smb://user:pass@host/share/path
     * (MX Player used libsmb2 for the same job; mpv/ffmpeg has it built in.)
     */
    fun smbUrl(host: String, share: String, path: String, user: String?, pass: String?): String {
        val creds = if (!user.isNullOrEmpty()) "${user}:${pass.orEmpty()}@" else ""
        return "smb://$creds$host/$share/${path.trimStart('/')}"
    }
}
