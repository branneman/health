package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity

class TemplatesViewModel private constructor(
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

    val templates: StateFlow<List<MealTemplateEntity>> = db.mealTemplateDao().observeAll()
        .map { list ->
            val pinned   = list.filter { it.sortOrder != null }.sortedBy { it.sortOrder }
            val unpinned = list.filter { it.sortOrder == null }.sortedBy { it.name }
            pinned + unpinned
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, kcal: Int) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            db.mealTemplateDao().upsert(
                MealTemplateEntity(
                    userId       = userId,
                    name         = name.trim(),
                    quickAddKcal = kcal,
                    syncStatus   = SyncStatus.PENDING_CREATE,
                )
            )
        }
    }

    fun update(id: String, name: String, kcal: Int) {
        viewModelScope.launch {
            val entity = db.mealTemplateDao().getById(id) ?: return@launch
            db.mealTemplateDao().upsert(
                entity.copy(
                    name         = name.trim(),
                    quickAddKcal = kcal,
                    syncStatus   = SyncStatus.PENDING_CREATE,
                )
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            db.mealTemplateDao().updateSyncStatus(id, SyncStatus.PENDING_DELETE)
        }
    }
}
