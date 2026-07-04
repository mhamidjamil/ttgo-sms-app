package com.textgate.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "textgate_prefs")

class PreferencesDataSource(private val context: Context) {

    companion object {
        private val KEY_CACHED_UID = stringPreferencesKey("cached_uid")
        // WhatsApp gateway link — the API key is a secret, so it lives in local
        // DataStore only (never in Firestore).
        private val KEY_WA_API_KEY = stringPreferencesKey("wa_api_key")
        private val KEY_WA_SESSION_ID = stringPreferencesKey("wa_session_id")
        // Anti-spam state (send-OTP cooldowns) — keyed per channel ("phone"/"email").
        private fun otpSentAtKey(channel: String) = longPreferencesKey("last_otp_sent_at_$channel")
    }

    suspend fun getOtpSentAt(channel: String): Long? =
        context.dataStore.data.first()[otpSentAtKey(channel)]

    suspend fun setOtpSentAt(channel: String, atMillis: Long) {
        context.dataStore.edit { it[otpSentAtKey(channel)] = atMillis }
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

    suspend fun clearWaLink() {
        context.dataStore.edit {
            it.remove(KEY_WA_API_KEY)
            it.remove(KEY_WA_SESSION_ID)
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
