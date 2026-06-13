package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.network.HealthApiClient

class ConnectPolarViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient()

    fun getConnectUrl(onUrl: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val token = tokenStore.tokenFlow.first()?.token ?: return@launch
                val url = apiClient.getPolarConnectUrl(token)
                onUrl(url)
            }
        }
    }
}
