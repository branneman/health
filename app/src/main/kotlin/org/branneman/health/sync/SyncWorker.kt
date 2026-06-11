package org.branneman.health.sync

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.first
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as HealthApplication
        val db = app.db
        val tokenStore = TokenStore(applicationContext.authDataStore)
        val stored = tokenStore.tokenFlow.first() ?: return Result.success()

        // Process PENDING_DELETE: delete locally only (upload endpoint added in later story)
        db.bodyWeightDao().getByStatus(SyncStatus.PENDING_DELETE).forEach { entity ->
            db.bodyWeightDao().deleteById(entity.id)
        }

        // PENDING_CREATE rows stay pending until upload endpoints exist (later story)

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "SyncWorker"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
