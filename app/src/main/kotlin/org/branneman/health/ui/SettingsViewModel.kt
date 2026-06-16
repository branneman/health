package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.UserProfileDto
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.network.HealthApiClient

enum class PolarStatus { Loading, Connected, NotConnected, Unknown }

data class ScheduleState(
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
    val savedWakeTime: String = "07:00",
    val savedBedtime: String = "23:00",
    val isSaving: Boolean = false,
    val saveError: String? = null,
) {
    val changed: Boolean get() = wakeTime != savedWakeTime || bedtime != savedBedtime
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HealthApplication
    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient()

    private val _polarStatus = MutableStateFlow(PolarStatus.Loading)
    val polarStatus: StateFlow<PolarStatus> = _polarStatus

    private val _scheduleState = MutableStateFlow(ScheduleState())
    val scheduleState: StateFlow<ScheduleState> = _scheduleState

    init {
        recheckPolarStatus()
        loadSchedule()
    }

    private fun loadSchedule() {
        viewModelScope.launch {
            val profile = app.db.userProfileDao().get() ?: return@launch
            _scheduleState.value = ScheduleState(
                wakeTime      = profile.wakeTime,
                bedtime       = profile.bedtime,
                savedWakeTime = profile.wakeTime,
                savedBedtime  = profile.bedtime,
            )
        }
    }

    fun updateWakeTime(deltaMinutes: Int) {
        _scheduleState.update { s -> s.copy(wakeTime = adjustTime(s.wakeTime, deltaMinutes)) }
    }

    fun updateBedtime(deltaMinutes: Int) {
        _scheduleState.update { s -> s.copy(bedtime = adjustTime(s.bedtime, deltaMinutes)) }
    }

    fun saveSchedule() {
        viewModelScope.launch {
            _scheduleState.update { it.copy(isSaving = true, saveError = null) }
            val profile = app.db.userProfileDao().get() ?: run {
                _scheduleState.update { it.copy(isSaving = false, saveError = "Profile not found") }
                return@launch
            }
            val token = tokenStore.tokenFlow.first() ?: run {
                _scheduleState.update { it.copy(isSaving = false, saveError = "Not signed in") }
                return@launch
            }
            val s = _scheduleState.value
            runCatching {
                val updated = profile.copy(wakeTime = s.wakeTime, bedtime = s.bedtime)
                app.db.userProfileDao().upsert(updated)
                apiClient.putProfile(token.token, UserProfileDto(
                    heightCm      = updated.heightCm,
                    birthYear     = updated.birthYear,
                    sex           = updated.sex,
                    goalWeightKg  = updated.goalWeightKg,
                    activityLevel = updated.activityLevel,
                    targetDeficit = updated.targetDeficit,
                    phase         = updated.phase,
                    vacationMode  = updated.vacationMode,
                    wakeTime      = updated.wakeTime,
                    bedtime       = updated.bedtime,
                ))
            }.onSuccess {
                _scheduleState.update { it.copy(isSaving = false, savedWakeTime = s.wakeTime, savedBedtime = s.bedtime) }
            }.onFailure { e ->
                _scheduleState.update { it.copy(isSaving = false, saveError = e.message ?: "Save failed") }
            }
        }
    }

    fun recheckPolarStatus() {
        viewModelScope.launch {
            _polarStatus.value = PolarStatus.Loading
            runCatching {
                val token = tokenStore.tokenFlow.first() ?: run {
                    _polarStatus.value = PolarStatus.Unknown
                    return@launch
                }
                val status = apiClient.getPolarStatus(token.token)
                _polarStatus.value = if (status.connected) PolarStatus.Connected else PolarStatus.NotConnected
            }.onFailure {
                _polarStatus.value = PolarStatus.Unknown
            }
        }
    }

    fun connectPolar(onUrl: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val token = tokenStore.tokenFlow.first() ?: return@launch
                onUrl(apiClient.getPolarConnectUrl(token.token))
            }
        }
    }
}
