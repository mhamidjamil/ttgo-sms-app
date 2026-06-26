package com.textgate.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.textgate.app.domain.model.AccountLink
import com.textgate.app.domain.model.LinkPermissions
import com.textgate.app.domain.model.LinkState

data class AccountLinkDto(
    @DocumentId val otherUid: String = "",
    @get:PropertyName("other_name") @set:PropertyName("other_name")
    var otherName: String = "",
    @get:PropertyName("other_phone") @set:PropertyName("other_phone")
    var otherPhone: String = "",
    val state: String = "pending_incoming",
    @get:PropertyName("perm_auto_updates") @set:PropertyName("perm_auto_updates")
    var permAutoUpdates: Boolean = false,
    @get:PropertyName("perm_request_location") @set:PropertyName("perm_request_location")
    var permRequestLocation: Boolean = false,
) {
    fun toDomain() = AccountLink(
        otherUid = otherUid,
        otherName = otherName,
        otherPhone = otherPhone,
        state = LinkState.from(state),
        permissions = LinkPermissions(
            autoLocationUpdates = permAutoUpdates,
            requestLocation = permRequestLocation,
        ),
    )
}
