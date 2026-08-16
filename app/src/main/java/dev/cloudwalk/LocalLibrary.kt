package dev.cloudwalk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.max

class LocalLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("cloudwalk_local", Context.MODE_PRIVATE)
    private val artDir = File(appContext.filesDir, "local_art").apply { mkdirs() }

    fun all(): List<Track> {
        val raw = prefs.getString("tracks", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<Track>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val uri = o.optString("uri").ifBlank { continue }
            out += Track(
                id = "local:$uri",
                title = o.optString("title").ifBlank { appContext.getString(R.string.local_track) },
                artist = o.optString("artist").ifBlank { appContext.getString(R.string.on_device) },
                album = o.optString("album").ifBlank { null },
                artworkUrl = o.optString("artwork").ifBlank { null },
                durationMs = o.optLong("duration", 0L),
                localUri = uri
            )
        }
        return out
    }

    /** Parse multiple files and persist the library once. Call from a background thread. */
    fun addAll(uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        val existing = LinkedHashMap<String, Track>()
        all().forEach { track -> track.localUri?.let { existing[it] = track } }
        var added = 0
        for (uri in uris.distinct()) {
            runCatching { readTrack(uri) }.onSuccess { track ->
                val previous = existing[uri.toString()]
                if (previous?.artworkUrl != null && previous.artworkUrl != track.artworkUrl) {
                    deleteArtwork(previous.artworkUrl)
                }
                existing[uri.toString()] = track
                added++
            }
        }
        if (added > 0) save(existing.values.toList())
        return added
    }

    fun remove(track: Track) {
        deleteArtwork(track.artworkUrl)
        save(all().filterNot { it.id == track.id })
    }

    private fun readTrack(uri: Uri): Track {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val fileName = displayName(uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fileName?.substringBeforeLast('.', fileName)?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.local_track)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.on_device)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val artworkUrl = retriever.embeddedPicture
                ?.takeIf { it.isNotEmpty() }
                ?.let { saveArtwork(uri, it) }
            return Track(
                id = "local:$uri",
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                durationMs = duration,
                localUri = uri.toString()
            )
        } finally {
            retriever.release()
        }
    }


    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun deleteArtwork(url: String?) {
        url?.takeIf { it.startsWith("file://") }
            ?.removePrefix("file://")
            ?.let { File(it).delete() }
    }

    private fun saveArtwork(uri: Uri, bytes: ByteArray): String? {
        val file = File(artDir, "${uri.toString().hashCode().toUInt().toString(16)}.jpg")
        if (file.exists() && file.length() > 0L) return "file://${file.absolutePath}"

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= ARTWORK_MAX_PX) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scaled = if (max(decoded.width, decoded.height) > ARTWORK_MAX_PX) {
            val factor = ARTWORK_MAX_PX.toFloat() / max(decoded.width, decoded.height).toFloat()
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * factor).toInt().coerceAtLeast(1),
                (decoded.height * factor).toInt().coerceAtLeast(1),
                true
            )
        } else decoded
        return try {
            file.outputStream().buffered().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)) return null
            }
            "file://${file.absolutePath}"
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun save(items: List<Track>) {
        val array = JSONArray()
        items.forEach { track ->
            val uri = track.localUri ?: return@forEach
            array.put(
                JSONObject()
                    .put("uri", uri)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album)
                    .put("artwork", track.artworkUrl)
                    .put("duration", track.durationMs)
            )
        }
        prefs.edit().putString("tracks", array.toString()).apply()
    }

    companion object {
        private const val ARTWORK_MAX_PX = 512
    }
}
