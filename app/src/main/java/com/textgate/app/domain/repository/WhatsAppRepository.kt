package com.textgate.app.domain.repository

interface WhatsAppRepository {

    companion object {
        const val MODE_SHARED = "shared" // app's WhatsApp number — zero setup (default)
        const val MODE_OWN = "own"       // user-linked WhatsApp (QR scan)
    }

    // What the WhatsApp screen needs to know about the gateway link, resolved
    // once so the screen never has to ask three separate questions.
    data class Link(
        val linked: Boolean,
        // The key came from the user's own portal account rather than SSO. Such
        // a key is tied to their own number, so the shared sender is not
        // available to it and the mode choice does not apply.
        val ownKey: Boolean,
        val sessionId: String,
        val phoneNumber: String?,
    )

    // True when the user has a usable gateway credential of either kind.
    suspend fun isLinked(): Boolean

    /**
     * SSO auto-provisioning, for gateways that have it enabled. No-op success
     * when a link already exists. Returns false when the user isn't eligible
     * yet (not both verified). Fails with the gateway's own message when it
     * declines — which is what surfaces "SSO provisioning is not configured on
     * this server" and sends the user to the manual portal key instead.
     */
    suspend fun ensureProvisioned(): Result<Boolean>

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

    // Sending mode: MODE_SHARED (default) or MODE_OWN.
    suspend fun getMode(): String
    suspend fun setMode(mode: String): Result<Unit>

    // Own-session linking (MODE_OWN): start the session, then poll the QR and
    // status until "connected".
    suspend fun startOwnLinking(): Result<Unit>
    suspend fun getQr(): Result<String?>           // base64 PNG data-URL, null while not ready
    suspend fun getStatus(): Result<String>        // own-session status
    suspend fun getSharedConnected(): Result<Boolean>

    /** Public address of the gateway portal, where a user signs up and mints a key. */
    suspend fun portalUrl(): String

    // Sends via the gateway using the active mode. toPhone may be E.164 (+92...)
    // — it is converted to the digits-only form the gateway expects.
    suspend fun sendMessage(toPhone: String, message: String, recipientName: String? = null): Result<Unit>
}
