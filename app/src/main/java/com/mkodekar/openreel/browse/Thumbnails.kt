// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.browse

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Video thumbnails for the browse list, with an in-memory cache.
 *
 * MX shows a real frame in both its list rows and grid cells; placeholders are
 * most of what made our list read as "not MX". Two paths are needed because
 * minSdk is 26:
 *
 *   API 29+  ContentResolver.loadThumbnail -- uses MediaStore's own cached
 *            thumbnail where one exists, so it is much cheaper.
 *   API 26-28  MediaMetadataRetriever.getFrameAtTime on a seek-friendly
 *            offset, which decodes a frame itself.
 *
 * The cache is sized from the app's available heap rather than a fixed count,
 * so a long folder does not push the process into an OOM.
 */
object Thumbnails {

    /** Roughly an eighth of the available heap, in KiB. */
    private val cache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        object : LruCache<String, Bitmap>(maxKb / 8) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }

    /** Non-blocking peek, so a cached frame paints on the first composition. */
    fun cached(id: Long): Bitmap? = cache.get(id.toString())

    /** Marks an id as having no recoverable frame, so we stop retrying it. */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    suspend fun load(context: Context, item: VideoItem, target: Size): Bitmap? {
        val key = item.id.toString()
        cache.get(key)?.let { return it }
        if (item.id in failed) return null

        val bmp = withContext(Dispatchers.IO) {
            runCatching { extract(context, item, target) }.getOrNull()
        }
        if (bmp == null) {
            failed += item.id
            return null
        }
        cache.put(key, bmp)
        return bmp
    }

    private fun extract(context: Context, item: VideoItem, target: Size): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.contentResolver.loadThumbnail(item.uri, target, null) }
                .getOrNull() ?: retrieveFrame(context, item.uri)
        } else {
            legacyThumbnail(context, item.id) ?: retrieveFrame(context, item.uri)
        }

    /** MediaStore's own thumbnail table, the only cheap option before API 29. */
    @Suppress("DEPRECATION")
    private fun legacyThumbnail(context: Context, id: Long): Bitmap? =
        runCatching {
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null,
            )
        }.getOrNull()

    /**
     * Decode a frame ourselves. Seeks a little way in: the very first frame of
     * a lot of files is black, which looks like a broken thumbnail.
     */
    private fun retrieveFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val atUs = if (durationMs > 4000) 2_000_000L else durationMs * 500
            retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close()
                else retriever.release()
            }
        }
    }
}
