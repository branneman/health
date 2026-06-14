package org.branneman.health.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.MealTemplateEntity
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as HealthApplication).db
    private val tokenStore = TokenStore(application.authDataStore)

    private val _undoPending = MutableStateFlow<Pair<LogEntryEntity, SyncStatus>?>(null)

    val pinnedTemplates: StateFlow<List<MealTemplateEntity>> = db.mealTemplateDao().observePinned()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val entries: StateFlow<List<LogEntryEntity>> =
        combine(db.logEntryDao().observeAll(), dateFlow()) { all, today ->
            all.filter { it.loggedAt.startsWith(today.toString()) }
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

    fun logFromTemplate(template: MealTemplateEntity) {
        val kcal = template.quickAddKcal ?: return
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = OffsetDateTime.now().toString(),
                mealType      = "unknown",
                quickAddKcal  = kcal,
                quickAddLabel = template.name,
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

internal fun dateFlow(clock: Clock = Clock.systemDefaultZone()): Flow<LocalDate> = flow {
    while (true) {
        val today = LocalDate.now(clock)
        emit(today)
        val millisUntilMidnight = ChronoUnit.MILLIS.between(
            LocalDateTime.now(clock),
            today.plusDays(1).atStartOfDay(),
        )
        kotlinx.coroutines.delay(millisUntilMidnight)
    }
}
