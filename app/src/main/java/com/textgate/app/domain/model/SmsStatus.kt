package com.textgate.app.domain.model

enum class SmsStatus(val firestoreValue: String, val displayLabel: String) {
    PENDING("pending", "Pending"),
    IN_PROGRESS("in_progress", "Sending"),
    SENT("sent", "Sent"),
    FAILED("failed", "Failed"),
    BLOCKED("blocked", "Blocked"),
    UNKNOWN("unknown", "Unknown");

    companion object {
        fun from(value: String?): SmsStatus =
            entries.firstOrNull { it.firestoreValue == value } ?: UNKNOWN
    }
}
