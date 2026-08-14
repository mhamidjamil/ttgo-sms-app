package com.spotwire.app.domain.repository

interface WhatsAppRepository {

    /**
     * The gateway answered. [whatsAppConnected] is null when it did not say
     * whether a WhatsApp number is linked, which older gateway builds do not.
     */
    data class GatewayHealth(val whatsAppConnected: Boolean?)

    data class VerifyTarget(
        val phoneNumber: String,
        val phrase: String,
        val waLink: String,
        val codeLength: Int,
        val resendAfterSeconds: Int,
    )

    data class VerifyResult(
        val verified: Boolean,
        val reason: String,
        val attemptsRemaining: Int?,
    )

    // What the WhatsApp screen needs to know about the gateway link, resolved
    // once so the screen never has to ask three separate questions.
    data class Link(
        val linked: Boolean,
        val sessionId: String,
        val phoneNumber: String?,
    )

    // True when the user has connected their own gateway key.
    suspend fun isLinked(): Boolean

    /**
     * Is the gateway answering at all, and does it have a WhatsApp number linked?
     * Needs no credential, so it can be asked before an account has one, and it
     * is what stops a new user being walked into a verification step that cannot
     * possibly finish.
     */
    suspend fun checkGateway(): Result<GatewayHealth>

    // ── Proving a phone number ────────────────────────────────────────────────
    //
    // These run on a credential the app fetches at run time and that the gateway
    // scopes to verification alone. A brand new account has no credential of its
    // own yet, which is the whole reason this one exists.

    /** The number to message and the words to send, or null when unavailable. */
    suspend fun verifyTarget(): Result<VerifyTarget>

    /** Has this number sent us the agreed words yet? */
    suspend fun verifyOptIn(phoneE164: String): Result<Boolean>

    /** Ask for a code. Fails with the reason when the number has not opted in. */
    suspend fun verifySendCode(phoneE164: String): Result<Unit>

    /** Check a typed code. */
    suspend fun verifyCheckCode(phoneE164: String, code: String): Result<VerifyResult>

    suspend fun clearLink()

    /**
     * Saves a key id + secret the user created on the gateway portal, after
     * proving it works: the gateway must accept it and report which WhatsApp
     * number it sends from. Nothing is stored when validation fails, so a typo
     * can never leave the app in a broken-but-configured state.
     */
    suspend fun saveOwnKey(keyId: String, keySecret: String): Result<Link>

    /** The current link, or null when no credential is stored. */
    suspend fun getLinkInfo(): Link?

    // Linking a number by QR from inside the app: start the session, then poll
    // the QR and the status until "connected".
    suspend fun startOwnLinking(): Result<Unit>
    suspend fun getQr(): Result<String?>           // base64 PNG data-URL, null while not ready
    suspend fun getStatus(): Result<String>        // the linked session's status

    /** Public address of the gateway portal, where a user signs up and mints a key. */
    suspend fun portalUrl(): String

    // Sends through the number this account's own key is linked to. toPhone may
    // be E.164 — it is converted to the digits-only form the gateway expects.
    suspend fun sendMessage(toPhone: String, message: String, recipientName: String? = null): Result<Unit>
}
