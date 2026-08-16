package com.spotwire.app.data.repository

import android.util.Log
import com.spotwire.app.data.firebase.FirebaseAuthDataSource
import com.spotwire.app.data.firebase.FirestoreDataSource
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.data.whatsapp.WaConfigProvider
import com.spotwire.app.data.whatsapp.WaCredential
import com.spotwire.app.data.whatsapp.WhatsAppApi
import com.spotwire.app.domain.repository.WhatsAppRepository

private const val TAG = "WhatsAppRepo"

class WhatsAppRepositoryImpl(
    private val prefs: PreferencesDataSource,
    private val api: WhatsAppApi,
    private val firestore: FirestoreDataSource,
    private val auth: FirebaseAuthDataSource,
    private val config: WaConfigProvider,
) : WhatsAppRepository {

    // (credential, sessionId) resolved local-cache-first, then the Firestore
    // user doc, which is the source of truth and survives reinstall, sign-out
    // and a second device.
    private suspend fun credential(): Pair<WaCredential, String>? {
        val cachedKeyId = prefs.getWaKeyId().orEmpty()
        val cachedSecret = prefs.getWaKeySecret().orEmpty()
        if (cachedKeyId.isNotBlank() && cachedSecret.isNotBlank()) {
            return WaCredential.pair(cachedKeyId, cachedSecret) to prefs.getWaSessionId().orEmpty()
        }

        val uid = auth.currentUser()?.uid ?: return null
        val dto = firestore.getUser(uid).getOrNull() ?: return null
        if (dto.waKeyId.isNotBlank() && dto.waKeySecret.isNotBlank()) {
            prefs.setWaOwnKey(dto.waKeyId, dto.waKeySecret, dto.waSessionId)
            return WaCredential.pair(dto.waKeyId, dto.waKeySecret) to dto.waSessionId
        }
        return null
    }

    override suspend fun isLinked(): Boolean = credential() != null

    override suspend fun checkGateway(): Result<WhatsAppRepository.GatewayHealth> =
        api.checkHealth().map { WhatsAppRepository.GatewayHealth(it.whatsAppConnected) }

    override suspend fun getLinkInfo(): WhatsAppRepository.Link? {
        val (_, session) = credential() ?: return null
        return WhatsAppRepository.Link(
            linked = true,
            sessionId = session,
            phoneNumber = prefs.getWaPhoneNumber(),
        )
    }

    override suspend fun saveOwnKey(keyId: String, keySecret: String): Result<WhatsAppRepository.Link> =
        runCatching {
            val id = keyId.trim()
            val secret = keySecret.trim()
            require(id.isNotBlank() && secret.isNotBlank()) { "Enter both the key id and the secret" }

            val cred = WaCredential.pair(id, secret)
            // Prove the key works before storing it, so a typo cannot leave the
            // app configured-but-broken.
            val sessions = api.listSessions(cred).getOrThrow()
            val session = sessions.firstOrNull { it.status == "connected" } ?: sessions.firstOrNull()
            if (session == null) {
                error("This key works, but no WhatsApp number is linked to it yet. Link one on the portal, then try again.")
            }

            val uid = auth.currentUser()?.uid ?: error("Not signed in")
            firestore.saveWaOwnKey(uid, id, secret, session.sessionId).getOrThrow()
            prefs.setWaOwnKey(id, secret, session.sessionId)
            session.phoneNumber?.let { prefs.setWaPhoneNumber(it) }
            Log.i(TAG, "gateway key accepted for session ${session.sessionId} (status ${session.status})")

            WhatsAppRepository.Link(
                linked = true,
                sessionId = session.sessionId,
                phoneNumber = session.phoneNumber,
            )
        }

    override suspend fun clearLink() {
        prefs.clearWaLink()
        // Best-effort Firestore clear so other devices unlink too.
        auth.currentUser()?.uid?.let { firestore.saveWaOwnKey(it, "", "", "") }
    }

    override suspend fun startOwnLinking(): Result<Unit> {
        val (cred, session) = credential()
            ?: return Result.failure(IllegalStateException("WhatsApp is not set up yet"))
        return api.connectSession(cred, session)
    }

    override suspend fun getQr(): Result<String?> {
        val (cred, session) = credential()
            ?: return Result.failure(IllegalStateException("WhatsApp is not set up yet"))
        return api.getQr(cred, session)
    }

    override suspend fun getStatus(): Result<String> {
        val (cred, session) = credential()
            ?: return Result.failure(IllegalStateException("WhatsApp is not set up yet"))
        // The list endpoint is the honest one: the per-session status only knows
        // about sockets live in the process it happens to ask.
        return api.listSessions(cred).map { list ->
            list.firstOrNull { it.sessionId == session }?.status ?: "disconnected"
        }
    }

    override suspend fun deliveryStatuses(
        ids: List<String>,
    ): Result<Map<String, WhatsAppRepository.Delivery>> = runCatching {
        if (ids.isEmpty()) return@runCatching emptyMap()
        val (cred, _) = credential() ?: error("WhatsApp is not set up yet")
        val wanted = ids.toSet()
        api.recentMessages(cred).getOrThrow()
            .filter { it.id in wanted }
            .associate { it.id to WhatsAppRepository.Delivery(it.status, it.error) }
    }

    override suspend fun portalUrl(): String = config.get().portalUrl

    override suspend fun shareUrl(): String = config.get().shareUrl

    override suspend fun sendMessage(toPhone: String, message: String, recipientName: String?): Result<String> {
        val (cred, session) = credential()
            ?: return Result.failure(IllegalStateException("WhatsApp is not set up yet"))
        // Gateway wants digits only including country code (no '+').
        val digits = toPhone.filter { it.isDigit() }
        if (digits.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid recipient number"))
        }
        return api.sendMessage(cred, session, digits, message, recipientName)
    }
}
