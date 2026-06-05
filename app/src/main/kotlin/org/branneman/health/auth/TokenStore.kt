package org.branneman.health.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

data class StoredToken(val token: String, val expiresAt: String)

class TokenStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_EXPIRES_AT = stringPreferencesKey("expires_at")
    }

    val tokenFlow: Flow<StoredToken?> = dataStore.data.map { prefs ->
        val token = prefs[KEY_TOKEN]
        val expiresAt = prefs[KEY_EXPIRES_AT]
        if (token != null && expiresAt != null) StoredToken(token, expiresAt) else null
    }

    suspend fun save(token: String, expiresAt: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = token
            prefs[KEY_EXPIRES_AT] = expiresAt
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
