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

data class StoredToken(val token: String, val expiresAt: String, val userId: String)

class TokenStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_EXPIRES_AT = stringPreferencesKey("expires_at")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
    }

    val tokenFlow: Flow<StoredToken?> = dataStore.data.map { prefs ->
        val token = prefs[KEY_TOKEN]
        val expiresAt = prefs[KEY_EXPIRES_AT]
        val userId = prefs[KEY_USER_ID]
        if (token != null && expiresAt != null && userId != null) StoredToken(token, expiresAt, userId) else null
    }

    suspend fun save(token: String, expiresAt: String, userId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = token
            prefs[KEY_EXPIRES_AT] = expiresAt
            prefs[KEY_USER_ID] = userId
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
