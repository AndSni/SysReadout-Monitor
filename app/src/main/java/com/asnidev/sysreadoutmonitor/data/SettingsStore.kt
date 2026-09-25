package com.asnidev.sysreadoutmonitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("srm")

class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("monitor")

    val prefs: Flow<MonitorPrefs> = context.dataStore.data.map { MonitorPrefs.fromJson(it[key]) }

    suspend fun update(transform: (MonitorPrefs) -> MonitorPrefs) {
        context.dataStore.edit { p -> p[key] = transform(MonitorPrefs.fromJson(p[key])).toJson() }
    }
}
