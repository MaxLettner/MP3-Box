package com.local.mp3box.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Folder grant and one-time prompt flags. Shuffle/repeat are **not** here:
 * they live in `playback_state` (see plan §5.3).
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    private object Keys {
        val folderUri = stringPreferencesKey("folder_uri")
        val batteryOptAsked = booleanPreferencesKey("battery_opt_asked")
        val notifPermAsked = booleanPreferencesKey("notif_perm_asked")
    }

    val folderUri: Flow<String?> = dataStore.data.map { it[Keys.folderUri] }
    val batteryOptAsked: Flow<Boolean> = dataStore.data.map { it[Keys.batteryOptAsked] ?: false }
    val notifPermAsked: Flow<Boolean> = dataStore.data.map { it[Keys.notifPermAsked] ?: false }

    suspend fun setFolderUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.folderUri) else prefs[Keys.folderUri] = uri
        }
    }

    suspend fun setBatteryOptAsked(asked: Boolean) {
        dataStore.edit { it[Keys.batteryOptAsked] = asked }
    }

    suspend fun setNotifPermAsked(asked: Boolean) {
        dataStore.edit { it[Keys.notifPermAsked] = asked }
    }
}
