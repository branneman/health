package org.branneman.health.auth

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import org.branneman.health.network.HealthApiClient
import java.time.OffsetDateTime

sealed class AuthState {
    data object Loading : AuthState()
    data object LoggedOut : AuthState()
    data object Expired : AuthState()
    data object LoggedIn : AuthState()
}

class AuthRepository(
    private val tokenStore: TokenStore,
    private val apiClient: HealthApiClient,
) {
    private val _expiredChannel = Channel<Unit>(Channel.CONFLATED)

    val authState: Flow<AuthState> = merge(
        tokenStore.tokenFlow.map { stored ->
            when {
                stored == null -> AuthState.LoggedOut
                OffsetDateTime.parse(stored.expiresAt) < OffsetDateTime.now() -> AuthState.Expired
                else -> AuthState.LoggedIn
            }
        },
        _expiredChannel.receiveAsFlow().map { AuthState.Expired }
    )

    // Called by AuthViewModel on startup to proactively refresh a near-expiry token.
    suspend fun proactiveRefreshIfNeeded() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val expiresAt = OffsetDateTime.parse(stored.expiresAt)
        val sevenDaysFromNow = OffsetDateTime.now().plusDays(7)
        if (expiresAt > OffsetDateTime.now() && expiresAt < sevenDaysFromNow) {
            refresh()
        }
    }

    // Called by AuthPlugin (via onRefreshNeeded lambda) on 401.
    suspend fun refresh(): String? {
        val stored = tokenStore.tokenFlow.first() ?: return null
        return runCatching {
            val response = apiClient.refresh(stored.token)
            tokenStore.save(response.token, response.expiresAt)
            response.token
        }.getOrElse {
            handleExpired()
            null
        }
    }

    // Called by AuthPlugin (via onExpired lambda) when refresh fails mid-session.
    fun handleExpired() {
        _expiredChannel.trySend(Unit)
    }

    suspend fun login(username: String, password: String): Result<Unit> = runCatching {
        val response = apiClient.login(username, password)
        tokenStore.save(response.token, response.expiresAt)
    }

    suspend fun logout() {
        val stored = tokenStore.tokenFlow.first()
        if (stored != null) runCatching { apiClient.logout(stored.token) }
        tokenStore.clear()
    }
}
