package org.branneman.health.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.BodyWeightEntity
import org.branneman.health.db.entities.SportTonightEntity
import org.branneman.health.network.HealthApiClient
import org.branneman.health.onboarding.activityMultiplier
import org.branneman.health.onboarding.computeBmr
import java.time.LocalDate
import org.branneman.health.util.effectiveDate

data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,
    val caloriesOutSource: String = "estimate",
    val targetDeficit: Int = 0,
    val caloriesLeft: Int = 0,
    val budgetLabel: String = "left (estimated)",
    val sportTonight: SportTonightEntity? = null,
    val weightKgToday: Double? = null,
    val expectedTodaySport: Int? = null,
    val expectedTodayNonSport: Int? = null,
    val actualBurnedSoFar: Int? = null,
)

fun computeSportEstimate(activityType: String, intensity: String, weightKg: Double): Int {
    data class Cfg(val met: Double, val mins: Int)
    val cfg = when (activityType) {
        "climbing" -> when (intensity) {
            "light" -> Cfg(4.0, 75)
            "hard"  -> Cfg(6.5, 90)
            else    -> Cfg(5.0, 90)
        }
        "rowing" -> when (intensity) {
            "light" -> Cfg(7.0, 45)
            "hard"  -> Cfg(9.0, 60)
            else    -> Cfg(7.5, 60)
        }
        else -> when (intensity) {
            "light" -> Cfg(4.0, 60)
            "hard"  -> Cfg(7.0, 75)
            else    -> Cfg(5.5, 75)
        }
    }
    return (cfg.met * weightKg * cfg.mins / 60.0).toInt()
}

fun isValidWeightInput(input: String): Boolean {
    val normalized = input.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return false
    if (value < 20.0 || value > 300.0) return false
    val sepIndex = normalized.indexOf('.')
    if (sepIndex != -1 && normalized.length - sepIndex - 1 > 1) return false
    return true
}

fun computeCaloriesLeft(
    expectedToday: Int,
    targetDeficit: Int,
    actualBurnedToday: Int?,
    caloriesIn: Int,
): Int {
    val caloriesOut = if (actualBurnedToday != null && actualBurnedToday >= expectedToday * 0.9) {
        actualBurnedToday
    } else {
        expectedToday
    }
    return caloriesOut - targetDeficit - caloriesIn
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HealthApplication
    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client = HttpClient(Android) { install(ContentNegotiation) { json() } },
    )

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { observeLogEntries() }
        viewModelScope.launch { load() }
    }

    private fun refreshCaloriesLeft() {
        val state = _uiState.value
        val isSportTonight = state.sportTonight != null

        val expectedToday = if (isSportTonight) {
            state.expectedTodaySport ?: state.caloriesOut
        } else {
            state.expectedTodayNonSport ?: state.caloriesOut
        }

        val caloriesLeft = computeCaloriesLeft(
            expectedToday      = expectedToday,
            targetDeficit      = state.targetDeficit,
            actualBurnedToday  = state.actualBurnedSoFar,
            caloriesIn         = state.caloriesIn,
        )

        val usingFallback = (isSportTonight && state.expectedTodaySport == null) ||
                            (!isSportTonight && state.expectedTodayNonSport == null)
        val budgetLabel = when {
            caloriesLeft < 0         -> "kcal over"
            state.targetDeficit == 0 -> "left (balance)"
            usingFallback            -> "left (estimated)"
            else                     -> "left"
        }

        _uiState.update { it.copy(caloriesLeft = caloriesLeft, budgetLabel = budgetLabel) }
    }

    private suspend fun observeLogEntries() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val today = effectiveDate().toString()
        app.db.logEntryDao().observeTotalKcalForDate(stored.userId, "$today%").collect { kcal ->
            _uiState.update { it.copy(caloriesIn = kcal) }
            refreshCaloriesLeft()
        }
    }

    private suspend fun load() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val today = effectiveDate().toString()

        val localState = computeLocalState(stored.userId, today)
        _uiState.value = localState
        refreshCaloriesLeft()

        runCatching { apiClient.getTodaySummary(stored.token, today) }
            .onSuccess { dto ->
                app.db.dynamicBudgetParamsDao().upsert(
                    org.branneman.health.db.entities.DynamicBudgetParamsEntity(
                        date                  = today,
                        expectedTodaySport    = dto.expectedTodaySport,
                        expectedTodayNonSport = dto.expectedTodayNonSport,
                    )
                )
                _uiState.update { state ->
                    state.copy(
                        isLoading             = false,
                        caloriesOut           = dto.caloriesOut,
                        caloriesOutSource     = dto.caloriesOutSource,
                        targetDeficit         = dto.targetDeficit,
                        expectedTodaySport    = dto.expectedTodaySport,
                        expectedTodayNonSport = dto.expectedTodayNonSport,
                        actualBurnedSoFar     = dto.actualBurnedSoFar,
                    )
                }
                refreshCaloriesLeft()
            }
    }

    private suspend fun computeLocalState(userId: String, today: String): DashboardUiState {
        val profile = app.db.userProfileDao().get()
            ?: return DashboardUiState(isLoading = false)
        val latestWeight = app.db.bodyWeightDao().observeAll().first().firstOrNull()?.kg
        val weightToday  = app.db.bodyWeightDao().getForDate(userId, today)?.kg
        val energy       = app.db.dailyEnergyDao().getForDate(userId, today)
        val caloriesIn   = app.db.logEntryDao().sumQuickAddKcalForDate(userId, "$today%") +
                           app.db.logEntryDao().sumItemKcalForDate(userId, "$today%")
        val sport        = app.db.sportTonightDao().getForDate(today)?.takeIf { it.date == today }
        val params       = app.db.dynamicBudgetParamsDao().getForDate(today)

        val (caloriesOut, source) = if (energy != null) {
            energy.totalKcal to "polar_today"
        } else {
            val weightKg = latestWeight ?: profile.goalWeightKg
            val age = LocalDate.now().year - profile.birthYear
            val tdee = (computeBmr(profile.sex, weightKg, profile.heightCm, age) * activityMultiplier(profile.activityLevel)).toInt()
            tdee to "estimate"
        }

        return DashboardUiState(
            isLoading             = false,
            caloriesIn            = caloriesIn,
            caloriesOut           = caloriesOut,
            caloriesOutSource     = source,
            targetDeficit         = profile.targetDeficit,
            sportTonight          = sport,
            weightKgToday         = weightToday,
            expectedTodaySport    = params?.expectedTodaySport,
            expectedTodayNonSport = params?.expectedTodayNonSport,
            actualBurnedSoFar     = energy?.totalKcal,
        )
        // caloriesLeft and budgetLabel are set by refreshCaloriesLeft() called after this
    }

    fun setSportTonight(activityType: String, intensity: String) {
        viewModelScope.launch {
            val profile = app.db.userProfileDao().get() ?: return@launch
            val latestWeight = app.db.bodyWeightDao().observeAll().first().firstOrNull()?.kg
                ?: profile.goalWeightKg
            val today = effectiveDate().toString()
            val estimatedKcal = computeSportEstimate(activityType, intensity, latestWeight)
            val entity = SportTonightEntity(
                date = today, activityType = activityType,
                intensity = intensity, estimatedKcal = estimatedKcal,
            )
            app.db.sportTonightDao().upsert(entity)
            _uiState.update { it.copy(sportTonight = entity) }
            refreshCaloriesLeft()
        }
    }

    fun clearSportTonight() {
        viewModelScope.launch {
            app.db.sportTonightDao().deleteForDate(effectiveDate().toString())
            _uiState.update { it.copy(sportTonight = null) }
            refreshCaloriesLeft()
        }
    }

    fun logWeight(kg: Double) {
        viewModelScope.launch {
            val stored = tokenStore.tokenFlow.first() ?: return@launch
            val today = effectiveDate().toString()
            app.db.bodyWeightDao().upsert(
                BodyWeightEntity(
                    id         = today,
                    userId     = stored.userId,
                    date       = today,
                    kg         = kg,
                    syncStatus = SyncStatus.PENDING_CREATE,
                )
            )
            _uiState.update { it.copy(weightKgToday = kg) }
        }
    }
}
