package com.captainzonks.grodtv.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.captainzonks.grodtv.piped.Quality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    val pipedApiUrl: String,
    val defaultQuality: Quality,
    val apiPin: String,
) {
    companion object {
        val Default = Settings(
            pipedApiUrl = "https://tubeapi.zonks.org",
            defaultQuality = Quality.P1080,
            apiPin = "",
        )
    }
}

class SettingsStore(private val ds: DataStore<Preferences>) {
    val flow: Flow<Settings> = ds.data.map { prefs ->
        Settings(
            pipedApiUrl = prefs[KeyPipedApi] ?: Settings.Default.pipedApiUrl,
            defaultQuality = prefs[KeyQuality]?.let(Quality::parse) ?: Settings.Default.defaultQuality,
            apiPin = prefs[KeyApiPin] ?: Settings.Default.apiPin,
        )
    }

    suspend fun setPipedApiUrl(url: String) {
        ds.edit { it[KeyPipedApi] = url.trimEnd('/') }
    }

    suspend fun setDefaultQuality(quality: Quality) {
        ds.edit { it[KeyQuality] = quality.label }
    }

    suspend fun setApiPin(pin: String) {
        ds.edit { it[KeyApiPin] = pin }
    }

    companion object {
        private val KeyPipedApi = stringPreferencesKey("piped_api_url")
        private val KeyQuality = stringPreferencesKey("default_quality")
        private val KeyApiPin = stringPreferencesKey("api_pin")
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "grod_tv_settings")

fun Context.settingsStore(): SettingsStore = SettingsStore(settingsDataStore)
