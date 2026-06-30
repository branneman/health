package org.branneman.health.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.LogEntryEntity

class QuickAddViewModel private constructor(
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

    private val _lastAdded = MutableStateFlow<LogEntryEntity?>(null)

    fun logQuickAdd(kcal: Int, label: String?, loggedAt: String) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val datePrefix = loggedAt.take(10)
            val sortOrder = db.logEntryDao().maxSortOrderForDate(userId, "$datePrefix%") + 1
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = loggedAt,
                mealType      = "unknown",
                quickAddKcal  = kcal,
                quickAddLabel = label?.trim()?.ifEmpty { null },
                sortOrder     = sortOrder,
            )
            db.logEntryDao().upsert(entity)
            _lastAdded.value = entity
        }
    }

    fun undoLog() {
        viewModelScope.launch {
            _lastAdded.value?.let { entity ->
                db.logEntryDao().deleteById(entity.id)
                _lastAdded.value = null
            }
        }
    }
}
