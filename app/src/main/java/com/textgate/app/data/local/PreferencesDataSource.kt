package com.textgate.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "textgate_prefs")

class PreferencesDataSource(private val context: Context) {

    companion object {
        private val KEY_CACHED_UID = stringPreferencesKey("cached_uid")
        private val KEY_MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        // WhatsApp gateway link — local cache of the per-user key/session. The
        // source of truth is the Firestore user doc (owner-only via rules), so
        // the link survives reinstall/sign-out and other devices.
        private val KEY_WA_API_KEY = stringPreferencesKey("wa_api_key")
        private val KEY_WA_SESSION_ID = stringPreferencesKey("wa_session_id")
        private val KEY_WA_MODE = stringPreferencesKey("wa_mode")
        // Anti-spam state (send-OTP cooldowns) — keyed per channel ("phone"/"email").
        private fun otpSentAtKey(channel: String) = longPreferencesKey("last_otp_sent_at_$channel")
        // Last date (YYYY-MM-DD) the user sent a quota-increase request.
        private val KEY_QUOTA_REQUEST_DATE = stringPreferencesKey("last_quota_request_date")
    }

    suspend fun getOtpSentAt(channel: String): Long? =
        context.dataStore.data.first()[otpSentAtKey(channel)]

    suspend fun setOtpSentAt(channel: String, atMillis: Long) {
        context.dataStore.edit { it[otpSentAtKey(channel)] = atMillis }
    }

    suspend fun getQuotaRequestDate(): String? =
        context.dataStore.data.first()[KEY_QUOTA_REQUEST_DATE]

    suspend fun setQuotaRequestDate(date: String) {
        context.dataStore.edit { it[KEY_QUOTA_REQUEST_DATE] = date }
    }

    suspend fun getCachedUid(): String? =
        context.dataStore.data.first()[KEY_CACHED_UID]

    suspend fun setCachedUid(uid: String) {
        context.dataStore.edit { it[KEY_CACHED_UID] = uid }
    }

    suspend fun getWaApiKey(): String? =
        context.dataStore.data.first()[KEY_WA_API_KEY]

    suspend fun getWaSessionId(): String? =
        context.dataStore.data.first()[KEY_WA_SESSION_ID]

    suspend fun setWaLink(apiKey: String, sessionId: String) {
        context.dataStore.edit {
            it[KEY_WA_API_KEY] = apiKey
            it[KEY_WA_SESSION_ID] = sessionId
        }
    }

    suspend fun getWaMode(): String? =
        context.dataStore.data.first()[KEY_WA_MODE]

    suspend fun setWaMode(mode: String) {
        context.dataStore.edit { it[KEY_WA_MODE] = mode }
    }

    suspend fun clearWaLink() {
        context.dataStore.edit {
            it.remove(KEY_WA_API_KEY)
            it.remove(KEY_WA_SESSION_ID)
        }
    }

    suspend fun getMonitoringEnabled(): Boolean =
        context.dataStore.data.first()[KEY_MONITORING_ENABLED] ?: false

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_MONITORING_ENABLED] = enabled }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
