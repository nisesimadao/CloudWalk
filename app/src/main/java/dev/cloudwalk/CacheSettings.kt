package dev.cloudwalk

import android.content.Context

class CacheSettings(context: Context) {
    private val prefs = context.getSharedPreferences("cloudwalk_cache", Context.MODE_PRIVATE)

    var maxBytes: Long
        get() = prefs.getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES).coerceAtLeast(MIN_MAX_BYTES)
        set(value) {
            prefs.edit().putLong(KEY_MAX_BYTES, value.coerceAtLeast(MIN_MAX_BYTES)).apply()
        }

    companion object {
        const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L
        const val MIN_MAX_BYTES = 16L * 1024L * 1024L
        val PRESETS_MB = intArrayOf(32, 64, 128, 256)
        private const val KEY_MAX_BYTES = "session_cache_max_bytes"
    }
}
