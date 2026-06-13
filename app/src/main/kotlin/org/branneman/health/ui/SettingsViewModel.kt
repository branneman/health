package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.network.HealthApiClient

enum class PolarStatus { Loading, Connected, NotConnected, Unknown }

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient()

    private val _polarStatus = MutableStateFlow(PolarStatus.Loading)
    val polarStatus: StateFlow<PolarStatus> = _polarStatus

    init {
        recheckPolarStatus()
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
