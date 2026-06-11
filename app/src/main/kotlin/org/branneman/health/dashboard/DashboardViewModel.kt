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
import org.branneman.health.db.entities.SportTonightEntity
import org.branneman.health.network.HealthApiClient
import org.branneman.health.onboarding.activityMultiplier
import org.branneman.health.onboarding.computeBmr
import java.time.LocalDate

data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,
    val caloriesOutSource: String = "estimate",
    val targetDeficit: Int = 0,
    val budgetRemaining: Int = 0,
    val sportTonight: SportTonightEntity? = null,
    val adjustedBudgetRemaining: Int = 0,
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

    private suspend fun observeLogEntries() {
        val stored = tokenStore.tokenFlow.first() ?: return
        app.db.logEntryDao().observeAll().collect { entries ->
            val today = LocalDate.now().toString()
            val caloriesIn = entries
                .filter { it.userId == stored.userId && it.loggedAt.startsWith(today) }
                .sumOf { it.quickAddKcal ?: 0 }
            _uiState.update { state ->
                val budget = state.caloriesOut - state.targetDeficit - caloriesIn
                state.copy(
                    caloriesIn              = caloriesIn,
                    budgetRemaining         = budget,
                    adjustedBudgetRemaining = budget + (state.sportTonight?.estimatedKcal ?: 0),
                )
            }
        }
    }

    private suspend fun load() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val today = LocalDate.now().toString()

        _uiState.value = computeLocalState(stored.userId, today)

        runCatching { apiClient.getTodaySummary(stored.token, today) }
            .onSuccess { dto ->
                _uiState.update { state ->
                    val budget = dto.caloriesOut - dto.targetDeficit - state.caloriesIn
                    state.copy(
                        isLoading               = false,
                        caloriesOut             = dto.caloriesOut,
                        caloriesOutSource       = dto.caloriesOutSource,
                        targetDeficit           = dto.targetDeficit,
                        budgetRemaining         = budget,
                        adjustedBudgetRemaining = budget + (state.sportTonight?.estimatedKcal ?: 0),
                    )
                }
            }
    }

    private suspend fun computeLocalState(userId: String, today: String): DashboardUiState {
        val profile = app.db.userProfileDao().get()
            ?: return DashboardUiState(isLoading = false)
        val latestWeight = app.db.bodyWeightDao().observeAll().first().firstOrNull()?.kg
        val energy = app.db.dailyEnergyDao().getForDate(userId, today)
        val caloriesIn = app.db.logEntryDao().sumQuickAddKcalForDate(userId, "$today%")
        val sport = app.db.sportTonightDao().getForDate(today)?.takeIf { it.date == today }

        val (caloriesOut, source) = if (energy != null) {
            energy.totalKcal to "polar_today"
        } else {
            val weightKg = latestWeight ?: profile.goalWeightKg
            val age = LocalDate.now().year - profile.birthYear
            val tdee = (computeBmr(profile.sex, weightKg, profile.heightCm, age) * activityMultiplier(profile.activityLevel)).toInt()
            tdee to "estimate"
        }

        val budgetBase = caloriesOut - profile.targetDeficit - caloriesIn
        return DashboardUiState(
            isLoading               = false,
            caloriesIn              = caloriesIn,
            caloriesOut             = caloriesOut,
            caloriesOutSource       = source,
            targetDeficit           = profile.targetDeficit,
            budgetRemaining         = budgetBase,
            sportTonight            = sport,
            adjustedBudgetRemaining = budgetBase + (sport?.estimatedKcal ?: 0),
        )
    }

    fun setSportTonight(activityType: String, intensity: String) {
        viewModelScope.launch {
            val profile = app.db.userProfileDao().get() ?: return@launch
            val latestWeight = app.db.bodyWeightDao().observeAll().first().firstOrNull()?.kg
                ?: profile.goalWeightKg
            val today = LocalDate.now().toString()
            val estimatedKcal = computeSportEstimate(activityType, intensity, latestWeight)
            val entity = SportTonightEntity(
                date = today, activityType = activityType,
                intensity = intensity, estimatedKcal = estimatedKcal,
            )
            app.db.sportTonightDao().upsert(entity)
            _uiState.update { state ->
                state.copy(
                    sportTonight            = entity,
                    adjustedBudgetRemaining = state.budgetRemaining + estimatedKcal,
                )
            }
        }
    }

    fun clearSportTonight() {
        viewModelScope.launch {
            app.db.sportTonightDao().deleteForDate(LocalDate.now().toString())
            _uiState.update { state ->
                state.copy(sportTonight = null, adjustedBudgetRemaining = state.budgetRemaining)
            }
        }
    }
}
