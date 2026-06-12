package org.branneman.health.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

private val KEY_LAST_SYNCED_AT = longPreferencesKey("last_synced_at")

val DataStore<Preferences>.lastSyncedAtFlow: Flow<Long?>
    get() = data.map { it[KEY_LAST_SYNCED_AT] }

suspend fun DataStore<Preferences>.saveLastSyncedAt(epochMillis: Long) {
    edit { it[KEY_LAST_SYNCED_AT] = epochMillis }
}
