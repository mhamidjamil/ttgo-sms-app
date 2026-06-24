package com.textgate.app.core.utils

import com.textgate.app.BuildConfig

object Paths {
    val SMS_JOBS: String = BuildConfig.SMS_JOBS_PATH
    val USERS: String = BuildConfig.USERS_PATH
    val DEVICE_DOC: String = BuildConfig.DEVICE_DOC_PATH
    const val HISTORY_SUB: String = "history"
    const val AUTO_HISTORY_SUB: String = "auto_history"
    const val FREE_SMS_QUOTA_FIELD: String = "free_sms_quota"
}

object Quota {
    val UNVERIFIED: Int = BuildConfig.UNVERIFIED_QUOTA
    val PARTIAL_VERIFIED: Int = BuildConfig.PARTIAL_VERIFIED_QUOTA
    val HISTORY_POLL_SECONDS: Int = BuildConfig.HISTORY_POLL_INTERVAL_SECONDS
}

object WifiConfig {
    val STABILITY_MINUTES: Int = BuildConfig.WIFI_STABILITY_MINUTES
    val MIN_STABILITY_MINUTES: Int = BuildConfig.MIN_WIFI_STABILITY_MINUTES
}
