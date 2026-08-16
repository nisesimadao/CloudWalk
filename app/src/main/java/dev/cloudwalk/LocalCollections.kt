package dev.cloudwalk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny SharedPreferences-backed user collection for the free/public mode. */
class LocalCollections(context: Context) {
    private val prefs = context.getSharedPreferences("cloudwalk_collections", Context.MODE_PRIVATE)

    fun likes(): List<Track> = read("likes")
    fun recent(): List<Track> = read("recent")

    fun isLiked(track: Track): Boolean = likes().any { it.id == track.id }

    fun toggleLike(track: Track): Boolean {
        val current = likes().toMutableList()
        val existing = current.indexOfFirst { it.id == track.id }
        val liked = existing < 0
        if (liked) current.add(0, track) else current.removeAt(existing)
        write("likes", current.take(MAX_LIKES))
        return liked
    }

    fun addRecent(track: Track) {
        val current = recent().filterNot { it.id == track.id }.toMutableList()
        current.add(0, track)
        write("recent", current.take(MAX_RECENT))
    }

    private fun read(key: String): List<Track> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<Track>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            out += Track(
                id = id,
                title = o.optString("title", "Untitled"),
                artist = o.optString("artist", "Unknown"),
                album = o.optString("album").ifBlank { null },
                artworkUrl = o.optString("artwork").ifBlank { null },
                durationMs = o.optLong("duration", 0L),
                permalinkUrl = o.optString("permalink").ifBlank { null },
                streamUrl = o.optString("stream").ifBlank { null },
                localUri = o.optString("local").ifBlank { null }
            )
        }
        return out
    }

    private fun write(key: String, tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { t ->
            array.put(JSONObject()
                .put("id", t.id)
                .put("title", t.title)
                .put("artist", t.artist)
                .put("album", t.album)
                .put("artwork", t.artworkUrl)
                .put("duration", t.durationMs)
                .put("permalink", t.permalinkUrl)
                .put("stream", t.streamUrl)
                .put("local", t.localUri))
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    companion object {
        private const val MAX_LIKES = 300
        private const val MAX_RECENT = 50
    }
}
