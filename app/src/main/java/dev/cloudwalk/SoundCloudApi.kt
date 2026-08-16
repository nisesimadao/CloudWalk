package dev.cloudwalk

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal SoundCloud API client for old Android devices.
 * No Retrofit/OkHttp/JSON dependency: HttpURLConnection + org.json only.
 */
class SoundCloudApi(
    private val accessTokenProvider: () -> String?,
    private val refreshAccessToken: (() -> String?)? = null
) {
    private val baseUrl = "https://api.soundcloud.com"

    fun searchTracks(query: String, limit: Int = 30): List<Track> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val body = get("$baseUrl/tracks?q=$q&access=playable&limit=$limit&linked_partitioning=true")
        return parseTrackCollection(JSONObject(body))
    }

    fun getTrack(trackUrn: String): Track {
        val encoded = URLEncoder.encode(trackUrn, StandardCharsets.UTF_8.name())
        return parseTrack(JSONObject(get("$baseUrl/tracks/$encoded")))
    }

    fun likedTracks(limit: Int = 50): List<Track> {
        val body = get("$baseUrl/me/likes/tracks?limit=$limit&linked_partitioning=true")
        return parseTrackCollection(JSONObject(body))
    }

    fun recentlyPlayed(limit: Int = 25): List<Track> {
        val body = get("$baseUrl/me/recently-played/tracks?limit=$limit&linked_partitioning=true")
        return parseTrackCollection(JSONObject(body))
    }

    fun playlists(limit: Int = 50): List<PlaylistSummary> {
        val body = get("$baseUrl/me/playlists?limit=$limit&linked_partitioning=true")
        return parsePlaylistCollection(JSONObject(body))
    }

    fun playlistTracks(playlistUrn: String, limit: Int = 100): List<Track> {
        val encoded = URLEncoder.encode(playlistUrn, StandardCharsets.UTF_8.name())
        val body = get("$baseUrl/playlists/$encoded/tracks?limit=$limit&linked_partitioning=true")
        return parseTrackCollection(JSONObject(body))
    }

    fun streamUrls(trackUrn: String): StreamUrls {
        val encoded = URLEncoder.encode(trackUrn, StandardCharsets.UTF_8.name())
        val json = JSONObject(get("$baseUrl/tracks/$encoded/streams"))
        return StreamUrls(
            hlsAac160 = json.optString("hls_aac_160_url").ifBlank { null },
            hlsAac96 = json.optString("hls_aac_96_url").ifBlank { null },
            progressiveMp3 = json.optString("http_mp3_128_url").ifBlank { null }
        )
    }

    private fun get(url: String): String = get(url, allowRefresh = true)

    private fun get(url: String, allowRefresh: Boolean): String {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw SoundCloudException(401, "SoundCloud account is not connected")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "OAuth $token")
            setRequestProperty("User-Agent", "CloudWalk/0.1 Android")
            useCaches = true
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use(BufferedReader::readText)
            }.orEmpty()
            if (code == 401 && allowRefresh && refreshAccessToken != null) {
                val refreshed = refreshAccessToken.invoke()
                if (!refreshed.isNullOrBlank()) return get(url, allowRefresh = false)
            }
            if (code !in 200..299) throw SoundCloudException(code, text.ifBlank { "HTTP $code" })
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTrackCollection(root: JSONObject): List<Track> {
        val array = root.optJSONArray("collection") ?: return emptyList()
        val out = ArrayList<Track>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(parseTrack(item))
        }
        return out
    }

    private fun parsePlaylistCollection(root: JSONObject): List<PlaylistSummary> {
        val array = root.optJSONArray("collection") ?: return emptyList()
        val out = ArrayList<PlaylistSummary>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(
                PlaylistSummary(
                    id = item.optString("urn").ifBlank { item.optLong("id").toString() },
                    title = item.optString("title", "Playlist"),
                    artworkUrl = item.optString("artwork_url").ifBlank { null },
                    trackCount = item.optInt("track_count", 0)
                )
            )
        }
        return out
    }

    private fun parseTrack(json: JSONObject): Track {
        val user = json.optJSONObject("user")
        val id = json.optString("urn").ifBlank { json.optLong("id").toString() }
        return Track(
            id = id,
            title = json.optString("title", "Untitled"),
            artist = user?.optString("username")?.ifBlank { null }
                ?: json.optString("publisher_metadata").ifBlank { "SoundCloud" },
            artworkUrl = json.optString("artwork_url").ifBlank { null },
            durationMs = json.optLong("duration", 0L),
            permalinkUrl = json.optString("permalink_url").ifBlank { null }
        )
    }
}

data class StreamUrls(
    val hlsAac160: String?,
    val hlsAac96: String?,
    val progressiveMp3: String?
) {
    fun preferred(): String? = hlsAac160 ?: hlsAac96 ?: progressiveMp3
}

class SoundCloudException(
    val statusCode: Int,
    message: String
) : Exception(message)


data class PlaylistSummary(
    val id: String,
    val title: String,
    val artworkUrl: String?,
    val trackCount: Int
)
