package org.branneman.health.onboarding

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

data class OnboardingUiState(
    val step: Int = 1,
    val sex: String = "",
    val heightCm: String = "",
    val currentWeightKg: String = "",
    val goalWeightKg: String = "",
    val age: String = "",
    val activityLevel: String = "lightly_active",
    val targetDeficit: Int = 300,
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
    val isSaving: Boolean = false,
    val saveError: String? = null,
) {
    val step1Valid: Boolean
        get() {
            val current = currentWeightKg.toDoubleOrNull() ?: return false
            val goal    = goalWeightKg.toDoubleOrNull()    ?: return false
            return sex.isNotEmpty()
                && heightCm.toIntOrNull() != null
                && age.toIntOrNull() != null
                && goal <= current
        }

    val estimatedTdeeKcal: Int?
        get() {
            val w = currentWeightKg.toDoubleOrNull() ?: return null
            val h = heightCm.toIntOrNull()           ?: return null
            val a = age.toIntOrNull()                ?: return null
            if (sex.isEmpty()) return null
            return (computeBmr(sex, w, h, a) * activityMultiplier(activityLevel)).toInt()
        }

    val kgPerWeek: Double?
        get() = if (targetDeficit == 0) null else targetDeficit / 7700.0

    val monthsToGoal: Double?
        get() {
            val current = currentWeightKg.toDoubleOrNull() ?: return null
            val goal    = goalWeightKg.toDoubleOrNull()    ?: return null
            if (targetDeficit == 0 || current <= goal) return null
            return (current - goal) * 7700.0 / targetDeficit / 30.0
        }
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HealthApplication
    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client = HttpClient(Android) { install(ContentNegotiation) { json() } }
    )
    private val repository = OnboardingRepository(apiClient, app.db)

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun update(block: OnboardingUiState.() -> OnboardingUiState) =
        _uiState.update(block)

    fun goBack() = _uiState.update { if (it.step > 1) it.copy(step = it.step - 1) else it }
    fun goNext() = _uiState.update { it.copy(step = it.step + 1) }

    fun save() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveError = null) }

            val stored = tokenStore.tokenFlow.first()
            if (stored == null) {
                _uiState.update { it.copy(isSaving = false, saveError = "Not signed in.") }
                return@launch
            }

            val state = _uiState.value
            val heightCm        = state.heightCm.toIntOrNull()           ?: return@launch
            val currentWeightKg = state.currentWeightKg.toDoubleOrNull() ?: return@launch
            val goalWeightKg    = state.goalWeightKg.toDoubleOrNull()    ?: return@launch
            val age             = state.age.toIntOrNull()                ?: return@launch
            val result = repository.save(
                token           = stored.token,
                userId          = stored.userId,
                sex             = state.sex,
                heightCm        = heightCm,
                currentWeightKg = currentWeightKg,
                goalWeightKg    = goalWeightKg,
                birthYear       = LocalDate.now().year - age,
                activityLevel   = state.activityLevel,
                targetDeficit   = state.targetDeficit,
                wakeTime        = state.wakeTime,
                bedtime         = state.bedtime,
            )

            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isSaving  = false,
                        saveError = "Couldn't reach the server — check your connection and try again.",
                    )
                }
                return@launch
            }
            // On success: Room existsFlow emits true → authState → LoggedIn → App.kt
            // renders MainNav automatically. No explicit navigation needed here.
            _uiState.update { it.copy(isSaving = false) }
        }
    }
}
