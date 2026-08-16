package dev.cloudwalk

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/** Public/unauthed SoundCloud web compatibility client. */
class WebSoundCloudApi(context: Context) {
    private val prefs = context.getSharedPreferences("cloudwalk_web", Context.MODE_PRIVATE)
    private val base = "https://api-v2.soundcloud.com"
    private val seedClientId = "UMY1dzQ68n2QbCuypNe8JOivmV2FO2Ep"

    fun searchTracks(query: String, limit: Int = 30): List<Track> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val body = withClientId { id ->
            get("$base/search/tracks?q=$q&client_id=$id&limit=$limit&offset=0&linked_partitioning=1&app_locale=en")
        }
        val arr = JSONObject(body).optJSONArray("collection") ?: return emptyList()
        Log.d(TAG, "search collection=${arr.length()}")
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::parseTrack)?.let(::add)
        }
    }

    fun resolvePublicStream(transcodingEndpoint: String): String {
        val separator = if ('?' in transcodingEndpoint) '&' else '?'
        val body = withClientId { id -> get("$transcodingEndpoint${separator}client_id=$id") }
        return JSONObject(body).getString("url")
    }

    fun resolveTrackUrl(inputUrl: String): Track? {
        val soundCloudUrl = if (isShortUrl(inputUrl)) resolveRedirect(inputUrl) else inputUrl
        val encoded = URLEncoder.encode(soundCloudUrl, StandardCharsets.UTF_8.name())
        val body = withClientId { id -> get("$base/resolve?url=$encoded&client_id=$id") }
        val item = JSONObject(body)
        if (item.optString("kind") != "track") return null
        return parseTrack(item)?.let { track ->
            if (track.permalinkUrl.isNullOrBlank()) track.copy(permalinkUrl = soundCloudUrl) else track
        }
    }

    fun relatedTracks(track: Track, limit: Int = 30): List<Track> {
        val numericId = track.id.substringAfterLast(':').toLongOrNull() ?: return emptyList()
        val body = withClientId { id ->
            get("$base/tracks/$numericId/related?client_id=$id&limit=$limit&offset=0&linked_partitioning=1")
        }
        val root = JSONObject(body)
        val arr = root.optJSONArray("collection") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::parseTrack)?.let(::add)
        }
    }

    private fun parseTrack(item: JSONObject): Track? {
        if (item.optString("kind").let { it.isNotBlank() && it != "track" }) return null
        val transcodings = item.optJSONObject("media")?.optJSONArray("transcodings")
        var progressive: String? = null
        var hls: String? = null
        if (transcodings != null) {
            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
                val endpoint = t.optString("url").ifBlank { null } ?: continue
                when (t.optJSONObject("format")?.optString("protocol")) {
                    "progressive" -> if (progressive == null) progressive = endpoint
                    "hls" -> if (hls == null) hls = endpoint
                }
            }
        }
        val stream = progressive ?: hls ?: return null
        val user = item.optJSONObject("user")
        return Track(
            id = item.optString("urn").ifBlank { "soundcloud:tracks:${item.optLong("id")}" },
            title = item.optString("title", "Untitled"),
            artist = user?.optString("username")?.ifBlank { null } ?: "SoundCloud",
            artworkUrl = item.optString("artwork_url").ifBlank { null },
            durationMs = item.optLong("duration", 0L),
            permalinkUrl = item.optString("permalink_url").ifBlank { null },
            streamUrl = stream
        )
    }

    private fun withClientId(block: (String) -> String): String {
        val id = clientId()
        return try {
            block(id)
        } catch (error: HttpStatusException) {
            if (error.code != 401 && error.code != 403) throw error
            val refreshed = discoverClientId()
            block(refreshed)
        }
    }

    private fun clientId(): String {
        val cached = prefs.getString("client_id", null)
        val fetchedAt = prefs.getLong("client_id_fetched_at", 0L)
        if (!cached.isNullOrBlank() && System.currentTimeMillis() - fetchedAt < CLIENT_ID_TTL_MS) return cached
        return seedClientId
    }

    private fun discoverClientId(): String {
        val html = get("https://soundcloud.com")
        val scripts = Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("a-v2.sndcdn.com/assets/") }
            .toList().asReversed()
        val patterns = listOf(
            Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{20,64})["']"""),
            Regex("""["']client_id["']\s*:\s*["']([A-Za-z0-9]{20,64})["']""")
        )
        for (script in scripts.take(12)) {
            val js = runCatching { get(script) }.getOrNull() ?: continue
            val match = patterns.firstNotNullOfOrNull { it.find(js) } ?: continue
            val id = match.groupValues[1]
            prefs.edit().putString("client_id", id).putLong("client_id_fetched_at", System.currentTimeMillis()).apply()
            return id
        }
        throw IllegalStateException("Could not discover SoundCloud web client id")
    }

    private fun isShortUrl(url: String): Boolean = runCatching { URL(url).host.equals("on.soundcloud.com", true) }.getOrDefault(false)

    private fun resolveRedirect(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "GET"
            c.connectTimeout = 6_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.connect()
            c.inputStream.close()
            c.url.toString()
        } finally { c.disconnect() }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 8_000
            c.readTimeout = 12_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "application/json,text/html,*/*")
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            Log.d(TAG, "HTTP $code ${URL(url).host}${URL(url).path}")
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use(BufferedReader::readText) }.orEmpty()
            if (code !in 200..299) throw HttpStatusException(code)
            return text
        } finally { c.disconnect() }
    }

    private class HttpStatusException(val code: Int) : IllegalStateException("HTTP $code")

    companion object {
        private const val TAG = "CloudWalkWeb"
        private const val CLIENT_ID_TTL_MS = 3L * 24 * 60 * 60 * 1000
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; CloudWalk) AppleWebKit/537.36 Chrome/121 Mobile Safari/537.36"
    }
}
