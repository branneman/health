package org.branneman.health.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.AiConfigStatusDto
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.network.HealthApiClient

class AiConfigViewModel private constructor(
    application: Application,
    private val repository: AiRepository,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository = NetworkAiRepository(
            client = HealthApiClient(),
            getToken = {
                TokenStore((application as HealthApplication).authDataStore)
                    .tokenFlow.first()?.token
            }
        )
    )

    val status: MutableStateFlow<AiConfigStatusDto?> = MutableStateFlow(null)
    val isSaving: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val saveError: MutableStateFlow<Boolean> = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            status.value = repository.getStatus()
        }
    }

    fun save(apiKey: String, expiresAt: String?) {
        viewModelScope.launch {
            isSaving.value = true
            saveError.value = false
            val result = repository.saveKey(apiKey, expiresAt)
            if (result != null) {
                status.value = result
            } else {
                saveError.value = true
            }
            isSaving.value = false
        }
    }

    fun remove() {
        viewModelScope.launch {
            repository.removeKey()
            status.value = AiConfigStatusDto(configured = false, expiresAt = null)
        }
    }

    internal companion object {
        fun forTest(
            application: Application,
            repository: AiRepository,
        ) = AiConfigViewModel(application, repository)
    }
}
