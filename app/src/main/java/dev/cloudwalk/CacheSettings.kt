package dev.cloudwalk

import android.content.Context

class CacheSettings(context: Context) {
    private val prefs = context.getSharedPreferences("cloudwalk_cache", Context.MODE_PRIVATE)
    @Volatile private var cachedMaxBytes =
        prefs.getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES).coerceAtLeast(MIN_MAX_BYTES)

    var maxBytes: Long
        get() = cachedMaxBytes
        set(value) {
            val safe = value.coerceAtLeast(MIN_MAX_BYTES)
            cachedMaxBytes = safe
            prefs.edit().putLong(KEY_MAX_BYTES, safe).apply()
        }

    companion object {
        const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L
        const val MIN_MAX_BYTES = 16L * 1024L * 1024L
        val PRESETS_MB = intArrayOf(32, 64, 128, 256)
        private const val KEY_MAX_BYTES = "session_cache_max_bytes"
    }
}
