package org.branneman.health.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.polarDataStore: DataStore<Preferences> by preferencesDataStore(name = "polar_prefs")

private val POLAR_SETUP_SHOWN = booleanPreferencesKey("polar_setup_shown")

class PolarPreferences(private val dataStore: DataStore<Preferences>) {
    val polarSetupShownFlow: Flow<Boolean> =
        dataStore.data.map { it[POLAR_SETUP_SHOWN] ?: false }

    suspend fun markPolarSetupShown() {
        dataStore.edit { it[POLAR_SETUP_SHOWN] = true }
    }
}
