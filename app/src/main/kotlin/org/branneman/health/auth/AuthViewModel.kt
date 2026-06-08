package org.branneman.health.auth

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
import kotlinx.coroutines.launch
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.network.AuthPlugin
import org.branneman.health.network.HealthApiClient
import org.branneman.health.sync.LoginSyncService

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = TokenStore(application.authDataStore)

    // lateinit resolves the circular dependency: the lambdas below capture authRepository
    // by reference. They are only called after init {} completes, by which point
    // authRepository is initialized.
    private lateinit var authRepository: AuthRepository

    private val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client = HttpClient(Android) {
            install(ContentNegotiation) { json() }
            install(AuthPlugin) {
                onRefreshNeeded = { authRepository.refresh() }
                onExpired = { authRepository.handleExpired() }
            }
        }
    )

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        val app = application as HealthApplication
        authRepository = AuthRepository(
            tokenStore = tokenStore,
            apiClient = apiClient,
            loginSyncService = LoginSyncService(api = apiClient, db = app.db),
            db = app.db,
        )

        viewModelScope.launch {
            authRepository.authState.collect { _authState.value = it }
        }

        viewModelScope.launch {
            authRepository.proactiveRefreshIfNeeded()
        }
    }

    fun login(username: String, password: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            authRepository.login(username, password).onFailure {
                onError(it.message ?: "Unknown error")
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
