package com.textgate.app.domain.repository

interface WhatsAppRepository {
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

    // Manual link save (legacy paste flow; removed once auto-linking lands).
    suspend fun saveLink(apiKey: String, sessionId: String)
    suspend fun clearLink()
    // Checks the user's own linked session; "connected" means sendable.
    suspend fun getStatus(): Result<String>
    // Sends via the gateway. toPhone may be E.164 (+92...) — it is converted to
    // the digits-only form the gateway expects.
    suspend fun sendMessage(toPhone: String, message: String, recipientName: String? = null): Result<Unit>
}
