package org.branneman.health.sync

import android.content.Context
import androidx.work.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as HealthApplication
        val db = app.db
        val tokenStore = TokenStore(applicationContext.authDataStore)
        val stored = tokenStore.tokenFlow.first() ?: return Result.success()

        val apiClient = HealthApiClient(
            baseUrl = BuildConfig.SERVER_BASE_URL,
            client  = HttpClient(Android) { install(ContentNegotiation) { json() } },
        )

        db.bodyWeightDao().getByStatus(SyncStatus.PENDING_DELETE).forEach { entity ->
            db.bodyWeightDao().deleteById(entity.id)
        }

        BodyWeightSyncService(apiClient, db).sync(stored.token)
        LogEntrySyncService(apiClient, db).sync(stored.token)
        runCatching { apiClient.triggerPolarSync(stored.token) }
        DailyEnergySyncService(apiClient, db).sync(stored.token, stored.userId)
        WorkoutSyncService(apiClient, db).sync(stored.token, stored.userId)

        applicationContext.syncDataStore.saveLastSyncedAt(System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "SyncWorker"

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

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
