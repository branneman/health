package org.branneman.health.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.network.AuthPlugin
import org.branneman.health.network.HealthApiClient
import org.branneman.health.sync.LoginSyncService
import org.branneman.health.sync.SyncWorker

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = TokenStore(application.authDataStore)

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

    private val authRepository: AuthRepository by lazy {
        val app = application as HealthApplication
        AuthRepository(
            tokenStore = tokenStore,
            apiClient = apiClient,
            db = app.db,
            loginSyncService = LoginSyncService(api = apiClient, db = app.db),
        )
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.authState.collect { _authState.value = it }
        }

        viewModelScope.launch {
            authRepository.proactiveRefreshIfNeeded()
        }
    }

    fun login(username: String, password: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            authRepository.login(username, password)
                .onSuccess { SyncWorker.enqueue(getApplication()) }
                .onFailure { onError(it.message ?: "Unknown error") }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            WorkManager.getInstance(getApplication()).cancelUniqueWork(SyncWorker.WORK_NAME)
        }
    }
}
