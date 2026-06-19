package org.branneman.health.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.LogEntryEntity
import java.time.OffsetDateTime

class QuickAddViewModel private constructor(
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

    private var _lastAdded: LogEntryEntity? = null

    fun log(kcalStr: String, label: String) {
        val kcal = kcalStr.trim().toIntOrNull()?.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = OffsetDateTime.now().toString(),
                mealType      = "unknown",
                quickAddKcal  = kcal,
                quickAddLabel = label.trim().ifEmpty { null },
            )
            db.logEntryDao().upsert(entity)
            _lastAdded = entity
        }
    }

    fun undoLog() {
        viewModelScope.launch {
            _lastAdded?.let { entity ->
                db.logEntryDao().deleteById(entity.id)
                _lastAdded = null
            }
        }
    }
}
