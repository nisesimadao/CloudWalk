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

/**
 * Compatibility client for SoundCloud's public web endpoints.
 * Public/unauthed tracks only. No cookies, private resources, or login bypasses.
 */
class WebSoundCloudApi(context: Context) {
    private val prefs = context.getSharedPreferences("cloudwalk_web", Context.MODE_PRIVATE)
    private val base = "https://api-v2.soundcloud.com"
    // Public identifier currently shipped by SoundCloud's web client. It is not a secret.
    // Used as a fast seed; if SoundCloud rotates it, discovery refreshes the value.
    private val seedClientId = "UMY1dzQ68n2QbCuypNe8JOivmV2FO2Ep"

    fun searchTracks(query: String, limit: Int = 30): List<Track> {
        var clientId = clientId()
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        fun request(id: String) = get("$base/search/tracks?q=$q&client_id=$id&limit=$limit&offset=0&linked_partitioning=1&app_locale=en")
        val body = try { request(clientId) } catch (first: Throwable) {
            clientId = discoverClientId()
            request(clientId)
        }
        val root = JSONObject(body)
        val arr = root.optJSONArray("collection") ?: return emptyList()
        Log.d("CloudWalkWeb", "search collection=${arr.length()}")
        val out = ArrayList<Track>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val media = item.optJSONObject("media")
            val transcodings = media?.optJSONArray("transcodings")
            var preferred: String? = null
            var fallback: String? = null
            if (transcodings != null) {
                for (j in 0 until transcodings.length()) {
                    val t = transcodings.optJSONObject(j) ?: continue
                    val format = t.optJSONObject("format")
                    val protocol = format?.optString("protocol")
                    val endpoint = t.optString("url").ifBlank { null }
                    if (protocol == "progressive" && endpoint != null) preferred = endpoint
                    if (fallback == null && endpoint != null) fallback = endpoint
                }
            }
            val user = item.optJSONObject("user")
            out += Track(
                id = item.optString("urn").ifBlank { "soundcloud:tracks:${item.optLong("id")}" },
                title = item.optString("title", "Untitled"),
                artist = user?.optString("username")?.ifBlank { null } ?: "SoundCloud",
                artworkUrl = item.optString("artwork_url").ifBlank { null },
                durationMs = item.optLong("duration", 0L),
                permalinkUrl = item.optString("permalink_url").ifBlank { null },
                streamUrl = preferred ?: fallback
            )
        }
        return out
    }

    fun resolvePublicStream(transcodingEndpoint: String): String {
        val separator = if ('?' in transcodingEndpoint) '&' else '?'
        var id = clientId()
        val body = try { get("$transcodingEndpoint${separator}client_id=$id") } catch (first: Throwable) {
            id = discoverClientId()
            get("$transcodingEndpoint${separator}client_id=$id")
        }
        return JSONObject(body).getString("url")
    }

    fun resolveTrackUrl(soundCloudUrl: String): Track? {
        val cid = clientId()
        val encoded = URLEncoder.encode(soundCloudUrl, StandardCharsets.UTF_8.name())
        val item = JSONObject(get("$base/resolve?url=$encoded&client_id=$cid"))
        if (item.optString("kind") != "track") return null
        val media = item.optJSONObject("media")
        val transcodings = media?.optJSONArray("transcodings")
        var endpoint: String? = null
        if (transcodings != null) {
            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
                val protocol = t.optJSONObject("format")?.optString("protocol")
                val u = t.optString("url").ifBlank { null }
                if (protocol == "progressive" && u != null) { endpoint = u; break }
                if (endpoint == null) endpoint = u
            }
        }
        val user = item.optJSONObject("user")
        return Track(
            id = item.optString("urn").ifBlank { "soundcloud:tracks:${item.optLong("id")}" },
            title = item.optString("title", "Untitled"),
            artist = user?.optString("username")?.ifBlank { null } ?: "SoundCloud",
            artworkUrl = item.optString("artwork_url").ifBlank { null },
            durationMs = item.optLong("duration", 0L),
            permalinkUrl = item.optString("permalink_url").ifBlank { soundCloudUrl },
            streamUrl = endpoint
        )
    }

    private fun clientId(): String {
        val cached = prefs.getString("client_id", null)
        val fetchedAt = prefs.getLong("client_id_fetched_at", 0L)
        if (!cached.isNullOrBlank() && System.currentTimeMillis() - fetchedAt < 3L * 24 * 60 * 60 * 1000) return cached
        return seedClientId
    }

    private fun discoverClientId(): String {
        val html = get("https://soundcloud.com")
        val scripts = Regex("""<script[^>]+src="([^"]+)"""").findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("a-v2.sndcdn.com/assets/") }
            .toList()
            .asReversed()
        for (script in scripts.take(16)) {
            val js = runCatching { get(script) }.getOrNull() ?: continue
            val match = Regex("""client_id:["]([A-Za-z0-9]{20,64})["]""").find(js) ?: continue
            val id = match.groupValues[1]
            prefs.edit().putString("client_id", id).putLong("client_id_fetched_at", System.currentTimeMillis()).apply()
            return id
        }
        throw IllegalStateException("Could not discover SoundCloud web client id")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 8_000
            c.readTimeout = 12_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "application/json,text/html,*/*")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; CloudWalk) AppleWebKit/537.36 Chrome/121 Mobile Safari/537.36")
            val code = c.responseCode
            Log.d("CloudWalkWeb", "HTTP $code ${URL(url).host}${URL(url).path}")
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use(BufferedReader::readText) }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }
}
