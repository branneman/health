package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity

class MealButtonsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as HealthApplication).db
    private val tokenStore = TokenStore(application.authDataStore)

    private val _draft = MutableStateFlow<List<MealTemplateEntity>?>(null)
    val draft: StateFlow<List<MealTemplateEntity>?> = _draft

    private var userId: String? = null

    init {
        viewModelScope.launch {
            userId = tokenStore.tokenFlow.first()?.userId
            _draft.value = db.mealTemplateDao().observePinned().first()
        }
    }

    fun addButton(name: String, kcal: Int) {
        val uid = userId ?: return
        val current = _draft.value ?: return
        val nextOrder = (current.maxOfOrNull { it.sortOrder ?: -1 } ?: -1) + 1
        _draft.value = current + MealTemplateEntity(
            userId       = uid,
            name         = name,
            sortOrder    = nextOrder,
            quickAddKcal = kcal,
            syncStatus   = SyncStatus.PENDING_CREATE,
        )
    }

    fun removeButton(index: Int) {
        val current = _draft.value?.toMutableList() ?: return
        current.removeAt(index)
        _draft.value = current.reindexed()
    }

    fun moveUp(index: Int) {
        if (index == 0) return
        val current = _draft.value?.toMutableList() ?: return
        val tmp = current[index - 1]; current[index - 1] = current[index]; current[index] = tmp
        _draft.value = current.reindexed()
    }

    fun moveDown(index: Int) {
        val current = _draft.value?.toMutableList() ?: return
        if (index >= current.size - 1) return
        val tmp = current[index + 1]; current[index + 1] = current[index]; current[index] = tmp
        _draft.value = current.reindexed()
    }

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
