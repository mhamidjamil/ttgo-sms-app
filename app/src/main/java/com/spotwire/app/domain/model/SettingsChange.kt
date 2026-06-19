package com.spotwire.app.domain.model

import java.util.Date

// One audit line for a settings edit, so a value that disappears on its own can
// be traced to whatever wrote it. `field` is the human label shown in the list
// ("Guardian number", "Home WiFi"), not a Firestore key.
data class SettingsChange(
    val id: String = "",
    val field: String,
    val oldValue: String,
    val newValue: String,
    val changedAt: Date? = null,
)
