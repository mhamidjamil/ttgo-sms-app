package com.textgate.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.textgate.app.domain.model.SettingsChange

data class SettingsChangeDto(
    @DocumentId val id: String = "",
    val field: String = "",
    @get:PropertyName("old_value") @set:PropertyName("old_value")
    var oldValue: String = "",
    @get:PropertyName("new_value") @set:PropertyName("new_value")
    var newValue: String = "",
    @get:PropertyName("changed_at") @set:PropertyName("changed_at")
    var changedAt: Timestamp? = null,
) {
    fun toDomain() = SettingsChange(
        id = id,
        field = field,
        oldValue = oldValue,
        newValue = newValue,
        changedAt = changedAt?.toDate(),
    )
}
