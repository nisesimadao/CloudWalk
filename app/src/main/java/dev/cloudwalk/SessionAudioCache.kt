package dev.cloudwalk

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SessionAudioCache(
    context: Context,
    private val settings: CacheSettings = CacheSettings(context)
) {
    private val appContext = context.applicationContext
    private val rootDir = File(appContext.cacheDir, "session_audio")
    private val dir = File(rootDir, "session_${System.currentTimeMillis()}_${android.os.Process.myPid()}")
    private val io = Executors.newSingleThreadExecutor()
    private val registry = LinkedHashMap<String, Track>()
    private val registryLock = Any()
    private val cacheGeneration = AtomicInteger()
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var closed = false

    init {
        dir.mkdirs()
        io.execute {
            rootDir.listFiles()?.forEach { stale ->
                if (stale != dir) runCatching { stale.deleteRecursively() }
            }
        }
    }

    fun cachedFile(track: Track): File? {
        val file = fileFor(track)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    fun cache(track: Track, resolvedUrl: String, onProgress: (Int) -> Unit = {}, callback: (Boolean, String) -> Unit) {
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
                                if (total + otherCacheBytes(partial) > settings.maxBytes) {
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

    fun isCached(track: Track): Boolean = synchronized(registryLock) {
        registry.containsKey(track.id)
    }

    fun cachedTracks(): List<Track> = synchronized(registryLock) {
        registry.values.toList().asReversed()
    }

    fun remove(track: Track): Boolean {
        val deleted = fileFor(track).let { !it.exists() || it.delete() }
        synchronized(registryLock) { registry.remove(track.id) }
        return deleted
    }

    fun currentBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
    fun maxBytes(): Long = settings.maxBytes
    fun setMaxBytes(value: Long) {
        settings.maxBytes = value
        trimToLimit()
    }

    fun clear() {
        cacheGeneration.incrementAndGet()
        activeConnection?.disconnect()
        dir.listFiles()?.forEach { it.delete() }
        synchronized(registryLock) { registry.clear() }
    }

    fun close() {
        if (closed) return
        closed = true
        cacheGeneration.incrementAndGet()
        activeConnection?.disconnect()
        io.shutdownNow()
        dir.listFiles()?.forEach { it.delete() }
        synchronized(registryLock) { registry.clear() }
        runCatching { dir.delete() }
    }

    private fun ensureCapacity(incomingBytes: Long): Boolean {
        if (incomingBytes > settings.maxBytes) return false
        trimToLimit(settings.maxBytes - incomingBytes)
        return currentBytes() + incomingBytes <= settings.maxBytes
    }

    private fun trimToLimit(limit: Long = settings.maxBytes) {
        var total = currentBytes()
        if (total <= limit) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        for (file in files) {
            if (total <= limit) break
            val len = file.length()
            if (file.delete()) {
                total -= len
                forgetFile(file)
            }
        }
    }


    private fun remember(track: Track) {
        synchronized(registryLock) {
            registry.remove(track.id)
            registry[track.id] = track
        }
    }

    private fun forgetFile(file: File) {
        synchronized(registryLock) {
            val id = registry.entries.firstOrNull { fileFor(it.value).name == file.name }?.key
            if (id != null) registry.remove(id)
        }
    }

    private fun otherCacheBytes(excluding: File): Long =
        dir.listFiles()?.filter { it != excluding }?.sumOf { it.length() } ?: 0L

    private fun fileFor(track: Track): File {
        val safe = track.id.hashCode().toUInt().toString(16)
        return File(dir, "$safe.audio")
    }
}
