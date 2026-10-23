package com.mkodekar.openreel.share

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Receiver side: read the sender's manifest and pull files over the local network. */
object ShareClient {

    data class Offered(val index: Int, val name: String, val size: Long)

    /** GET baseUrl -> parse the JSON list produced by ShareServer.manifest(). */
    fun fetchManifest(baseUrl: String): List<Offered> {
        val text = (URL(baseUrl).openConnection() as HttpURLConnection).run {
            connectTimeout = 5000; readTimeout = 5000
            inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
        }
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Offered(o.getInt("index"), o.getString("name"), o.getLong("size"))
        }
    }

    /** Download one offered file to [destDir]; returns the written file. */
    fun download(baseUrl: String, item: Offered, destDir: File, onProgress: (Float) -> Unit = {}): File {
        val conn = URL("${baseUrl.trimEnd('/')}/file/${item.index}").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        val out = File(destDir, item.name)
        conn.inputStream.use { input ->
            out.outputStream.use { output ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    output.write(buf, 0, n); total += n
                    if (item.size > 0) onProgress(total.toFloat() / item.size)
                }
            }
        }
        conn.disconnect()
        return out
    }
}
