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

    // ── Proving a phone number ────────────────────────────────────────────────

    // Read once per process. It changes only when the credential is rotated, and
    // a rotation is followed by whatever is verifying next time the app starts.
    @Volatile private var verifyCred: WaCredential? = null

    private suspend fun verificationCredential(): WaCredential {
        verifyCred?.let { return it }
        val (keyId, secret) = firestore.getVerificationCredential().getOrNull()
            ?: error("Phone verification is not available right now. Please try again shortly.")
        return WaCredential.pair(keyId, secret).also { verifyCred = it }
    }

    // The gateway wants digits with the country code and no plus sign.
    private fun digitsOf(phoneE164: String) = phoneE164.filter { it.isDigit() }

    override suspend fun verifyTarget(): Result<WhatsAppRepository.VerifyTarget> = runCatching {
        val target = api.verifyTarget(verificationCredential()).getOrThrow()
        WhatsAppRepository.VerifyTarget(
            phoneNumber = target.phoneNumber,
            phrase = target.phrase,
            waLink = target.waLink,
            codeLength = target.codeLength,
            resendAfterSeconds = target.resendAfterSeconds,
        )
    }

    override suspend fun verifyOptIn(phoneE164: String): Result<Boolean> = runCatching {
        api.verifyOptIn(verificationCredential(), digitsOf(phoneE164)).getOrThrow()
    }

    override suspend fun verifySendCode(phoneE164: String): Result<Unit> = runCatching {
        api.verifySendCode(verificationCredential(), digitsOf(phoneE164)).getOrThrow()
    }

    override suspend fun verifyCheckCode(
        phoneE164: String,
        code: String,
    ): Result<WhatsAppRepository.VerifyResult> = runCatching {
        val result = api.verifyCheckCode(verificationCredential(), digitsOf(phoneE164), code).getOrThrow()
        WhatsAppRepository.VerifyResult(result.verified, result.reason, result.attemptsRemaining)
    }

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
            proveNumberIfItMatches(uid, session.phoneNumber)

            WhatsAppRepository.Link(
                linked = true,
                sessionId = session.sessionId,
                phoneNumber = session.phoneNumber,
            )
        }

    /**
     * Connecting a gateway key proves the number for free. Linking one means
     * scanning a QR code with the WhatsApp on that phone, so the number the
     * gateway reports back is a number this person demonstrably holds. Anyone
     * outside Pakistan gets their number confirmed here rather than by a code,
     * because the one device that sends codes cannot reach them.
     */
    private suspend fun proveNumberIfItMatches(uid: String, sessionPhone: String?) {
        val linked = sessionPhone?.filter { it.isDigit() }.orEmpty()
        if (linked.isBlank()) return
        val dto = firestore.getUser(uid).getOrNull() ?: return
        if (dto.phoneVerified) return
        if (dto.phoneNumber.filter { it.isDigit() } != linked) {
            Log.i(TAG, "linked WhatsApp number is not the account's number, leaving it unverified")
            return
        }
        firestore.markPhoneVerified(uid)
            .onSuccess {
                Log.i(TAG, "account number proven by the WhatsApp number its gateway key is linked to")
                firestore.publishPhoneDirectoryEntry(dto.phoneNumber, uid, dto.name)
            }
            .onFailure { Log.w(TAG, "could not record the proven number: ${it.message}") }
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

    override suspend fun portalUrl(): String = config.get().portalUrl

    override suspend fun sendMessage(toPhone: String, message: String, recipientName: String?): Result<Unit> {
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
