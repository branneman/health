package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.MealTemplateItemEntity
import java.util.UUID
import kotlin.math.roundToInt
// Ingredient is defined in BuildFromScratchViewModel.kt but reused here

class EditIngredientTemplateViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db          = (application as HealthApplication).db,
        tokenStore  = TokenStore(application.authDataStore),
    )

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _ingredients = MutableStateFlow<List<Ingredient>>(emptyList())
    val ingredients: StateFlow<List<Ingredient>> = _ingredients

    val totalKcal: StateFlow<Int> = _ingredients
        .map { list -> list.sumOf { (item, grams) -> (item.kcalPer100g * grams / 100.0).roundToInt() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private var templateId: String? = null

    fun loadTemplate(id: String?) {
        templateId = id
        _name.value = ""
        _ingredients.value = emptyList()
        if (id == null) return
        viewModelScope.launch {
            val template = db.mealTemplateDao().getById(id) ?: return@launch
            _name.value = template.name
            val items = db.mealTemplateDao().getItemsForTemplate(id)
            val entities = items.mapNotNull { item ->
                db.foodItemDao().getById(item.foodItemId)?.let { food ->
                    Ingredient(food, item.grams)
                }
            }
            _ingredients.value = entities
        }
    }

    fun onNameChange(value: String) { _name.value = value }

    fun addIngredient(item: FoodItemEntity, grams: Double) {
        _ingredients.value = _ingredients.value + Ingredient(item, grams)
    }

    fun removeAt(index: Int) {
        _ingredients.value = _ingredients.value.toMutableList().also { it.removeAt(index) }
    }

    fun save() {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            saveWith(userId)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = templateId ?: return
        viewModelScope.launch {
            db.mealTemplateDao().updateSyncStatus(id, SyncStatus.PENDING_DELETE)
            onDeleted()
        }
    }

    private fun saveWith(userId: String) {
        viewModelScope.launch {
            val id = templateId ?: UUID.randomUUID().toString().also { templateId = it }
            db.mealTemplateDao().upsert(
                MealTemplateEntity(
                    id           = id,
                    userId       = userId,
                    name         = _name.value.trim(),
                    sortOrder    = null,
                    quickAddKcal = null,
                    syncStatus   = SyncStatus.PENDING_CREATE,
                )
            )
            db.mealTemplateDao().deleteItemsForTemplate(id)
            _ingredients.value.forEachIndexed { index, ingredient ->
                db.mealTemplateDao().upsertItem(
                    MealTemplateItemEntity(
                        templateId = id,
                        foodItemId = ingredient.item.id,
                        grams      = ingredient.grams,
                        sortOrder  = index,
                    )
                )
            }
        }
    }

    internal companion object {
        fun forTest(
            application: Application,
            db:          HealthDatabase,
            tokenStore:  TokenStore,
        ) = EditIngredientTemplateViewModel(application, db, tokenStore)
    }
}
