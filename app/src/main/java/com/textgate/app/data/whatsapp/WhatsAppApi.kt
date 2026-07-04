package com.textgate.app.data.whatsapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WaSessionStatus(
    val status: String,        // connecting | qr_ready | connected | disconnected
    val phoneNumber: String?,
)

data class WaProvisionResult(
    val apiKey: String,
    val sessionId: String,
    val sharedSessionId: String,
)

/**
 * Thin client for the baileys WhatsApp gateway. Auth is the user's personal
 * API key sent as an `x-api-key` header (no Bearer prefix). Sends return 202
 * on enqueue — delivery is asynchronous with the service's own 5–15 s anti-ban
 * pacing and there is no per-message delivery receipt.
 *
 * The base URL (and the SSO secret) resolve through [WaConfigProvider]:
 * Firestore device-doc overrides first, BuildConfig fallback — so a URL or
 * secret rotation is a config edit, never an app rebuild.
 */
class WhatsAppApi(private val configProvider: WaConfigProvider) {

    companion object {
        const val MAINTENANCE_MESSAGE =
            "WhatsApp service is currently under maintenance. Please try again in a few hours."
    }

    /**
     * Availability gate: GET /health must answer 2xx. Unreachable host, timeout,
     * or any 5xx all resolve to the friendly maintenance message — callers must
     * stop their flow on failure.
     */
    suspend fun checkHealth(): Result<Unit> = withContext(Dispatchers.IO) {
        val base = configProvider.get().serviceUrl
        try {
            val (code, _) = request(base, "GET", "/health", emptyMap(), null)
            if (code in 200..299) Result.success(Unit)
            else Result.failure(IllegalStateException(MAINTENANCE_MESSAGE))
        } catch (_: Exception) {
            Result.failure(IllegalStateException(MAINTENANCE_MESSAGE))
        }
    }

    /**
     * SSO provisioning: creates/links a PRE-VERIFIED gateway account for a user
     * whose email + phone this app has already verified. Auth is the dedicated
     * x-service-key (SSO secret) — never a user API key. Returns the personal
     * API key + the phone-derived session id. Idempotent server-side.
     */
    suspend fun provision(
        email: String,
        phoneNumber: String,
        displayName: String?,
    ): Result<WaProvisionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = configProvider.get()
            check(cfg.ssoSecret.isNotBlank()) { "WhatsApp SSO is not configured in this build" }
            val payload = JSONObject().apply {
                put("email", email)
                put("phoneNumber", phoneNumber)
                displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
            }
            val (code, body) = request(
                cfg.serviceUrl, "POST", "/sso/provision",
                mapOf("x-service-key" to cfg.ssoSecret), payload.toString(),
            )
            if (code !in 200..299) throw mapError(code, body)
            val json = JSONObject(body)
            WaProvisionResult(
                apiKey = json.getString("apiKey"),
                sessionId = json.getString("sessionId"),
                sharedSessionId = json.optString("sharedSessionId").ifBlank { "shared" },
            )
        }
    }

    suspend fun getSessionStatus(apiKey: String, sessionId: String): Result<WaSessionStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) = request(base, "GET", "/v1/sessions/$sessionId/status", apiKeyHeader(apiKey), null)
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
            val base = configProvider.get().serviceUrl
            val payload = JSONObject().apply {
                put("phoneNumber", phoneDigits)
                put("message", message)
                recipientName?.let { put("recipientName", it) }
            }
            val (code, body) = request(base, "POST", "/v1/messages/$sessionId/send", apiKeyHeader(apiKey), payload.toString())
            if (code !in 200..299) throw mapError(code, body)
        }
    }

    /** Start (or restart) the user's own session — the QR appears shortly after. */
    suspend fun connectSession(apiKey: String, sessionId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) = request(base, "POST", "/v1/sessions/$sessionId/connect", apiKeyHeader(apiKey), "{}")
                if (code !in 200..299) throw mapError(code, body)
            }
        }

    /**
     * Current QR for a linking session as a base64 PNG data-URL, or null while
     * the QR isn't ready yet (still connecting / already connected).
     */
    suspend fun getQr(apiKey: String, sessionId: String): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) = request(base, "GET", "/v1/sessions/$sessionId/qr", apiKeyHeader(apiKey), null)
                when {
                    code in 200..299 -> JSONObject(body).optString("qrBase64").takeIf { it.isNotBlank() }
                    code == 404 -> null // QR not available (yet) — caller keeps polling
                    else -> throw mapError(code, body)
                }
            }
        }

    /** Whether the admin-linked shared sender is connected and able to send. */
    suspend fun getSharedStatus(apiKey: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) = request(base, "GET", "/v1/messages/shared/status", apiKeyHeader(apiKey), null)
                if (code !in 200..299) throw mapError(code, body)
                JSONObject(body).optBoolean("connected", false)
            }
        }

    /** Send through the shared (app-owned) WhatsApp number. */
    suspend fun sendShared(
        apiKey: String,
        phoneDigits: String,
        message: String,
        recipientName: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = configProvider.get().serviceUrl
            val payload = JSONObject().apply {
                put("phoneNumber", phoneDigits)
                put("message", message)
                recipientName?.let { put("recipientName", it) }
            }
            val (code, body) = request(base, "POST", "/v1/messages/shared/send", apiKeyHeader(apiKey), payload.toString())
            if (code !in 200..299) throw mapError(code, body)
        }
    }

    private fun apiKeyHeader(apiKey: String) = mapOf("x-api-key" to apiKey)

    private fun request(
        baseUrl: String,
        method: String,
        path: String,
        headers: Map<String, String>,
        jsonBody: String?,
    ): Pair<Int, String> {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
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
        val friendly = when {
            code == 401 -> "WhatsApp link is no longer valid — open WhatsApp settings to re-link"
            code == 403 -> "This session belongs to another account"
            code == 404 -> "WhatsApp session not found — set it up from WhatsApp settings"
            code == 503 && serverMessage.isNotBlank() -> serverMessage
            code == 503 -> "WhatsApp session not connected — re-link it from WhatsApp settings"
            code >= 500 -> MAINTENANCE_MESSAGE
            else -> serverMessage.ifBlank { "WhatsApp service error (HTTP $code)" }
        }
        return IllegalStateException(friendly)
    }
}
