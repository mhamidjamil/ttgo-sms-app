package com.textgate.app.domain.repository

interface WhatsAppRepository {

    companion object {
        const val MODE_SHARED = "shared" // app's WhatsApp number — zero setup (default)
        const val MODE_OWN = "own"       // user-linked WhatsApp (QR scan)
    }

    // True when the user has a provisioned gateway account (API key + session).
    suspend fun isLinked(): Boolean
    suspend fun getLink(): Pair<String, String>?   // (apiKey, sessionId) or null

    /**
     * SSO auto-provisioning. No-op success when a link already exists. When the
     * user has BOTH phone and email verified and no link yet: health-gates the
     * gateway (fails with the friendly maintenance message when it is down),
     * provisions a pre-verified account, and persists the returned API key +
     * phone-derived session id to the Firestore user doc + local cache.
     * Returns false when the user isn't eligible yet (not both verified).
     */
    suspend fun ensureProvisioned(): Result<Boolean>

    suspend fun clearLink()

    // Sending mode: MODE_SHARED (default) or MODE_OWN.
    suspend fun getMode(): String
    suspend fun setMode(mode: String): Result<Unit>

    // Own-session linking (MODE_OWN): start the session, then poll the QR and
    // status until "connected".
    suspend fun startOwnLinking(): Result<Unit>
    suspend fun getQr(): Result<String?>           // base64 PNG data-URL, null while not ready
    suspend fun getStatus(): Result<String>        // own-session status
    suspend fun getSharedConnected(): Result<Boolean>

    // Sends via the gateway using the active mode. toPhone may be E.164 (+92...)
    // — it is converted to the digits-only form the gateway expects.
    suspend fun sendMessage(toPhone: String, message: String, recipientName: String? = null): Result<Unit>
}
