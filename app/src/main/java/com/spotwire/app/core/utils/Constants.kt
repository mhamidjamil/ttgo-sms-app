package com.spotwire.app.core.utils

import com.spotwire.app.BuildConfig

object Paths {
    val SMS_JOBS: String = BuildConfig.SMS_JOBS_PATH
    val USERS: String = BuildConfig.USERS_PATH
    val DEVICE_DOC: String = BuildConfig.DEVICE_DOC_PATH
    const val HISTORY_SUB: String = "history"
    const val AUTO_HISTORY_SUB: String = "auto_history"
    const val SETTINGS_HISTORY_SUB: String = "settings_history"
    const val LINKS_SUB: String = "links"
    const val LOCATION_REQUESTS_SUB: String = "location_requests"
    // Alerts delivered inside the app, written by a linked sender onto the
    // recipient's own account. Only what was sent to them, never the sender's log.
    const val INCOMING_ALERTS_SUB: String = "incoming_alerts"
    const val FREE_SMS_QUOTA_FIELD: String = "free_sms_quota"
    // Recipient-owned alert subscriptions, keyed by the recipient's E.164 number
    // so a link can be found months before that person installs the app.
    const val ALERT_SUBSCRIPTIONS: String = "alert_subscriptions"
    const val SENDERS_SUB: String = "senders"
    // uid + name only, keyed by verified number, so link invites can find an
    // account without opening up the user documents themselves.
    const val PHONE_DIRECTORY: String = "phone_directory"
}

object Quota {
    // Phone unverified → 0 SMS/day (hard rule, not configurable); phone verified
    // → assigned_quota from the device doc. See GetEffectiveQuotaUseCase.
    val HISTORY_POLL_SECONDS: Int = BuildConfig.HISTORY_POLL_INTERVAL_SECONDS
}

object WifiConfig {
    val STABILITY_MINUTES: Int = BuildConfig.WIFI_STABILITY_MINUTES
    val MIN_STABILITY_MINUTES: Int = BuildConfig.MIN_WIFI_STABILITY_MINUTES
}
