package com.spotwire.app.data.whatsapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * How this app proves who it is to the gateway: a key id and a secret created on
 * the gateway portal, bound to one WhatsApp number when it is made, so a send
 * never has to name a session.
 *
 * The older single-value scheme is gone with the shared sender it belonged to.
 * Everything now sends from a number its own owner linked.
 */
data class WaCredential(val keyId: String, val keySecret: String) {
    fun headers(): Map<String, String> = mapOf("x-key-id" to keyId, "x-key-secret" to keySecret)

    companion object {
        fun pair(keyId: String, keySecret: String) = WaCredential(keyId, keySecret)
    }
}

data class WaSession(
    val sessionId: String,
    val status: String,          // connecting | qr_ready | connected | disconnected
    val phoneNumber: String?,
)

data class WaSessionStatus(
    val status: String,
    val phoneNumber: String?,
)

/** What /health said. Null means the gateway answered but did not mention it. */
data class WaHealth(val whatsAppConnected: Boolean?)

/** One row of the gateway's own trail: queued, sending, sent, failed, cancelled. */
data class WaMessageStatus(val id: String, val status: String, val error: String?)

/**
 * Thin client for the baileys WhatsApp gateway (API 3.0.0). Sends return 202 on
 * enqueue — delivery is asynchronous with the service's own anti-ban pacing and
 * there is no per-message delivery receipt.
 *
 * The base URL resolves through [WaConfigProvider]: Firestore device-doc
 * override first, BuildConfig fallback — so moving the gateway is a config edit,
 * never an app rebuild.
 */
class WhatsAppApi(private val configProvider: WaConfigProvider) {

    companion object {
        private const val TAG = "WhatsAppApi"
        const val MAINTENANCE_MESSAGE =
            "WhatsApp service is currently under maintenance. Please try again in a few hours."
    }

    /**
     * Availability gate: GET /health must answer 2xx. Unreachable host, timeout,
     * or any 5xx all resolve to the friendly maintenance message — callers must
     * stop their flow on failure.
     *
     * The one call that needs no credential, which is what makes it usable before
     * a brand new account has anything to authenticate with. A gateway that also
     * reports its WhatsApp link state fills in [WaHealth.whatsAppConnected]; an
     * older one leaves it null, which reads as "up, and it did not say".
     */
    suspend fun checkHealth(): Result<WaHealth> = withContext(Dispatchers.IO) {
        val base = configProvider.get().serviceUrl
        try {
            val (code, body) = request(base, "GET", "/health", emptyMap(), null)
            if (code !in 200..299) {
                Log.w(TAG, "health check failed: HTTP $code at $base")
                return@withContext Result.failure(IllegalStateException(MAINTENANCE_MESSAGE))
            }
            val whatsApp = runCatching {
                val json = JSONObject(body)
                when {
                    json.has("whatsapp") -> json.optString("whatsapp") == "connected"
                    json.has("whatsappConnected") -> json.optBoolean("whatsappConnected")
                    else -> null
                }
            }.getOrNull()
            Result.success(WaHealth(whatsAppConnected = whatsApp))
        } catch (e: Exception) {
            Log.w(TAG, "health check unreachable at $base: ${e.javaClass.simpleName}")
            Result.failure(IllegalStateException(MAINTENANCE_MESSAGE))
        }
    }

    /**
     * Lists the WhatsApp numbers this credential can reach. Doubles as the
     * validation call for a key the user has just pasted: 200 proves the pair is
     * live, and a key minted on the portal is always bound to exactly one of
     * these numbers.
     */
    suspend fun listSessions(cred: WaCredential): Result<List<WaSession>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) = request(base, "GET", "/v1/sessions", cred.headers(), null)
                if (code !in 200..299) throw mapError(code, body)
                val array = JSONObject(body).optJSONArray("sessions")
                buildList {
                    for (i in 0 until (array?.length() ?: 0)) {
                        val item = array!!.getJSONObject(i)
                        add(
                            WaSession(
                                sessionId = item.optString("sessionId"),
                                status = item.optString("status", "disconnected"),
                                phoneNumber = item.optString("phoneNumber").takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
            }
        }

    suspend fun getSessionStatus(cred: WaCredential, sessionId: String): Result<WaSessionStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) = request(base, "GET", "/v1/sessions/$sessionId/status", cred.headers(), null)
                if (code !in 200..299) throw mapError(code, body)
                val json = JSONObject(body)
                WaSessionStatus(
                    status = json.optString("status", "disconnected"),
                    phoneNumber = json.optString("phoneNumber").takeIf { it.isNotBlank() },
                )
            }
        }

    /**
     * Queues one message. A portal key is tied to a number, so it posts to the
     * unqualified send path and the gateway resolves the sender; the gateway
     * answers 400 when a key has no binding, which is the cue to name the
     * session explicitly. phoneDigits is digits only including the country code.
     */
    suspend fun sendMessage(
        cred: WaCredential,
        sessionId: String,
        phoneDigits: String,
        message: String,
        recipientName: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = configProvider.get().serviceUrl
            val payload = JSONObject().apply {
                put("phoneNumber", phoneDigits)
                put("message", message)
                recipientName?.let { put("recipientName", it) }
                // Everything this app sends is somebody waiting: a test the user
                // just pressed, or an arrival alert that is worthless an hour
                // late. That is the gateway's fast lane, which overtakes queued
                // campaign traffic and keeps its own reserved headroom. Without
                // this the alerts queue behind other people's uploads and are
                // capped at the slower campaign allowance.
                put("priority", "high")
            }.toString()

            val (code, body) = request(base, "POST", "/v1/messages/send", cred.headers(), payload)
            if (code in 200..299) return@runCatching messageIdOf(body)
            // 400 means this key names no number — fall through and say which.
            if (code != 400 || sessionId.isBlank()) throw mapError(code, body)
            Log.i(TAG, "unbound key, retrying send against session $sessionId")
            val (retryCode, retryBody) = request(
                base, "POST", "/v1/messages/$sessionId/send", cred.headers(), payload,
            )
            if (retryCode !in 200..299) throw mapError(retryCode, retryBody)
            messageIdOf(retryBody)
        }
    }

    /** Start (or restart) a session — the QR appears shortly after. */
    suspend fun connectSession(cred: WaCredential, sessionId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) = request(base, "POST", "/v1/sessions/$sessionId/connect", cred.headers(), "{}")
                if (code !in 200..299) throw mapError(code, body)
            }
        }

    /**
     * Current QR for a linking session as a base64 PNG data-URL, or null while
     * the QR isn't ready yet (still connecting / already connected).
     */
    suspend fun getQr(cred: WaCredential, sessionId: String): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) = request(base, "GET", "/v1/sessions/$sessionId/qr", cred.headers(), null)
                when {
                    code in 200..299 -> JSONObject(body).optString("qrBase64").takeIf { it.isNotBlank() }
                    code == 404 -> null // QR not available (yet) — caller keeps polling
                    else -> throw mapError(code, body)
                }
            }
        }

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
            // Present only while a rotated key's OLD secret is still being
            // accepted. Worth a log line: the send keeps working right up to the
            // moment it silently stops.
            connection.getHeaderField("x-key-secret-expires")?.let {
                Log.w(TAG, "gateway key secret is superseded and stops working at $it")
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The gateway's own id for the message it just accepted. Keeping it is what
     * turns "handed over" into a question the app can answer later: 202 means
     * queued, not delivered, and until now the app treated the two as the same.
     */
    private fun messageIdOf(body: String): String = runCatching {
        JSONObject(body).optJSONArray("messageIds")?.optString(0).orEmpty()
    }.getOrDefault("")

    /**
     * The recent trail with each row's current status, which is how a queued
     * message is followed to sent or failed. A purpose-built lookup by id would
     * be cheaper, and the gateway is being asked for one; until then this is the
     * endpoint that exists and one call covers every pending row at once.
     */
    suspend fun recentMessages(cred: WaCredential, limit: Int = 50): Result<List<WaMessageStatus>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = configProvider.get().serviceUrl
                val (code, body) =
                    request(base, "GET", "/v1/messages/recent?limit=$limit", cred.headers(), null)
                if (code !in 200..299) throw mapError(code, body)
                val json = JSONObject(body)
                val rows = json.optJSONArray("messages") ?: json.optJSONArray("rows")
                buildList {
                    for (i in 0 until (rows?.length() ?: 0)) {
                        val row = rows!!.getJSONObject(i)
                        add(
                            WaMessageStatus(
                                id = row.optString("id"),
                                status = row.optString("status"),
                                error = row.optString("error").takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
            }
        }

    private fun errorOf(body: String) =
        runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()

    private fun mapError(code: Int, body: String): Exception {
        val serverMessage = errorOf(body)
        val revoked = runCatching { JSONObject(body).optBoolean("revoked") }.getOrDefault(false)
        val friendly = when {
            revoked -> "That gateway key was revoked. Create a new one on the portal and paste it here."
            code == 401 -> "The gateway rejected this key. Check the key id and secret, or create a new key."
            code == 403 && serverMessage.isNotBlank() -> serverMessage
            code == 403 -> "This gateway key is not allowed to do that"
            code == 404 -> "WhatsApp session not found — set it up from WhatsApp settings"
            code == 503 && serverMessage.isNotBlank() -> serverMessage
            code == 503 -> "WhatsApp session not connected — re-link it from WhatsApp settings"
            code >= 500 -> MAINTENANCE_MESSAGE
            else -> serverMessage.ifBlank { "WhatsApp service error (HTTP $code)" }
        }
        Log.w(TAG, "gateway error HTTP $code: ${serverMessage.ifBlank { "(no body)" }}")
        return IllegalStateException(friendly)
    }
}
