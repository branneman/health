package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.ShortcutEntity

class DrinkButtonsViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db = (application as HealthApplication).db,
        tokenStore = TokenStore(application.authDataStore),
    )

    internal constructor(db: HealthDatabase, tokenStore: TokenStore) : this(
        application = Application(),
        db = db,
        tokenStore = tokenStore,
    )

    private var userId: String? = null

    val draft: StateFlow<List<ShortcutEntity>?> = flow<List<ShortcutEntity>?> {
        emit(null)
        userId = tokenStore.tokenFlow.first()?.userId
        emitAll(db.shortcutDao().observeAll())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(rows: List<ShortcutEntity>) {
        val uid = userId ?: return
        save(rows, uid)
    }

    internal fun save(rows: List<ShortcutEntity>, userId: String) {
        viewModelScope.launch {
            db.shortcutDao().deleteAllForUser(userId)
            db.shortcutDao().upsertAll(rows.mapIndexed { i, entity ->
                entity.copy(userId = userId, sortOrder = i, syncStatus = SyncStatus.PENDING_CREATE)
            })
        }
    }
}
