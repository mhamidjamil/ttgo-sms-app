package com.textgate.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "textgate_prefs")

class PreferencesDataSource(private val context: Context) {

    companion object {
        private val KEY_CACHED_UID = stringPreferencesKey("cached_uid")
    }

    suspend fun getCachedUid(): String? =
        context.dataStore.data.first()[KEY_CACHED_UID]

    suspend fun setCachedUid(uid: String) {
        context.dataStore.edit { it[KEY_CACHED_UID] = uid }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
