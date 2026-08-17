package dev.cloudwalk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny SharedPreferences-backed user collection for the free/public mode. */
class LocalCollections(context: Context) {
    private val prefs = context.getSharedPreferences("cloudwalk_collections", Context.MODE_PRIVATE)
    private val lock = Any()
    @Volatile private var likesCache: List<Track>? = null
    @Volatile private var recentCache: List<Track>? = null
    @Volatile private var queueCache: List<Track>? = null

    fun likes(): List<Track> = likesCache ?: synchronized(lock) {
        likesCache ?: read("likes").also { likesCache = it }
    }

    fun recent(): List<Track> = recentCache ?: synchronized(lock) {
        recentCache ?: read("recent").also { recentCache = it }
    }

    fun queue(): List<Track> = queueCache ?: synchronized(lock) {
        queueCache ?: read("queue").also { queueCache = it }
    }

    fun saveQueue(tracks: List<Track>) = synchronized(lock) {
        val snapshot = tracks.take(MAX_QUEUE).toList()
        queueCache = snapshot
        write("queue", snapshot)
    }

    fun isLiked(track: Track): Boolean = likes().any { it.id == track.id }

    fun importLikes(tracks: List<Track>): Int = synchronized(lock) {
        if (tracks.isEmpty()) return@synchronized 0
        val before = likes()
        val existing = before.asSequence().map { it.id }.toHashSet()
        val added = tracks.count { existing.add(it.id) }
        val merged = ArrayList<Track>(minOf(MAX_LIKES, tracks.size + before.size))
        val seen = HashSet<String>()
        (tracks + before).forEach { track ->
            if (merged.size < MAX_LIKES && seen.add(track.id)) merged += track
        }
        likesCache = merged
        write("likes", merged)
        added
    }

    fun toggleLike(track: Track): Boolean = synchronized(lock) {
        val current = likes().toMutableList()
        val existing = current.indexOfFirst { it.id == track.id }
        val liked = existing < 0
        if (liked) current.add(0, track) else current.removeAt(existing)
        val snapshot = current.take(MAX_LIKES)
        likesCache = snapshot
        write("likes", snapshot)
        liked
    }

    fun addRecent(track: Track) = synchronized(lock) {
        val current = recent().filterNot { it.id == track.id }.toMutableList()
        current.add(0, track)
        val snapshot = current.take(MAX_RECENT)
        recentCache = snapshot
        write("recent", snapshot)
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
        return out.toList()
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
        private const val MAX_QUEUE = 120
    }
}
