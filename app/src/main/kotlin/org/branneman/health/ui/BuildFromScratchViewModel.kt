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
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.LogEntryItemEntity
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.MealTemplateItemEntity
import java.util.UUID
import kotlin.math.roundToInt

data class Ingredient(val item: FoodItemEntity, val grams: Double)

class BuildFromScratchViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db          = (application as HealthApplication).db,
        tokenStore  = TokenStore(application.authDataStore),
    )

    internal constructor(db: HealthDatabase, tokenStore: TokenStore) : this(
        application = Application(),
        db          = db,
        tokenStore  = tokenStore,
    )

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

    fun reset() {
        _ingredients.value = emptyList()
        _totalKcal.value = 0
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

    fun loadFromTemplate(templateId: String) {
        viewModelScope.launch {
            val items = db.mealTemplateDao().getItemsForTemplate(templateId)
            val loaded = items.mapNotNull { item ->
                db.foodItemDao().getById(item.foodItemId)?.let { food ->
                    Ingredient(food, item.grams)
                }
            }
            _ingredients.value = loaded
            recomputeTotal(loaded)
        }
    }

    fun log(mealType: String, loggedAt: String) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entryId = UUID.randomUUID().toString()
            db.logEntryDao().upsert(
                LogEntryEntity(
                    id            = entryId,
                    userId        = userId,
                    loggedAt      = loggedAt,
                    mealType      = mealType,
                    quickAddKcal  = null,
                    quickAddLabel = null,
                    syncStatus    = SyncStatus.PENDING_CREATE,
                )
            )
            for (ingredient in _ingredients.value) {
                db.logEntryDao().upsertItem(
                    LogEntryItemEntity(
                        logEntryId     = entryId,
                        foodItemId     = ingredient.item.id,
                        grams          = ingredient.grams,
                        kcalPer100g    = ingredient.item.kcalPer100g,
                        proteinPer100g = ingredient.item.proteinPer100g,
                        carbsPer100g   = ingredient.item.carbsPer100g,
                        fatPer100g     = ingredient.item.fatPer100g,
                    )
                )
            }
        }
    }

    fun saveAsTemplate(name: String) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val templateId = UUID.randomUUID().toString()
            db.mealTemplateDao().upsert(
                MealTemplateEntity(
                    id           = templateId,
                    userId       = userId,
                    name         = name,
                    sortOrder    = null,
                    quickAddKcal = null,
                    syncStatus   = SyncStatus.PENDING_CREATE,
                )
            )
            _ingredients.value.forEachIndexed { index, ingredient ->
                db.mealTemplateDao().upsertItem(
                    MealTemplateItemEntity(
                        templateId = templateId,
                        foodItemId = ingredient.item.id,
                        grams      = ingredient.grams,
                        sortOrder  = index,
                    )
                )
            }
        }
    }
}
