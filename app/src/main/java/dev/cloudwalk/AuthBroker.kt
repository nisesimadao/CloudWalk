package dev.cloudwalk

import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Optional auth broker. SoundCloud currently treats mobile/desktop clients as confidential,
 * so the client secret must not be embedded in the APK.
 *
 * Expected broker contract:
 * GET  {base}/soundcloud/start?redirect_uri=cloudwalk://auth/callback
 *      -> browser-based SoundCloud authorization
 *      -> redirects to cloudwalk://auth/callback?code=ONE_TIME_CODE
 * POST {base}/soundcloud/exchange { code, redirect_uri }
 *      -> { access_token, refresh_token?, expires_in? }
 */
class AuthBroker(private val baseUrl: String) {
    val configured: Boolean get() = baseUrl.startsWith("https://")

    fun authorizationUri(redirectUri: String): Uri {
        val redirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8.name())
        return Uri.parse("${baseUrl.trimEnd('/')}/soundcloud/start?redirect_uri=$redirect")
    }

    fun refresh(refreshToken: String): BrokerTokens {
        if (!configured) throw IllegalStateException("Auth broker is not configured")
        return postTokens("/soundcloud/refresh", JSONObject().put("refresh_token", refreshToken))
    }

    fun exchange(code: String, redirectUri: String): BrokerTokens {
        if (!configured) throw IllegalStateException("Auth broker is not configured")
        return postTokens("/soundcloud/exchange", JSONObject().put("code", code).put("redirect_uri", redirectUri))
    }

    private fun postTokens(path: String, payload: JSONObject): BrokerTokens {
        val connection = (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use(BufferedReader::readText) }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Broker HTTP $status")
            val json = JSONObject(text)
            BrokerTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifBlank { null },
                expiresInSeconds = json.optLong("expires_in", 0L)
            )
        } finally {
            connection.disconnect()
        }
    }
}

data class BrokerTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long
)
