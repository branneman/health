package org.branneman.health.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.dao.LogEntryWithKcal
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.ShortcutEntity
import org.branneman.health.util.effectiveDate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db = (application as HealthApplication).db,
        tokenStore = TokenStore(application.authDataStore),
    )

    internal constructor(db: HealthDatabase, tokenStore: TokenStore) : this(
        application = Application(),
        db = db,
        tokenStore = tokenStore,
    )

    private val _undoPending = MutableStateFlow<Pair<LogEntryEntity, SyncStatus>?>(null)

    private val _selectedDate = MutableStateFlow(effectiveDate())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    fun setSelectedDate(date: LocalDate) { _selectedDate.value = date }

    internal fun loggedAtForSelectedDate(): String {
        val date = _selectedDate.value
        return if (date == effectiveDate()) {
            OffsetDateTime.now().toString()
        } else {
            val noon   = date.atTime(12, 0)
            val offset = ZoneId.systemDefault().rules.getOffset(noon)
            OffsetDateTime.of(noon, offset).toString()
        }
    }

    private val userId: Flow<String> = tokenStore.tokenFlow.mapNotNull { it?.userId }

    val pinnedTemplates: StateFlow<List<MealTemplateEntity>> = db.mealTemplateDao().observePinned()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val entries: StateFlow<List<LogEntryWithKcal>> =
        combine(_selectedDate, userId) { date, uid ->
            db.logEntryDao().observeForDate(uid, "$date%")
        }
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shortcuts: StateFlow<List<ShortcutEntity>> = db.shortcutDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun logFromTemplate(template: MealTemplateEntity) {
        val kcal = template.quickAddKcal ?: return
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = loggedAtForSelectedDate(),
                mealType      = "unknown",
                quickAddKcal  = kcal,
                quickAddLabel = template.name,
            )
            db.logEntryDao().upsert(entity)
            _undoPending.value = entity to SyncStatus.PENDING_CREATE
        }
    }

    fun logFromShortcut(shortcut: ShortcutEntity) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = loggedAtForSelectedDate(),
                mealType      = "unknown",
                quickAddKcal  = shortcut.kcal,
                quickAddLabel = "${shortcut.emoji} ${shortcut.label}",
            )
            db.logEntryDao().upsert(entity)
            _undoPending.value = entity to SyncStatus.PENDING_CREATE
        }
    }

    fun logSingleItem(label: String, kcal: Int) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = loggedAtForSelectedDate(),
                mealType      = "unknown",
                quickAddKcal  = kcal,
                quickAddLabel = label,
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

    fun editEntry(entry: LogEntryEntity, kcal: Int, label: String?) {
        viewModelScope.launch {
            db.logEntryDao().updateQuickAdd(entry.id, kcal, label?.trim()?.ifEmpty { null })
        }
    }
}
