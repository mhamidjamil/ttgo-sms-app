package com.textgate.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.textgate.app.domain.model.PlacePresence
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
        // Which half of the History page opens first: "automated" or "manual".
        private val KEY_DEFAULT_HISTORY_TAB = stringPreferencesKey("default_history_tab")

        // Arrival presence, held locally because it decides whether a message is
        // sent. The Firestore copy of the user document falls back to an offline
        // cache that can be hours stale, which is not something an alert may
        // depend on.
        private fun presenceKey(placeId: String) = stringPreferencesKey("presence_$placeId")
        // What the surroundings sounded like while standing at this place, so a
        // later scan can be compared against it rather than against a guess.
        private fun placeEnvironmentKey(placeId: String) =
            stringPreferencesKey("place_environment_$placeId")
        private val KEY_ENVIRONMENT = stringPreferencesKey("environment_fingerprint")
        private val KEY_LAST_OBSERVED_AT = longPreferencesKey("last_observed_at")

        const val HISTORY_TAB_AUTOMATED = "automated"
        const val HISTORY_TAB_MANUAL = "manual"
    }

    suspend fun getPresence(placeId: String): PlacePresence =
        PlacePresence.decode(context.dataStore.data.first()[presenceKey(placeId)])

    suspend fun setPresence(placeId: String, presence: PlacePresence) {
        context.dataStore.edit { it[presenceKey(placeId)] = presence.encode() }
    }

    // Everything heard on the last successful scan, saved or not. This is what
    // makes "the surroundings changed" a testable statement instead of a guess,
    // so a router power cut does not read as having left the building.
    suspend fun getEnvironment(): Set<String> =
        context.dataStore.data.first()[KEY_ENVIRONMENT]
            ?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()

    suspend fun setEnvironment(bssids: Set<String>) {
        context.dataStore.edit { it[KEY_ENVIRONMENT] = bssids.joinToString(",") }
    }

    suspend fun getPlaceEnvironment(placeId: String): Set<String> =
        context.dataStore.data.first()[placeEnvironmentKey(placeId)]
            ?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()

    suspend fun setPlaceEnvironment(placeId: String, bssids: Set<String>) {
        context.dataStore.edit { it[placeEnvironmentKey(placeId)] = bssids.joinToString(",") }
    }

    suspend fun getLastObservedAt(): Long =
        context.dataStore.data.first()[KEY_LAST_OBSERVED_AT] ?: 0L

    suspend fun setLastObservedAt(atMillis: Long) {
        context.dataStore.edit { it[KEY_LAST_OBSERVED_AT] = atMillis }
    }

    suspend fun getDefaultHistoryTab(): String =
        context.dataStore.data.first()[KEY_DEFAULT_HISTORY_TAB] ?: HISTORY_TAB_AUTOMATED

    suspend fun setDefaultHistoryTab(tab: String) {
        context.dataStore.edit { it[KEY_DEFAULT_HISTORY_TAB] = tab }
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
