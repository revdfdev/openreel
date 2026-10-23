package com.mkodekar.openreel.browse

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** A local video the browse screen can list and open. */
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val relativePath: String,
)

/**
 * Lists local videos through MediaStore (scoped storage -- no MANAGE_EXTERNAL_STORAGE).
 * This is module 1's "browse" surface: folder/flat list of what's on the device.
 */
class MediaRepository(private val context: Context) {

    fun queryVideos(): List<VideoItem> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val out = ArrayList<VideoItem>()

        context.contentResolver.query(collection, projection, null, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += VideoItem(
                    id = id,
                    uri = Uri.withAppendedPath(collection, id.toString()),
                    title = c.getString(nameCol) ?: "",
                    durationMs = c.getLong(durCol),
                    sizeBytes = c.getLong(sizeCol),
                    relativePath = c.getString(pathCol) ?: "",
                )
            }
        }
        return out
    }

    /** Group by containing folder, like MX Player's folder view. */
    fun queryFolders(): Map<String, List<VideoItem>> =
        queryVideos().groupBy { it.relativePath.trimEnd('/') }
}
