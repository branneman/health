package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity

class MealButtonsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as HealthApplication).db
    private val tokenStore = TokenStore(application.authDataStore)

    private var userId: String? = null

    val draft: StateFlow<List<MealTemplateEntity>?> = flow<List<MealTemplateEntity>?> {
        emit(null)
        userId = tokenStore.tokenFlow.first()?.userId
        emitAll(db.mealTemplateDao().observePinned())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(rows: List<MealTemplateEntity>) {
        val uid = userId ?: return
        viewModelScope.launch {
            db.mealTemplateDao().deleteAllItemsForUser(uid)
            db.mealTemplateDao().deleteAllForUser(uid)
            rows.forEachIndexed { i, entity ->
                db.mealTemplateDao().upsert(entity.copy(userId = uid, sortOrder = i,
                    syncStatus = SyncStatus.PENDING_CREATE))
            }
        }
    }
}

internal fun List<MealTemplateEntity>.reindexed(): List<MealTemplateEntity> =
    mapIndexed { i, e -> e.copy(sortOrder = i) }
