package dev.cloudwalk

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalLibrary(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("cloudwalk_local", Context.MODE_PRIVATE)
    private val artDir = File(context.filesDir, "local_art").apply { mkdirs() }

    fun all(): List<Track> {
        val raw = prefs.getString("tracks", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<Track>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val uri = o.optString("uri").ifBlank { continue }
            out += Track(
                id = "local:$uri",
                title = o.optString("title", "Local track"),
                artist = o.optString("artist", "On device"),
                album = o.optString("album").ifBlank { null },
                artworkUrl = o.optString("artwork").ifBlank { null },
                durationMs = o.optLong("duration", 0L),
                localUri = uri
            )
        }
        return out
    }

    fun add(uri: Uri): Track {
        val retriever = MediaMetadataRetriever()
        val title: String
        val artist: String
        val album: String?
        val duration: Long
        val artworkUrl: String?
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment?.substringAfterLast('/') ?: appContext.getString(R.string.local_track))
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "On device"
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val picture = retriever.embeddedPicture
            artworkUrl = if (picture != null && picture.isNotEmpty()) {
                val file = File(artDir, "${uri.toString().hashCode().toUInt().toString(16)}.img")
                if (!file.exists() || file.length() == 0L) file.writeBytes(picture)
                "file://${file.absolutePath}"
            } else null
        } finally {
            retriever.release()
        }

        val existing = all().filterNot { it.localUri == uri.toString() }
        val track = Track(
            id = "local:${uri}",
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            durationMs = duration,
            localUri = uri.toString()
        )
        save(existing + track)
        return track
    }

    fun remove(track: Track) {
        track.artworkUrl?.takeIf { it.startsWith("file://") }?.removePrefix("file://")?.let { File(it).delete() }
        save(all().filterNot { it.id == track.id })
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
}