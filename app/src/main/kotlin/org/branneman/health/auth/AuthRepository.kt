package org.branneman.health.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import org.branneman.health.db.HealthDatabase
import org.branneman.health.network.HealthApiClient
import org.branneman.health.sync.LoginSyncService
import java.time.OffsetDateTime

sealed class AuthState {
    data object Loading         : AuthState()
    data object LoggedOut       : AuthState()
    data object Expired         : AuthState()
    data object NeedsOnboarding : AuthState()
    data object LoggedIn        : AuthState()
}

class AuthRepository(
    private val tokenStore: TokenStore,
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
    private val loginSyncService: LoginSyncService? = null,
) {
    private val _expiredChannel = Channel<Unit>(Channel.CONFLATED)

    @OptIn(ExperimentalCoroutinesApi::class)
    val authState: Flow<AuthState> = merge(
        tokenStore.tokenFlow.flatMapLatest { stored ->
            when {
                stored == null -> flowOf(AuthState.LoggedOut)
                OffsetDateTime.parse(stored.expiresAt) < OffsetDateTime.now() -> flowOf(AuthState.Expired)
                else -> db.userProfileDao()
                    .existsFlow()
                    .map { exists -> if (exists) AuthState.LoggedIn else AuthState.NeedsOnboarding }
            }
        },
        _expiredChannel.receiveAsFlow().map { AuthState.Expired }
    )

    suspend fun proactiveRefreshIfNeeded() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val expiresAt = OffsetDateTime.parse(stored.expiresAt)
        val sevenDaysFromNow = OffsetDateTime.now().plusDays(7)
        if (expiresAt > OffsetDateTime.now() && expiresAt < sevenDaysFromNow) {
            refresh()
        }
    }

    suspend fun refresh(): String? {
        val stored = tokenStore.tokenFlow.first() ?: return null
        return runCatching {
            val response = apiClient.refresh(stored.token)
            tokenStore.save(response.token, response.expiresAt, stored.userId)
            response.token
        }.getOrElse {
            handleExpired()
            null
        }
    }

    fun handleExpired() {
        _expiredChannel.trySend(Unit)
    }

    suspend fun login(username: String, password: String): Result<Boolean> = runCatching {
        val response = apiClient.login(username, password)
        tokenStore.save(response.token, response.expiresAt, response.userId)
        loginSyncService?.sync(response.token, response.userId) ?: false
    }

    suspend fun logout() {
        val stored = tokenStore.tokenFlow.first()
        if (stored != null) runCatching { apiClient.logout(stored.token) }
        stored?.userId?.let { userId ->
            db.bodyWeightDao().deleteAllForUser(userId)
            db.dailyEnergyDao().deleteAllForUser(userId)
            db.workoutDao().deleteAllForUser(userId)
            db.logEntryDao().deleteAllItemsForUser(userId)
            db.logEntryDao().deleteAllForUser(userId)
            db.mealTemplateDao().deleteAllItemsForUser(userId)
            db.mealTemplateDao().deleteAllForUser(userId)
            db.foodItemDao().deleteAllForUser(userId)
            db.shortcutDao().deleteAllForUser(userId)
            db.userProfileDao().deleteForUser(userId)
        }
        tokenStore.clear()
    }
}
