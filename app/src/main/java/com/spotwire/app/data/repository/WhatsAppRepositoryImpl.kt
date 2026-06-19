package com.spotwire.app.data.repository

import android.util.Log
import com.spotwire.app.data.firebase.FirebaseAuthDataSource
import com.spotwire.app.data.firebase.FirestoreDataSource
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.data.whatsapp.WaConfigProvider
import com.spotwire.app.data.whatsapp.WaCredential
import com.spotwire.app.data.whatsapp.WhatsAppApi
import com.spotwire.app.domain.repository.WhatsAppRepository
import com.spotwire.app.domain.repository.WhatsAppRepository.Companion.MODE_OWN
import com.spotwire.app.domain.repository.WhatsAppRepository.Companion.MODE_SHARED

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
        val cachedSession = prefs.getWaSessionId().orEmpty()
        if (cachedKeyId.isNotBlank() && cachedSecret.isNotBlank()) {
            return WaCredential.pair(cachedKeyId, cachedSecret) to cachedSession
        }
        val cachedApiKey = prefs.getWaApiKey().orEmpty()
        if (cachedApiKey.isNotBlank() && cachedSession.isNotBlank()) {
            return WaCredential.legacy(cachedApiKey) to cachedSession
        }

        val uid = auth.currentUser()?.uid ?: return null
        val dto = firestore.getUser(uid).getOrNull() ?: return null
        prefs.setWaMode(dto.waMode.ifBlank { MODE_SHARED })
        if (dto.waKeyId.isNotBlank() && dto.waKeySecret.isNotBlank()) {
            prefs.setWaOwnKey(dto.waKeyId, dto.waKeySecret, dto.waSessionId)
            return WaCredential.pair(dto.waKeyId, dto.waKeySecret) to dto.waSessionId
        }
        if (dto.waApiKey.isNotBlank() && dto.waSessionId.isNotBlank()) {
            prefs.setWaLink(dto.waApiKey, dto.waSessionId)
            return WaCredential.legacy(dto.waApiKey) to dto.waSessionId
        }
        return null
    }

    override suspend fun isLinked(): Boolean = credential() != null

    override suspend fun getLinkInfo(): WhatsAppRepository.Link? {
        val (cred, session) = credential() ?: return null
        return WhatsAppRepository.Link(
            linked = true,
            ownKey = cred.isPair,
            sessionId = session,
            phoneNumber = prefs.getWaPhoneNumber(),
        )
    }

    override suspend fun ensureProvisioned(): Result<Boolean> = runCatching {
        val uid = auth.currentUser()?.uid ?: return@runCatching false
        if (credential() != null) return@runCatching true

        val dto = firestore.getUser(uid).getOrNull() ?: return@runCatching false
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
            // A portal key only ever sends from its own number, so the shared
            // sender is not an option for it.
            setMode(MODE_OWN)
            Log.i(TAG, "gateway key accepted for session ${session.sessionId} (status ${session.status})")

            WhatsAppRepository.Link(
                linked = true,
                ownKey = true,
                sessionId = session.sessionId,
                phoneNumber = session.phoneNumber,
            )
        }

    override suspend fun clearLink() {
        prefs.clearWaLink()
        // Best-effort Firestore clear so other devices unlink too.
        auth.currentUser()?.uid?.let {
            firestore.saveWaLink(it, "", "")
            firestore.saveWaOwnKey(it, "", "", "")
        }
    }

    override suspend fun getMode(): String {
        prefs.getWaMode()?.takeIf { it.isNotBlank() }?.let { return it }
        val uid = auth.currentUser()?.uid ?: return MODE_SHARED
        val mode = firestore.getUser(uid).getOrNull()?.waMode?.ifBlank { MODE_SHARED } ?: MODE_SHARED
        prefs.setWaMode(mode)
        return mode
    }

    override suspend fun setMode(mode: String): Result<Unit> = runCatching {
        require(mode == MODE_SHARED || mode == MODE_OWN) { "Unknown WhatsApp mode: $mode" }
        prefs.setWaMode(mode)
        val uid = auth.currentUser()?.uid ?: error("Not signed in")
        firestore.saveWaMode(uid, mode).getOrThrow()
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
        // For a portal key the list endpoint is the honest one: the per-session
        // status only knows about sockets live in the process it asks.
        if (cred.isPair) {
            return api.listSessions(cred).map { list ->
                list.firstOrNull { it.sessionId == session }?.status ?: "disconnected"
            }
        }
        return api.getSessionStatus(cred, session).map { it.status }
    }

    override suspend fun getSharedConnected(): Result<Boolean> {
        val (cred, _) = credential()
            ?: return Result.failure(IllegalStateException("WhatsApp is not set up yet"))
        return api.getSharedStatus(cred)
    }

    override suspend fun portalUrl(): String = config.get().portalUrl

    override suspend fun sendMessage(toPhone: String, message: String, recipientName: String?): Result<Unit> {
        val (cred, session) = credential()
            ?: return Result.failure(IllegalStateException("WhatsApp is not set up yet"))
        // Gateway wants digits only including country code (no '+').
        val digits = toPhone.filter { it.isDigit() }
        if (digits.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid recipient number"))
        }
        // A portal key is tied to the user's own number and the gateway refuses
        // it on the shared sender, so only an SSO key can take that route.
        return if (cred.isPair || getMode() == MODE_OWN) {
            api.sendMessage(cred, session, digits, message, recipientName)
        } else {
            api.sendShared(cred, digits, message, recipientName)
        }
    }
}
