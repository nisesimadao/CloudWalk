package dev.cloudwalk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ArtworkCache(context: Context) {
    private val compactDevice = context.resources.configuration.screenWidthDp <= 400
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(if (compactDevice) 1 else 2)
    private val memory = object : LruCache<String, Bitmap>((if (compactDevice) 4 else 6) * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val dir = File(context.cacheDir, "artwork").apply { mkdirs() }
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    private val pendingLock = Any()
    private val diskLock = Any()
    private var downloadsSinceTrim = 0
    @Volatile private var closed = false

    init {
        io.execute { trimDiskCache() }
    }

    fun load(url: String?, into: ImageView, targetPx: Int = 256) {
        if (closed) return
        val bucket = bucketFor(targetPx)
        if (url.isNullOrBlank()) {
            into.tag = null
            into.setImageDrawable(null)
            return
        }
        val cacheKey = key(url, bucket)
        into.tag = cacheKey
        memory.get(cacheKey)?.let {
            into.setImageBitmap(it)
            return
        }
        val fallback = fallbackBitmap(url, bucket)
        if (fallback != null) into.setImageBitmap(fallback) else into.setImageDrawable(null)
        request(url, bucket) {
            if (into.tag == cacheKey) into.setImageBitmap(it)
        }
    }

    fun peek(url: String?, targetPx: Int): Bitmap? {
        if (closed || url.isNullOrBlank()) return null
        val bucket = bucketFor(targetPx)
        return memory.get(key(url, bucket)) ?: fallbackBitmap(url, bucket)
    }

    fun prefetch(url: String?, targetPx: Int, onReady: (() -> Unit)? = null) {
        if (closed || url.isNullOrBlank()) return
        val bucket = bucketFor(targetPx)
        if (memory.get(key(url, bucket)) != null) {
            onReady?.invoke()
            return
        }
        request(url, bucket) { onReady?.invoke() }
    }


    private fun fallbackBitmap(url: String, bucket: Int): Bitmap? {
        val buckets = intArrayOf(96, 192, 320, 512)
        val index = buckets.indexOf(bucket)
        if (index <= 0) return null
        for (i in index - 1 downTo 0) {
            memory.get(key(url, buckets[i]))?.let { return it }
        }
        return null
    }

    private fun request(url: String, bucket: Int, onReady: ((Bitmap) -> Unit)? = null) {
        if (closed) return
        val cacheKey = key(url, bucket)
        memory.get(cacheKey)?.let { bitmap ->
            onReady?.invoke(bitmap)
            return
        }
        var shouldStart = false
        synchronized(pendingLock) {
            val listeners = pending[cacheKey]
            if (listeners != null) {
                if (onReady != null) listeners.add(onReady)
            } else {
                pending[cacheKey] = ArrayList<(Bitmap) -> Unit>(2).apply { if (onReady != null) add(onReady) }
                shouldStart = true
            }
        }
        if (!shouldStart) return
        io.execute {
            val bitmap = loadBitmap(url, bucket)
            val listeners = synchronized(pendingLock) { pending.remove(cacheKey).orEmpty() }
            if (closed) {
                bitmap?.recycle()
                return@execute
            }
            if (bitmap != null) {
                memory.put(cacheKey, bitmap)
                if (listeners.isNotEmpty()) main.post {
                    if (!closed) listeners.forEach { it(bitmap) }
                }
            }
        }
    }

    private fun loadBitmap(url: String, targetPx: Int): Bitmap? {
        if (url.startsWith("file://")) {
            val local = File(url.removePrefix("file://"))
            return if (local.exists()) decodeSampled(local, targetPx) else null
        }
        val requestUrl = artworkVariant(url, targetPx)
        val diskKey = requestUrl.hashCode().toUInt().toString(16)
        val file = File(dir, "$diskKey.img")
        if (!file.exists() || file.length() == 0L) {
            if (!download(requestUrl, file)) return null
            synchronized(diskLock) {
                downloadsSinceTrim++
                if (downloadsSinceTrim >= 12) {
                    downloadsSinceTrim = 0
                    trimDiskCache(excluding = file)
                }
            }
        }
        file.setLastModified(System.currentTimeMillis())
        val bitmap = decodeSampled(file, targetPx)
        if (bitmap == null) file.delete()
        return bitmap
    }

    private fun artworkVariant(url: String, targetPx: Int): String {
        if (!url.contains("sndcdn.com/") || !url.contains("-large.")) return url
        val size = when {
            targetPx <= 96 -> "t120x120"
            targetPx <= 192 -> "t200x200"
            targetPx <= 320 -> "t500x500"
            else -> "t500x500"
        }
        return url.replace(Regex("""-large\.(jpg|jpeg|png|webp)(?:\?.*)?$""")) { match ->
            val ext = match.groupValues[1]
            "-$size.$ext"
        }
    }


    private fun trimDiskCache(excluding: File? = null) {
        synchronized(diskLock) {
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            var total = files.sumOf { it.length() }
            if (total <= MAX_DISK_BYTES) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (file == excluding) continue
                if (total <= MAX_DISK_BYTES) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    private fun download(url: String, file: File): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.useCaches = true
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return@runCatching false
            connection.inputStream.use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output, 16 * 1024) }
            }
            file.length() > 0L
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun bucketFor(targetPx: Int): Int = when {
        targetPx <= 96 -> 96
        targetPx <= 192 -> 192
        targetPx <= 320 -> 320
        else -> 512
    }

    private fun key(url: String, bucket: Int) = "$url#$bucket"

    @Suppress("DEPRECATION")
    fun trimMemory(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> memory.evictAll()
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> memory.trimToSize(2 * 1024 * 1024)
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> memory.trimToSize(3 * 1024 * 1024)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        synchronized(pendingLock) { pending.clear() }
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        memory.evictAll()
    }

    companion object {
        private const val MAX_DISK_BYTES = 48L * 1024L * 1024L
    }
}