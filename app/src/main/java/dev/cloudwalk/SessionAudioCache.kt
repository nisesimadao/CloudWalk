package dev.cloudwalk

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

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
        io.execute {
            val target = fileFor(track)
            if (target.exists() && target.length() > 0L) {
                remember(track)
                callback(true, appContext.getString(R.string.cached_for_session))
                return@execute
            }

            val ok = runCatching {
                val connection = URL(resolvedUrl).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = true
                    val length = connection.contentLengthLong
                    if (length > settings.maxBytes) return@runCatching false
                    if (!ensureCapacity(length.coerceAtLeast(0L))) return@runCatching false

                    connection.inputStream.use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            var lastProgress = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total + otherCacheBytes(target) > settings.maxBytes) {
                                    target.delete()
                                    return@runCatching false
                                }
                                output.write(buffer, 0, read)
                                if (length > 0L) {
                                    val progress = ((total * 100L) / length).toInt().coerceIn(0, 100)
                                    if (progress >= lastProgress + 2 || progress == 100) {
                                        lastProgress = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                    target.length() > 0L
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)

            if (ok) remember(track)
            callback(ok, if (ok) appContext.getString(R.string.cached_for_session) else appContext.getString(R.string.session_cache_full))
        }
    }

    fun cachedTracks(): List<Track> = synchronized(registryLock) {
        registry.values.filter { cachedFile(it) != null }.toList().asReversed()
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
        dir.listFiles()?.forEach { it.delete() }
        synchronized(registryLock) { registry.clear() }
    }

    fun close() {
        clear()
        runCatching { dir.delete() }
        io.shutdownNow()
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
