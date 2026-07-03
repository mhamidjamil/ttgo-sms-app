package com.textgate.app.data.whatsapp

import com.textgate.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WaSessionStatus(
    val status: String,        // connecting | qr_ready | connected | disconnected
    val phoneNumber: String?,
)

/**
 * Thin client for the baileys WhatsApp gateway. Auth is the user's personal
 * API key sent as an `x-api-key` header (no Bearer prefix). Sends return 202
 * on enqueue — delivery is asynchronous with the service's own 5–15 s anti-ban
 * pacing and there is no per-message delivery receipt.
 *
 * TODO(@dev): BuildConfig.WHATSAPP_SERVICE_URL is not permanent — load the base
 * URL dynamically from Firebase so a URL change doesn't require an app rebuild.
 */
class WhatsAppApi {

    private val baseUrl = BuildConfig.WHATSAPP_SERVICE_URL.trimEnd('/')

    suspend fun getSessionStatus(apiKey: String, sessionId: String): Result<WaSessionStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = request("GET", "/v1/sessions/$sessionId/status", apiKey, null)
                if (code !in 200..299) throw mapError(code, body)
                val json = JSONObject(body)
                WaSessionStatus(
                    status = json.optString("status", "disconnected"),
                    phoneNumber = json.optString("phoneNumber").takeIf { it.isNotBlank() },
                )
            }
        }

    // phoneDigits: digits only INCLUDING country code, no '+' (e.g. 923001234567).
    suspend fun sendMessage(
        apiKey: String,
        sessionId: String,
        phoneDigits: String,
        message: String,
        recipientName: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("phoneNumber", phoneDigits)
                put("message", message)
                recipientName?.let { put("recipientName", it) }
            }
            val (code, body) = request("POST", "/v1/messages/$sessionId/send", apiKey, payload.toString())
            if (code !in 200..299) throw mapError(code, body)
        }
    }

    private fun request(method: String, path: String, apiKey: String, jsonBody: String?): Pair<Int, String> {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("x-api-key", apiKey)
            if (jsonBody != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(jsonBody.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun mapError(code: Int, body: String): Exception {
        val serverMessage = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        val friendly = when (code) {
            401 -> "Invalid API key — paste it again (regenerating the key on the dashboard kills the old one)"
            403 -> "This session belongs to another account"
            404 -> "Session not found — link WhatsApp on the service dashboard first"
            503 -> "WhatsApp session not connected — re-link it on the service dashboard"
            else -> serverMessage.ifBlank { "WhatsApp service error (HTTP $code)" }
        }
        return IllegalStateException(friendly)
    }
}
