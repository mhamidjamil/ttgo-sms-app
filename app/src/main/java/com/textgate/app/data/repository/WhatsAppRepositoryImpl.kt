package com.textgate.app.data.repository

import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.data.whatsapp.WhatsAppApi
import com.textgate.app.domain.repository.WhatsAppRepository

class WhatsAppRepositoryImpl(
    private val prefs: PreferencesDataSource,
    private val api: WhatsAppApi,
) : WhatsAppRepository {

    override suspend fun isLinked(): Boolean = getLink() != null

    override suspend fun getLink(): Pair<String, String>? {
        val key = prefs.getWaApiKey()?.takeIf { it.isNotBlank() } ?: return null
        val session = prefs.getWaSessionId()?.takeIf { it.isNotBlank() } ?: return null
        return key to session
    }

    override suspend fun saveLink(apiKey: String, sessionId: String) =
        prefs.setWaLink(apiKey.trim(), sessionId.trim())

    override suspend fun clearLink() = prefs.clearWaLink()

    override suspend fun getStatus(): Result<String> {
        val (key, session) = getLink()
            ?: return Result.failure(IllegalStateException("WhatsApp is not linked yet"))
        return api.getSessionStatus(key, session).map { it.status }
    }

    override suspend fun sendMessage(toPhone: String, message: String, recipientName: String?): Result<Unit> {
        val (key, session) = getLink()
            ?: return Result.failure(IllegalStateException("WhatsApp is not linked yet"))
        // Gateway wants digits only including country code (no '+').
        val digits = toPhone.filter { it.isDigit() }
        if (digits.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid recipient number"))
        }
        return api.sendMessage(key, session, digits, message, recipientName)
    }
}
