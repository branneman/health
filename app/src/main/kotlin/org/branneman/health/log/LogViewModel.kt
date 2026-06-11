package org.branneman.health.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.LogEntryEntity
import java.time.LocalDate
import java.time.OffsetDateTime

class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as HealthApplication).db
    private val tokenStore = TokenStore(application.authDataStore)

    private val _undoPending = MutableStateFlow<Pair<LogEntryEntity, SyncStatus>?>(null)

    val entries: StateFlow<List<LogEntryEntity>> = db.logEntryDao().observeAll()
        .map { all ->
            val today = LocalDate.now().toString()
            all.filter { it.loggedAt.startsWith(today) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addEntry(kcalStr: String, label: String) {
        val kcal = kcalStr.trim().toIntOrNull()?.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = OffsetDateTime.now().toString(),
                mealType      = "unknown",
                quickAddKcal  = kcal,
                quickAddLabel = label.trim().ifEmpty { null },
            )
            db.logEntryDao().upsert(entity)
            _undoPending.value = entity to SyncStatus.PENDING_CREATE
        }
    }

    fun undoAdd() {
        viewModelScope.launch {
            _undoPending.value?.let { (entity, _) ->
                db.logEntryDao().deleteById(entity.id)
                _undoPending.value = null
            }
        }
    }

    fun deleteEntry(entry: LogEntryEntity) {
        viewModelScope.launch {
            _undoPending.value = entry to entry.syncStatus
            db.logEntryDao().updateSyncStatus(entry.id, SyncStatus.PENDING_DELETE)
        }
    }

    fun undoDelete() {
        viewModelScope.launch {
            _undoPending.value?.let { (entity, previousStatus) ->
                db.logEntryDao().updateSyncStatus(entity.id, previousStatus)
                _undoPending.value = null
            }
        }
    }
}
