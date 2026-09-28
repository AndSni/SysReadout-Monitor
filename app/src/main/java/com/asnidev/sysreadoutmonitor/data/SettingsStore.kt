package com.asnidev.sysreadoutmonitor.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// A corrupt settings file is replaced by defaults: the app must start either way.
private val Context.dataStore by preferencesDataStore(
    name = "srm",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w("SettingsStore", "settings file was corrupt; starting from defaults", it)
        emptyPreferences()
    },
)

class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("monitor")

    private val data: Flow<Preferences> = context.dataStore.data.catch { e ->
        if (e !is IOException) throw e
        Log.w("SettingsStore", "couldn't read settings", e)
        emit(emptyPreferences())
    }

    val prefs: Flow<MonitorPrefs> = data.map { MonitorPrefs.fromJson(it[key]) }

    suspend fun update(transform: (MonitorPrefs) -> MonitorPrefs) {
        context.dataStore.edit { p -> p[key] = transform(MonitorPrefs.fromJson(p[key])).toJson() }
    }
}
