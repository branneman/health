package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.UserProfileDto
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.UserProfileEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

data class ProfileSettingsUiState(
    val isLoading: Boolean = true,
    val sex: String = "",
    val heightCm: String = "",
    val age: String = "",
    val goalWeightKg: String = "",
    val activityLevel: String = "lightly_active",
    val targetDeficit: Int = 300,
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
    val isSaving: Boolean = false,
    val saveError: String? = null,
    // Pass-through — preserved in PUT, not shown in UI
    val phase: String = "loss",
    val vacationMode: Boolean = false,
    val userId: String = "",
    // Snapshots for dirty detection; reset on load and successful save
    val savedSex: String = "",
    val savedHeightCm: String = "",
    val savedAge: String = "",
    val savedGoalWeightKg: String = "",
    val savedActivityLevel: String = "lightly_active",
    val savedTargetDeficit: Int = 300,
    val savedWakeTime: String = "07:00",
    val savedBedtime: String = "23:00",
) {
    val profileDirty: Boolean get() = !isLoading && (
        sex != savedSex || heightCm != savedHeightCm ||
        age != savedAge || goalWeightKg != savedGoalWeightKg
    )
    val goalDirty: Boolean get() = !isLoading && (
        activityLevel != savedActivityLevel || targetDeficit != savedTargetDeficit
    )
    val scheduleDirty: Boolean get() = !isLoading && (
        wakeTime != savedWakeTime || bedtime != savedBedtime
    )
}

class ProfileSettingsViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
    private val apiClient: HealthApiClient,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db          = (application as HealthApplication).db,
        tokenStore  = TokenStore(application.authDataStore),
        apiClient   = HealthApiClient(),
    )

    internal constructor(
        db: HealthDatabase,
        tokenStore: TokenStore,
        apiClient: HealthApiClient,
    ) : this(Application(), db, tokenStore, apiClient)

    private val _state = MutableStateFlow(ProfileSettingsUiState())
    val uiState: StateFlow<ProfileSettingsUiState> = _state.asStateFlow()

    val latestWeightKg: StateFlow<Double?> = db.bodyWeightDao().observeAll()
        .map { it.firstOrNull()?.kg }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, saveError = null) }
            val stored = tokenStore.tokenFlow.first() ?: return@launch
            runCatching {
                val dto = apiClient.getProfile(stored.token) ?: return@launch
                val age = (LocalDate.now().year - dto.birthYear).toString()
                _state.value = ProfileSettingsUiState(
                    isLoading          = false,
                    sex                = dto.sex,
                    heightCm           = dto.heightCm.toString(),
                    age                = age,
                    goalWeightKg       = dto.goalWeightKg.toString(),
                    activityLevel      = dto.activityLevel,
                    targetDeficit      = dto.targetDeficit,
                    wakeTime           = dto.wakeTime,
                    bedtime            = dto.bedtime,
                    phase              = dto.phase,
                    vacationMode       = dto.vacationMode,
                    userId             = stored.userId,
                    savedSex           = dto.sex,
                    savedHeightCm      = dto.heightCm.toString(),
                    savedAge           = age,
                    savedGoalWeightKg  = dto.goalWeightKg.toString(),
                    savedActivityLevel = dto.activityLevel,
                    savedTargetDeficit = dto.targetDeficit,
                    savedWakeTime      = dto.wakeTime,
                    savedBedtime       = dto.bedtime,
                )
            }.onFailure {
                _state.update { it.copy(isLoading = false, saveError = "Couldn't load profile.") }
            }
        }
    }

    fun update(block: ProfileSettingsUiState.() -> ProfileSettingsUiState) =
        _state.update(block)

    fun save(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            val heightCm     = s.heightCm.toIntOrNull()        ?: return@launch
            val goalWeightKg = s.goalWeightKg.toDoubleOrNull() ?: return@launch
            val age          = s.age.toIntOrNull()             ?: return@launch
            val birthYear    = LocalDate.now().year - age
            val stored       = tokenStore.tokenFlow.first()    ?: return@launch

            _state.update { it.copy(isSaving = true, saveError = null) }

            runCatching {
                val dto = UserProfileDto(
                    heightCm      = heightCm,
                    birthYear     = birthYear,
                    sex           = s.sex,
                    goalWeightKg  = goalWeightKg,
                    activityLevel = s.activityLevel,
                    targetDeficit = s.targetDeficit,
                    phase         = s.phase,
                    vacationMode  = s.vacationMode,
                    wakeTime      = s.wakeTime,
                    bedtime       = s.bedtime,
                )
                apiClient.putProfile(stored.token, dto)
                db.userProfileDao().upsert(
                    UserProfileEntity(
                        userId        = s.userId,
                        heightCm      = heightCm,
                        birthYear     = birthYear,
                        sex           = s.sex,
                        goalWeightKg  = goalWeightKg,
                        activityLevel = s.activityLevel,
                        targetDeficit = s.targetDeficit,
                        phase         = s.phase,
                        vacationMode  = s.vacationMode,
                        wakeTime      = s.wakeTime,
                        bedtime       = s.bedtime,
                        syncStatus    = SyncStatus.SYNCED,
                    )
                )
            }.onSuccess {
                _state.update { it.copy(
                    isSaving           = false,
                    savedSex           = it.sex,
                    savedHeightCm      = it.heightCm,
                    savedAge           = it.age,
                    savedGoalWeightKg  = it.goalWeightKg,
                    savedActivityLevel = it.activityLevel,
                    savedTargetDeficit = it.targetDeficit,
                    savedWakeTime      = it.wakeTime,
                    savedBedtime       = it.bedtime,
                )}
                onSuccess()
            }.onFailure {
                _state.update { it.copy(
                    isSaving  = false,
                    saveError = "Couldn't reach the server — check your connection and try again.",
                )}
            }
        }
    }
}
