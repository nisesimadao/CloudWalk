package dev.cloudwalk

import android.content.Context

/** Tiny token store/refresh coordinator. All secret-bearing refresh happens on the broker. */
class AuthSession(
    context: Context,
    private val broker: AuthBroker
) {
    private val prefs = context.getSharedPreferences("cloudwalk", Context.MODE_PRIVATE)
    private val lock = Any()

    fun accessToken(): String? = synchronized(lock) {
        val token = prefs.getString("access_token", null)?.takeIf { it.isNotBlank() } ?: return@synchronized null
        val expiresAt = prefs.getLong("expires_at", 0L)
        if (expiresAt == 0L || expiresAt - System.currentTimeMillis() > 120_000L) return@synchronized token
        refreshLocked() ?: token
    }

    fun hasAccount(): Boolean = synchronized(lock) {
        val token = prefs.getString("access_token", null)?.takeIf { it.isNotBlank() } ?: return@synchronized false
        val expiresAt = prefs.getLong("expires_at", 0L)
        if (expiresAt == 0L || expiresAt > System.currentTimeMillis()) return@synchronized true
        val refresh = prefs.getString("refresh_token", null)?.takeIf { it.isNotBlank() }
        broker.configured && refresh != null
    }

    fun save(tokens: BrokerTokens) = synchronized(lock) {
        prefs.edit()
            .putString("access_token", tokens.accessToken)
            .putString("refresh_token", tokens.refreshToken)
            .putLong("expires_at", expiry(tokens.expiresInSeconds))
            .apply()
    }

    fun forceRefresh(): String? = synchronized(lock) { refreshLocked() }

    fun clear() = synchronized(lock) {
        prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
    }

    private fun refreshLocked(): String? {
        val refresh = prefs.getString("refresh_token", null)?.takeIf { it.isNotBlank() } ?: return null
        if (!broker.configured) return null
        val tokens = runCatching { broker.refresh(refresh) }.getOrNull() ?: return null
        save(tokens)
        return tokens.accessToken
    }

    private fun expiry(expiresSeconds: Long): Long =
        if (expiresSeconds > 0) System.currentTimeMillis() + expiresSeconds * 1000L else 0L
}
