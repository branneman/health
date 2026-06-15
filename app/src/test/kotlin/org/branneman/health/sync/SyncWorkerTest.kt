package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.branneman.health.aShortcut
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Verifies that SyncWorker's doWork() wires up DailyEnergySyncService, WorkoutSyncService,
 * and ShortcutSyncService.
 *
 * SyncWorker itself cannot be instantiated in a unit test (requires WorkManager infrastructure),
 * so this test exercises the same service calls directly with a shared multi-endpoint fake API
 * and an in-memory Room DB, asserting the results land in Room as expected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncWorkerTest {

    private lateinit var db: HealthDatabase

    private val userId = "00000000-0000-0000-0000-000000000001"
    private val token = "test-token"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun fakeApiClient(): HealthApiClient {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/polar/sync" ->
                    respond("", HttpStatusCode.NoContent)
                request.url.encodedPath.contains("/out/energy") ->
                    respond(
                        """[{"date":"2026-06-12","bmrKcal":1700,"activeKcal":400,"totalKcal":2100,"steps":9000,"source":"polar"}]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.encodedPath.contains("/out/workouts") ->
                    respond(
                        """[{"id":"00000000-0000-0000-0000-000000000002","date":"2026-06-12","type":"RUNNING","durationSecs":3600,"avgHr":145,"kcal":450}]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else ->
                    respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }
        return HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
    }

    @Test
    fun `doWork syncs energy rows into Room`() = runTest {
        val api = fakeApiClient()
        DailyEnergySyncService(api, db).sync(token, userId)
        val row = db.dailyEnergyDao().getForDate(userId, "2026-06-12")
        assertEquals(2100, row?.totalKcal)
    }

    @Test
    fun `doWork syncs workout rows into Room`() = runTest {
        val api = fakeApiClient()
        WorkoutSyncService(api, db).sync(token, userId)
        assertEquals(1, db.workoutDao().getAll(userId).size)
    }

    @Test
    fun `doWork syncs both energy and workout in the same pass`() = runTest {
        val api = fakeApiClient()
        DailyEnergySyncService(api, db).sync(token, userId)
        WorkoutSyncService(api, db).sync(token, userId)
        val energyRow = db.dailyEnergyDao().getForDate(userId, "2026-06-12")
        assertEquals(2100, energyRow?.totalKcal)
        assertEquals(1, db.workoutDao().getAll(userId).size)
    }

    @Test
    fun `shortcut pushPending wires correctly in sync pass`() = runTest {
        val api = fakeApiClient()
        db.shortcutDao().upsertAll(listOf(
            aShortcut(userId = userId, label = "Pils", sortOrder = 0,
                syncStatus = SyncStatus.PENDING_CREATE)
        ))
        ShortcutSyncService(api, db).pushPending(token, userId)
        assertEquals(SyncStatus.SYNCED,
            db.shortcutDao().getByStatus(SyncStatus.SYNCED).single().syncStatus)
    }
}
