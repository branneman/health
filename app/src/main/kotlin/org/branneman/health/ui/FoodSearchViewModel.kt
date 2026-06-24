package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.network.HealthApiClient
import java.util.UUID

data class FoodSearchResult(
    val entity: FoodItemEntity,
    val isPersonalCatalog: Boolean,
)

@OptIn(FlowPreview::class)
class FoodSearchViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
    private val api: HealthApiClient,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db          = (application as HealthApplication).db,
        tokenStore  = TokenStore(application.authDataStore),
        api         = HealthApiClient(),
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<FoodSearchResult>>(emptyList())
    val results: StateFlow<List<FoodSearchResult>> = _results

    private val _selectedItem = MutableStateFlow<FoodItemEntity?>(null)
    val selectedItem: StateFlow<FoodItemEntity?> = _selectedItem

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    init {
        viewModelScope.launch {
            _query.debounce(300).distinctUntilChanged().collect { q ->
                if (q.isBlank()) { _results.value = emptyList(); return@collect }
                val token = tokenStore.tokenFlow.first()?.token ?: return@collect
                val localResults = db.foodItemDao().searchByName(q)
                val remoteResults = runCatching { api.searchOfdCatalog(token, q) }
                    .onSuccess { _isOffline.value = false }
                    .onFailure { _isOffline.value = true }
                    .getOrDefault(emptyList())
                val localIds = localResults.map { it.id }.toSet()
                val remoteFoodItems = remoteResults
                    .filter { dto -> dto.id !in localIds }
                    .map { dto ->
                        FoodSearchResult(
                            entity = FoodItemEntity(
                                id             = dto.id,
                                userId         = tokenStore.tokenFlow.first()?.userId ?: "",
                                barcode        = dto.barcode,
                                name           = dto.name,
                                kcalPer100g    = dto.kcalPer100g,
                                proteinPer100g = dto.proteinPer100g,
                                carbsPer100g   = dto.carbsPer100g,
                                fatPer100g     = dto.fatPer100g,
                                source         = dto.source,
                                syncStatus     = SyncStatus.SYNCED,
                            ),
                            isPersonalCatalog = false,
                        )
                    }
                _results.value = localResults.map { FoodSearchResult(it, true) } + remoteFoodItems
            }
        }
    }

    fun onQueryChange(q: String) { _query.value = q }

    fun onBarcodeScanned(barcode: String) {
        viewModelScope.launch {
            val token  = tokenStore.tokenFlow.first()?.token ?: return@launch
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val existing = db.foodItemDao().getByBarcode(barcode)
            if (existing != null) { selectExisting(existing); return@launch }
            val dto = runCatching { api.lookupFoodByBarcode(token, barcode) }
                .onFailure { _isOffline.value = true }
                .getOrNull()
            if (dto != null) {
                val entity = FoodItemEntity(
                    id             = UUID.randomUUID().toString(),
                    userId         = userId,
                    barcode        = dto.barcode,
                    name           = dto.name,
                    kcalPer100g    = dto.kcalPer100g,
                    proteinPer100g = dto.proteinPer100g,
                    carbsPer100g   = dto.carbsPer100g,
                    fatPer100g     = dto.fatPer100g,
                    source         = dto.source,
                    syncStatus     = SyncStatus.PENDING_CREATE,
                )
                db.foodItemDao().upsert(entity)
                _selectedItem.value = entity
            }
        }
    }

    fun selectResult(result: FoodSearchResult) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            if (result.isPersonalCatalog) {
                selectExisting(result.entity)
            } else {
                val barcode = result.entity.barcode
                val existing = barcode?.let { db.foodItemDao().getByBarcode(it) }
                if (existing != null) {
                    selectExisting(existing)
                } else {
                    val entity = result.entity.copy(
                        id         = UUID.randomUUID().toString(),
                        userId     = userId,
                        syncStatus = SyncStatus.PENDING_CREATE,
                    )
                    db.foodItemDao().upsert(entity)
                    _selectedItem.value = entity
                }
            }
        }
    }

    fun createManual(name: String, kcalPer100g: Double, proteinPer100g: Double?, carbsPer100g: Double?, fatPer100g: Double?) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = FoodItemEntity(
                id             = UUID.randomUUID().toString(),
                userId         = userId,
                barcode        = null,
                name           = name,
                kcalPer100g    = kcalPer100g,
                proteinPer100g = proteinPer100g,
                carbsPer100g   = carbsPer100g,
                fatPer100g     = fatPer100g,
                source         = "manual",
                syncStatus     = SyncStatus.PENDING_CREATE,
            )
            db.foodItemDao().upsert(entity)
            _selectedItem.value = entity
        }
    }

    fun consumeSelectedItem() { _selectedItem.value = null }

    private fun selectExisting(entity: FoodItemEntity) { _selectedItem.value = entity }
}
