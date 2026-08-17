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

data class ResolvedPublicStream(
    val url: String,
    val protocol: String,
    val licenseAuthToken: String? = null,
    val applicationId: String
)

/** Public/unauthed SoundCloud web compatibility client. */
class WebSoundCloudApi(context: Context) {
    private val prefs = context.getSharedPreferences("cloudwalk_web", Context.MODE_PRIVATE)
    private val base = "https://api-v2.soundcloud.com"
    private val seedClientId = "UMY1dzQ68n2QbCuypNe8JOivmV2FO2Ep"
    private val clientIdLock = Any()
    @Volatile private var cachedClientId: String? = prefs.getString("client_id", null)?.takeIf { it.isNotBlank() }
    private val applicationId: String = run {
        val tail = System.currentTimeMillis().toString().takeLast(8).toLongOrNull() ?: 0L
        (tail * 1000L + (Math.random() * 1000.0).toLong()).toString()
    }

    fun searchTracks(query: String, limit: Int = 30, offset: Int = 0): List<Track> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val safeOffset = offset.coerceAtLeast(0)
        val body = withClientId { id ->
            get("$base/search/tracks?q=$q&client_id=$id&limit=$limit&offset=$safeOffset&linked_partitioning=1&app_locale=en")
        }
        val arr = JSONObject(body).optJSONArray("collection") ?: return emptyList()
        Log.d(TAG, "search collection=${arr.length()}")
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::parseTrack)?.let(::add)
        }
    }

    fun resolvePublicStream(transcodingEndpoint: String, permalinkUrl: String? = null): ResolvedPublicStream {
        fun protocolOf(endpoint: String): String = when {
            endpoint.contains("ctr-encrypted-hls", ignoreCase = true) -> "ctr-encrypted-hls"
            endpoint.contains("cbc-encrypted-hls", ignoreCase = true) -> "cbc-encrypted-hls"
            endpoint.contains("/hls", ignoreCase = true) -> "hls"
            else -> "progressive"
        }
        fun resolve(endpoint: String): ResolvedPublicStream {
            val separator = if ('?' in endpoint) '&' else '?'
            val body = withClientId { id -> get("$endpoint${separator}client_id=$id") }
            val json = JSONObject(body)
            return ResolvedPublicStream(
                url = json.getString("url"),
                protocol = protocolOf(endpoint),
                licenseAuthToken = json.optString("licenseAuthToken").ifBlank { null },
                applicationId = applicationId
            )
        }
        return try {
            resolve(transcodingEndpoint)
        } catch (error: HttpStatusException) {
            if (error.code != 404 || permalinkUrl.isNullOrBlank()) throw error
            val refreshed = resolveTrackUrl(permalinkUrl)?.streamUrl ?: throw error
            resolve(refreshed)
        }
    }

    fun resolvePublicUrl(inputUrl: String): SoundCloudResolvedUrl {
        val soundCloudUrl = if (isShortUrl(inputUrl)) resolveRedirect(inputUrl) else inputUrl
        val encoded = URLEncoder.encode(soundCloudUrl, StandardCharsets.UTF_8.name())
        val body = withClientId { id -> get("$base/resolve?url=$encoded&client_id=$id") }
        val item = JSONObject(body)
        return when (item.optString("kind")) {
            "user" -> {
                val id = item.optLong("id", 0L)
                SoundCloudResolvedUrl(profile = if (id > 0) SoundCloudPublicProfile(
                    id = id,
                    username = item.optString("username").ifBlank { "SoundCloud" },
                    permalinkUrl = item.optString("permalink_url").ifBlank { soundCloudUrl }
                ) else null)
            }
            "track" -> SoundCloudResolvedUrl(track = parseTrack(item)?.let { track ->
                if (track.permalinkUrl.isNullOrBlank()) track.copy(permalinkUrl = soundCloudUrl) else track
            })
            else -> SoundCloudResolvedUrl()
        }
    }

    fun resolveTrackUrl(inputUrl: String): Track? = resolvePublicUrl(inputUrl).track

    fun resolveProfileUrl(inputUrl: String): SoundCloudPublicProfile? = resolvePublicUrl(inputUrl).profile

    fun profileLikes(profile: SoundCloudPublicProfile, limit: Int = 300): List<Track> {
        val out = ArrayList<Track>(minOf(limit, 300))
        val seen = HashSet<String>()
        var offset = 0
        val safeLimit = limit.coerceIn(1, 300)
        while (out.size < safeLimit) {
            val pageLimit = minOf(100, safeLimit - out.size)
            val body = withClientId { id ->
                get("$base/users/${profile.id}/likes?client_id=$id&limit=$pageLimit&offset=$offset&linked_partitioning=1")
            }
            val arr = JSONObject(body).optJSONArray("collection") ?: break
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val item = entry.optJSONObject("track") ?: if (entry.optString("kind") == "track") entry else null
                val track = item?.let(::parseTrack) ?: continue
                if (seen.add(track.id)) {
                    out += track
                    if (out.size >= safeLimit) break
                }
            }
            offset += pageLimit
            if (arr.length() < pageLimit) break
        }
        return out
    }

    fun profileTracks(profile: SoundCloudPublicProfile, limit: Int = 120): List<Track> {
        val safeLimit = limit.coerceIn(1, 120)
        val body = withClientId { id ->
            get("$base/users/${profile.id}/tracks?client_id=$id&limit=$safeLimit&offset=0&linked_partitioning=1")
        }
        val arr = JSONObject(body).optJSONArray("collection") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::parseTrack)?.let(::add)
        }
    }

    fun artistTracks(track: Track, limit: Int = 30): List<Track> {
        val permalink = track.permalinkUrl ?: return emptyList()
        val uri = runCatching { java.net.URI(permalink) }.getOrNull() ?: return emptyList()
        val artistSlug = uri.path.trim('/').substringBefore('/').takeIf { it.isNotBlank() } ?: return emptyList()
        val artistUrl = "https://soundcloud.com/$artistSlug"
        val encoded = URLEncoder.encode(artistUrl, StandardCharsets.UTF_8.name())
        val userBody = withClientId { id -> get("$base/resolve?url=$encoded&client_id=$id") }
        val user = JSONObject(userBody)
        if (user.optString("kind") != "user") return emptyList()
        val userId = user.optLong("id", 0L).takeIf { it > 0 } ?: return emptyList()
        val body = withClientId { id ->
            get("$base/users/$userId/tracks?client_id=$id&limit=$limit&offset=0&linked_partitioning=1")
        }
        val arr = JSONObject(body).optJSONArray("collection") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::parseTrack)?.let(::add)
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
        var aacCtr160: String? = null
        var aacCtr96: String? = null
        var aacCbc160: String? = null
        var aacCbc96: String? = null
        var legacyHls: String? = null
        var legacyProgressive: String? = null
        if (transcodings != null) {
            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
                val endpoint = t.optString("url").ifBlank { null } ?: continue
                val format = t.optJSONObject("format")
                val protocol = format?.optString("protocol")
                val mime = format?.optString("mime_type").orEmpty()
                val preset = t.optString("preset")
                val isAac = mime.contains("audio/mp4", ignoreCase = true)
                when {
                    protocol == "ctr-encrypted-hls" && isAac && preset == "aac_160k" -> if (aacCtr160 == null) aacCtr160 = endpoint
                    protocol == "ctr-encrypted-hls" && isAac && preset == "aac_96k" -> if (aacCtr96 == null) aacCtr96 = endpoint
                    protocol == "cbc-encrypted-hls" && isAac && preset == "aac_160k" -> if (aacCbc160 == null) aacCbc160 = endpoint
                    protocol == "cbc-encrypted-hls" && isAac && preset == "aac_96k" -> if (aacCbc96 == null) aacCbc96 = endpoint
                    protocol == "hls" -> if (legacyHls == null) legacyHls = endpoint
                    protocol == "progressive" -> if (legacyProgressive == null) legacyProgressive = endpoint
                }
            }
        }
        var stream = aacCtr160 ?: aacCtr96 ?: aacCbc160 ?: aacCbc96 ?: legacyHls ?: legacyProgressive ?: return null
        item.optString("track_authorization").ifBlank { null }?.let { authorization ->
            if (!stream.contains("track_authorization=")) {
                val separator = if ('?' in stream) '&' else '?'
                val encoded = URLEncoder.encode(authorization, StandardCharsets.UTF_8.name())
                stream = "$stream${separator}track_authorization=$encoded"
            }
        }
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
            val refreshed = refreshClientId(id)
            block(refreshed)
        }
    }

    private fun clientId(): String = cachedClientId ?: seedClientId

    private fun refreshClientId(failedId: String): String = synchronized(clientIdLock) {
        val current = clientId()
        if (current != failedId) return@synchronized current
        discoverClientId()
    }

    private fun discoverClientId(): String {
        val html = get("https://soundcloud.com")
        val directPatterns = listOf(
            Regex("""[\"']clientId[\"']\s*:\s*[\"']([A-Za-z0-9]{20,64})[\"']"""),
            Regex("""[\"']client_id[\"']\s*:\s*[\"']([A-Za-z0-9]{20,64})[\"']""")
        )
        directPatterns.firstNotNullOfOrNull { it.find(html) }?.groupValues?.getOrNull(1)?.let {
            return saveClientId(it)
        }

        val scripts = Regex("""<script[^>]+src=[\"']([^\"']+)[\"']""").findAll(html)
            .map { it.groupValues[1] }
            .filter { src ->
                runCatching {
                    val host = URL(src).host
                    host.endsWith("sndcdn.com", ignoreCase = true) && src.contains(".js", ignoreCase = true)
                }.getOrDefault(false)
            }
            .toList().asReversed()
        val patterns = listOf(
            Regex("""client_id\s*[:=]\s*[\"']([A-Za-z0-9]{20,64})[\"']"""),
            Regex("""[\"']client_id[\"']\s*:\s*[\"']([A-Za-z0-9]{20,64})[\"']"""),
            Regex("""[\"']clientId[\"']\s*:\s*[\"']([A-Za-z0-9]{20,64})[\"']""")
        )
        for (script in scripts.take(16)) {
            val js = runCatching { get(script) }.getOrNull() ?: continue
            val match = patterns.firstNotNullOfOrNull { it.find(js) } ?: continue
            return saveClientId(match.groupValues[1])
        }
        throw IllegalStateException("Could not discover SoundCloud web client id")
    }

    private fun saveClientId(id: String): String {
        cachedClientId = id
        prefs.edit().putString("client_id", id).remove("client_id_fetched_at").apply()
        return id
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
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; CloudWalk) AppleWebKit/537.36 Chrome/121 Mobile Safari/537.36"
    }
}


data class SoundCloudResolvedUrl(
    val track: Track? = null,
    val profile: SoundCloudPublicProfile? = null
)

data class SoundCloudPublicProfile(
    val id: Long,
    val username: String,
    val permalinkUrl: String
)
