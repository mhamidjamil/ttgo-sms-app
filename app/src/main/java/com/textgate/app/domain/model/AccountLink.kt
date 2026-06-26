package com.textgate.app.domain.model

// A link needs approval from both sides before it does anything, so each side
// keeps its own document and its own state for the same pairing.
enum class LinkState(val firestoreValue: String) {
    // I sent the invite and am waiting for them.
    PENDING_OUTGOING("pending_outgoing"),
    // They invited me and I have not answered yet.
    PENDING_INCOMING("pending_incoming"),
    ACTIVE("active"),
    DECLINED("declined");

    companion object {
        fun from(value: String?): LinkState =
            entries.firstOrNull { it.firestoreValue == value } ?: PENDING_INCOMING
    }
}

// What the linked person is allowed to do with MY location. Stored on my own
// side of the link, so revoking is always a write to my own document.
data class LinkPermissions(
    // Push my arrival alerts to them automatically.
    val autoLocationUpdates: Boolean = true,
    // Let them ask where I am right now.
    val requestLocation: Boolean = false,
)

data class AccountLink(
    val otherUid: String,
    val otherName: String,
    val otherPhone: String,
    val state: LinkState,
    val permissions: LinkPermissions = LinkPermissions(),
)
