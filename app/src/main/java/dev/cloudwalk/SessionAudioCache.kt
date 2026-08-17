package dev.cloudwalk

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Downloader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@UnstableApi
class SessionAudioCache(
    context: Context,
    private val settings: CacheSettings = CacheSettings(context)
) {
    private val appContext = context.applicationContext
    private val userAgent = runCatching {
        @Suppress("DEPRECATION")
        val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        "CloudWalk/${version ?: "unknown"} Android"
    }.getOrDefault("CloudWalk Android")
    private val rootDir = File(appContext.cacheDir, "session_audio")
    private val dir = File(rootDir, "session_${System.currentTimeMillis()}_${android.os.Process.myPid()}")
    private val hlsDir = File(dir, "hls")
    private val io = Executors.newSingleThreadExecutor()
    private val registry = LinkedHashMap<String, Track>()
    private val hlsStreams = HashMap<String, ResolvedPublicStream>()
    private val hlsPrefixes = HashMap<String, String>()
    private val registryLock = Any()
    private val cacheGeneration = AtomicInteger()
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activeDownloader: Downloader? = null
    @Volatile private var closed = false

    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    private val hlsCache: SimpleCache
    private val cacheKeyFactory = CacheKeyFactory { dataSpec -> stableCacheKey(dataSpec.uri.toString()) }

    init {
        dir.mkdirs()
        hlsDir.mkdirs()
        hlsCache = SimpleCache(hlsDir, NoOpCacheEvictor(), databaseProvider)
        io.execute {
            rootDir.listFiles()?.forEach { stale ->
                if (stale != dir) runCatching { stale.deleteRecursively() }
            }
        }
    }

    fun cachedFile(track: Track): File? {
        val file = fileFor(track)
        if (file.exists() && file.length() > 0L) return file
        synchronized(registryLock) {
            if (!hlsStreams.containsKey(track.id)) registry.remove(track.id)
        }
        return null
    }

    /** Read-only during playback: only an explicit Keep-for-session action fills this cache. */
    fun playbackDataSourceFactory(headers: Map<String, String>): DataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
        return CacheDataSource.Factory()
            .setCache(hlsCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(cacheKeyFactory)
            .setCacheWriteDataSinkFactory(null)
    }

    fun cache(
        track: Track,
        resolvedUrl: String,
        onProgress: (Int) -> Unit = {},
        callback: (Boolean, String) -> Unit
    ) {
        if (closed) return
        val requestGeneration = cacheGeneration.get()
        io.execute {
            if (closed || requestGeneration != cacheGeneration.get()) return@execute
            val target = fileFor(track)
            val partial = File(dir, "${target.name}.part")
            if (target.exists() && target.length() > 0L) {
                remember(track)
                if (!closed) callback(true, appContext.getString(R.string.cached_for_session))
                return@execute
            }
            partial.delete()
            var failureMessage = appContext.getString(R.string.couldnt_cache_track)
            val ok = runCatching {
                val connection = URL(resolvedUrl).openConnection() as HttpURLConnection
                activeConnection = connection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = true
                    val length = connection.contentLengthLong
                    if (length > settings.maxBytes) {
                        failureMessage = appContext.getString(R.string.session_cache_full)
                        return@runCatching false
                    }
                    if (!ensureCapacity(length.coerceAtLeast(0L))) {
                        failureMessage = appContext.getString(R.string.session_cache_full)
                        return@runCatching false
                    }

                    connection.inputStream.use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            var lastProgress = -1
                            while (true) {
                                if (closed || requestGeneration != cacheGeneration.get()) return@runCatching false
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (currentBytes() + read > settings.maxBytes) {
                                    failureMessage = appContext.getString(R.string.session_cache_full)
                                    return@runCatching false
                                }
                                output.write(buffer, 0, read)
                                if (length > 0L) {
                                    val progress = ((total * 100L) / length).toInt().coerceIn(0, 100)
                                    if (progress >= lastProgress + 2 || progress == 100) {
                                        lastProgress = progress
                                        if (!closed) onProgress(progress)
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                    if (closed || requestGeneration != cacheGeneration.get()) return@runCatching false
                    if (partial.length() <= 0L || (length > 0L && partial.length() != length)) return@runCatching false
                    if (target.exists()) target.delete()
                    partial.renameTo(target)
                } finally {
                    if (activeConnection === connection) activeConnection = null
                    connection.disconnect()
                }
            }.getOrDefault(false)

            if (!ok) partial.delete()
            if (ok) remember(track)
            if (!closed && requestGeneration == cacheGeneration.get()) {
                callback(ok, if (ok) appContext.getString(R.string.cached_for_session) else failureMessage)
            }
        }
    }

    fun cacheHls(
        track: Track,
        stream: ResolvedPublicStream,
        onProgress: (Int) -> Unit = {},
        callback: (Boolean, String) -> Unit
    ) {
        if (closed) return
        val requestGeneration = cacheGeneration.get()
        io.execute {
            if (closed || requestGeneration != cacheGeneration.get()) return@execute
            if (isCached(track)) {
                callback(true, appContext.getString(R.string.already_cached))
                return@execute
            }

            val estimate = estimateHlsBytes(track)
            if (!ensureCapacity(estimate)) {
                callback(false, appContext.getString(R.string.session_cache_full))
                return@execute
            }

            val prefix = hlsPrefix(stream.url)
            removeHlsPrefix(prefix)
            var failureMessage = appContext.getString(R.string.couldnt_cache_track)
            var canceledForLimit = false
            val headers = mapOf("X-SC-Application-Id" to stream.applicationId)
            val downloader = HlsDownloader.Factory(downloadDataSourceFactory(headers)).create(
                MediaItem.Builder()
                    .setUri(stream.url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
            )
            activeDownloader = downloader
            val ok = runCatching {
                var lastProgress = -1
                downloader.download(Downloader.ProgressListener { _, _, percentDownloaded ->
                    if (closed || requestGeneration != cacheGeneration.get()) {
                        downloader.cancel()
                        return@ProgressListener
                    }
                    if (currentBytes() > settings.maxBytes) {
                        failureMessage = appContext.getString(R.string.session_cache_full)
                        canceledForLimit = true
                        downloader.cancel()
                        return@ProgressListener
                    }
                    if (percentDownloaded >= 0f && !percentDownloaded.isNaN()) {
                        val progress = percentDownloaded.roundToInt().coerceIn(0, 100)
                        if (progress >= lastProgress + 2 || progress == 100) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                })
                !closed && requestGeneration == cacheGeneration.get() && !canceledForLimit
            }.onFailure { error ->
                if (!canceledForLimit) failureMessage = error.message ?: failureMessage
            }.getOrDefault(false)

            if (activeDownloader === downloader) activeDownloader = null
            if (ok) {
                synchronized(registryLock) {
                    hlsStreams[track.id] = stream
                    hlsPrefixes[track.id] = prefix
                }
                remember(track)
                trimToLimit()
            } else {
                removeHlsPrefix(prefix)
            }
            if (!closed && requestGeneration == cacheGeneration.get()) {
                callback(ok, if (ok) appContext.getString(R.string.cached_for_session) else failureMessage)
            }
        }
    }

    fun isCached(track: Track): Boolean = synchronized(registryLock) {
        registry.containsKey(track.id)
    }

    fun cachedTracks(): List<Track> = synchronized(registryLock) {
        registry.values.toList().asReversed()
    }

    fun remove(track: Track): Boolean = removeInternal(track)

    fun currentBytes(): Long {
        val direct = dir.listFiles()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L
        return direct + runCatching { hlsCache.cacheSpace }.getOrDefault(0L)
    }

    fun maxBytes(): Long = settings.maxBytes

    fun setMaxBytes(value: Long) {
        settings.maxBytes = value
        trimToLimit()
    }

    fun clear() {
        cacheGeneration.incrementAndGet()
        activeConnection?.disconnect()
        activeDownloader?.cancel()
        activeDownloader = null
        dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        clearHlsCache()
        synchronized(registryLock) {
            registry.clear()
            hlsStreams.clear()
            hlsPrefixes.clear()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        cacheGeneration.incrementAndGet()
        activeConnection?.disconnect()
        activeDownloader?.cancel()
        activeDownloader = null
        io.shutdownNow()
        synchronized(registryLock) {
            registry.clear()
            hlsStreams.clear()
            hlsPrefixes.clear()
        }
        runCatching { hlsCache.release() }
        runCatching { dir.deleteRecursively() }
    }

    private fun downloadDataSourceFactory(headers: Map<String, String>): CacheDataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
        return CacheDataSource.Factory()
            .setCache(hlsCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(cacheKeyFactory)
    }

    private fun ensureCapacity(incomingBytes: Long): Boolean {
        if (incomingBytes > settings.maxBytes) return false
        trimToLimit((settings.maxBytes - incomingBytes).coerceAtLeast(0L))
        return currentBytes() + incomingBytes <= settings.maxBytes
    }

    private fun trimToLimit(limit: Long = settings.maxBytes) {
        var guard = 0
        while (currentBytes() > limit && guard++ < 512) {
            val oldest = synchronized(registryLock) { registry.values.firstOrNull() } ?: break
            if (!removeInternal(oldest)) break
        }
    }

    private fun removeInternal(track: Track): Boolean {
        val direct = fileFor(track)
        val partial = File(dir, "${direct.name}.part")
        val directDeleted = (!direct.exists() || direct.delete()) && (!partial.exists() || partial.delete())
        val prefix = synchronized(registryLock) {
            hlsStreams.remove(track.id)
            hlsPrefixes.remove(track.id).also { registry.remove(track.id) }
        }
        if (prefix != null) removeHlsPrefix(prefix)
        return directDeleted
    }

    private fun clearHlsCache() {
        runCatching {
            hlsCache.keys.toList().forEach { key -> hlsCache.removeResource(key) }
        }
    }

    private fun removeHlsPrefix(prefix: String) {
        runCatching {
            hlsCache.keys.toList()
                .filter { it.startsWith(prefix) }
                .forEach { key -> hlsCache.removeResource(key) }
        }
    }

    private fun remember(track: Track) {
        synchronized(registryLock) {
            registry.remove(track.id)
            registry[track.id] = track
        }
    }

    private fun estimateHlsBytes(track: Track): Long {
        // Preferred SoundCloud AAC is 160 kbps. Include headroom for fMP4/HLS overhead.
        if (track.durationMs <= 0L) return 8L * 1024L * 1024L
        val audioBytes = (track.durationMs * 160_000L) / 8_000L
        return (audioBytes * 9L / 8L + 256L * 1024L).coerceAtLeast(512L * 1024L)
    }

    private fun hlsPrefix(url: String): String {
        val stable = stableCacheKey(url)
        val slash = stable.lastIndexOf('/')
        return if (slash >= 0) stable.substring(0, slash + 1) else stable
    }

    private fun stableCacheKey(url: String): String {
        val query = url.indexOf('?')
        val clean = if (query >= 0) url.substring(0, query) else url
        return if (clean.contains("playback.media-streaming.soundcloud.cloud", ignoreCase = true)) clean else url
    }

    private fun fileFor(track: Track): File {
        val safe = track.id.hashCode().toUInt().toString(16)
        return File(dir, "$safe.audio")
    }
}
