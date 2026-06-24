package org.branneman.health.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.LogEntryItemEntity
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.MealTemplateItemEntity
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.roundToInt

data class Ingredient(val item: FoodItemEntity, val grams: Double)

class BuildFromScratchViewModel(
    private val db: HealthDatabase,
) : ViewModel() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    private val _ingredients = MutableStateFlow<List<Ingredient>>(emptyList())
    val ingredients: StateFlow<List<Ingredient>> = _ingredients

    private val _totalKcal = MutableStateFlow(0)
    val totalKcal: StateFlow<Int> = _totalKcal

    val bailOutKcal: Int get() = _totalKcal.value

    private fun recomputeTotal(list: List<Ingredient>) {
        _totalKcal.value = list.sumOf { (item, grams) ->
            (item.kcalPer100g * grams / 100.0).roundToInt()
        }
    }

    fun addIngredient(item: FoodItemEntity, grams: Double) {
        val updated = _ingredients.value + Ingredient(item, grams)
        _ingredients.value = updated
        recomputeTotal(updated)
    }

    fun removeAt(index: Int) {
        val current = _ingredients.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _ingredients.value = current
            recomputeTotal(current)
        }
    }

    suspend fun log(mealType: String, userId: String) {
        val entryId = UUID.randomUUID().toString()
        val entry = LogEntryEntity(
            id            = entryId,
            userId        = userId,
            loggedAt      = OffsetDateTime.now().toString(),
            mealType      = mealType,
            quickAddKcal  = null,
            quickAddLabel = null,
            syncStatus    = SyncStatus.PENDING_CREATE,
        )
        db.logEntryDao().upsert(entry)

        for (ingredient in _ingredients.value) {
            val logItem = LogEntryItemEntity(
                logEntryId     = entryId,
                foodItemId     = ingredient.item.id,
                grams          = ingredient.grams,
                kcalPer100g    = ingredient.item.kcalPer100g,
                proteinPer100g = ingredient.item.proteinPer100g,
                carbsPer100g   = ingredient.item.carbsPer100g,
                fatPer100g     = ingredient.item.fatPer100g,
            )
            db.logEntryDao().upsertItem(logItem)
        }
    }

    suspend fun saveAsTemplate(name: String, userId: String) {
        val templateId = UUID.randomUUID().toString()
        val template = MealTemplateEntity(
            id           = templateId,
            userId       = userId,
            name         = name,
            sortOrder    = null,
            quickAddKcal = null,
            syncStatus   = SyncStatus.PENDING_CREATE,
        )
        db.mealTemplateDao().upsert(template)

        _ingredients.value.forEachIndexed { index, ingredient ->
            val templateItem = MealTemplateItemEntity(
                templateId = templateId,
                foodItemId = ingredient.item.id,
                grams      = ingredient.grams,
                sortOrder  = index,
            )
            db.mealTemplateDao().upsertItem(templateItem)
        }
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}
