package com.textgate.app.domain.repository

interface WhatsAppRepository {
    // True when an API key + session id are stored locally.
    suspend fun isLinked(): Boolean
    suspend fun getLink(): Pair<String, String>?   // (apiKey, sessionId) or null
    suspend fun saveLink(apiKey: String, sessionId: String)
    suspend fun clearLink()
    // Checks the linked session against the gateway; "connected" means sendable.
    suspend fun getStatus(): Result<String>
    // Sends via the linked session. toPhone may be E.164 (+92...) — it is
    // converted to the digits-only form the gateway expects.
    suspend fun sendMessage(toPhone: String, message: String, recipientName: String? = null): Result<Unit>
}
