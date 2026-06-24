package org.branneman.health.ui

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
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.MealTemplateEntity
import java.time.OffsetDateTime
import kotlin.math.roundToInt

class TemplateListViewModel private constructor(
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

    private val _lastLogged = MutableStateFlow<LogEntryEntity?>(null)

    val templates: StateFlow<List<MealTemplateEntity>> = db.mealTemplateDao().observeAll()
        .map { list ->
            val withKcal = list.filter { it.quickAddKcal != null }
            val pinned   = withKcal.filter { it.sortOrder != null }.sortedBy { it.sortOrder }
            val unpinned = withKcal.filter { it.sortOrder == null }.sortedBy { it.name }
            pinned + unpinned
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ingredientTemplates: StateFlow<List<MealTemplateEntity>> = db.mealTemplateDao().observeAll()
        .map { list -> list.filter { it.quickAddKcal == null }.sortedBy { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun logFromTemplate(template: MealTemplateEntity, multiplier: Float) {
        val baseKcal = template.quickAddKcal ?: return
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = OffsetDateTime.now().toString(),
                mealType      = "unknown",
                quickAddKcal  = (baseKcal * multiplier).roundToInt(),
                quickAddLabel = template.name,
            )
            db.logEntryDao().upsert(entity)
            _lastLogged.value = entity
        }
    }

    fun undoLog() {
        viewModelScope.launch {
            _lastLogged.value?.let { entity ->
                db.logEntryDao().deleteById(entity.id)
                _lastLogged.value = null
            }
        }
    }
}
