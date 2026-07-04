package com.textgate.app.data.repository

import com.textgate.app.data.firebase.FirebaseAuthDataSource
import com.textgate.app.data.firebase.FirestoreDataSource
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.data.whatsapp.WhatsAppApi
import com.textgate.app.domain.repository.WhatsAppRepository

class WhatsAppRepositoryImpl(
    private val prefs: PreferencesDataSource,
    private val api: WhatsAppApi,
    private val firestore: FirestoreDataSource,
    private val auth: FirebaseAuthDataSource,
) : WhatsAppRepository {

    override suspend fun isLinked(): Boolean = getLink() != null

    // Local cache first (fast, offline); Firestore user doc as the source of
    // truth (survives reinstall/sign-out and other devices) — re-cached on hit.
    override suspend fun getLink(): Pair<String, String>? {
        val cachedKey = prefs.getWaApiKey()?.takeIf { it.isNotBlank() }
        val cachedSession = prefs.getWaSessionId()?.takeIf { it.isNotBlank() }
        if (cachedKey != null && cachedSession != null) return cachedKey to cachedSession

        val uid = auth.currentUser()?.uid ?: return null
        val dto = firestore.getUser(uid).getOrNull() ?: return null
        if (dto.waApiKey.isBlank() || dto.waSessionId.isBlank()) return null
        prefs.setWaLink(dto.waApiKey, dto.waSessionId)
        return dto.waApiKey to dto.waSessionId
    }

    override suspend fun ensureProvisioned(): Result<Boolean> = runCatching {
        val uid = auth.currentUser()?.uid ?: return@runCatching false
        val dto = firestore.getUser(uid).getOrNull() ?: return@runCatching false

        // Already provisioned (this or another device) — refresh the cache.
        if (dto.waApiKey.isNotBlank() && dto.waSessionId.isNotBlank()) {
            prefs.setWaLink(dto.waApiKey, dto.waSessionId)
            return@runCatching true
        }
        // Eligible only once BOTH verifications are done.
        if (!dto.phoneVerified || !dto.emailVerified || dto.phoneNumber.isBlank()) {
            return@runCatching false
        }

        // Availability gate — fails with the friendly maintenance message.
        api.checkHealth().getOrThrow()

        val provisioned = api.provision(dto.email, dto.phoneNumber, dto.name).getOrThrow()
        firestore.saveWaLink(uid, provisioned.apiKey, provisioned.sessionId).getOrThrow()
        prefs.setWaLink(provisioned.apiKey, provisioned.sessionId)
        true
    }

    override suspend fun saveLink(apiKey: String, sessionId: String) {
        prefs.setWaLink(apiKey.trim(), sessionId.trim())
        auth.currentUser()?.uid?.let { firestore.saveWaLink(it, apiKey.trim(), sessionId.trim()) }
    }

    override suspend fun clearLink() {
        prefs.clearWaLink()
        // Best-effort Firestore clear so other devices unlink too.
        auth.currentUser()?.uid?.let { firestore.saveWaLink(it, "", "") }
    }

    override suspend fun getStatus(): Result<String> {
        val (key, session) = getLink()
            ?: return Result.failure(IllegalStateException("WhatsApp is not set up yet"))
        return api.getSessionStatus(key, session).map { it.status }
    }

    override suspend fun sendMessage(toPhone: String, message: String, recipientName: String?): Result<Unit> {
        val (key, session) = getLink()
            ?: return Result.failure(IllegalStateException("WhatsApp is not set up yet"))
        // Gateway wants digits only including country code (no '+').
        val digits = toPhone.filter { it.isDigit() }
        if (digits.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid recipient number"))
        }
        return api.sendMessage(key, session, digits, message, recipientName)
    }
}
