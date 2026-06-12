package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.branneman.health.aBodyWeightEntry
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BodyWeightSyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun mockApiClient(handler: MockRequestHandler): HealthApiClient {
        val engine = MockEngine(handler)
        return HealthApiClient("http://test", HttpClient(engine) {
            install(ContentNegotiation) { json() }
        })
    }

    @Test
    fun `PENDING_CREATE is posted and marked SYNCED on 200`() = runTest {
        val entry = aBodyWeightEntry(
            id = "2026-06-11", date = "2026-06-11", kg = 82.5,
            syncStatus = SyncStatus.PENDING_CREATE,
        )
        db.bodyWeightDao().upsert(entry)

        val api = mockApiClient { _ ->
            respond(
                """{"date":"2026-06-11","kg":82.5}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        BodyWeightSyncService(api, db).sync("token")

        assertEquals(0, db.bodyWeightDao().getByStatus(SyncStatus.PENDING_CREATE).size)
        assertEquals(1, db.bodyWeightDao().getByStatus(SyncStatus.SYNCED).size)
    }

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE on network error`() = runTest {
        val entry = aBodyWeightEntry(
            id = "2026-06-11", date = "2026-06-11", kg = 82.5,
            syncStatus = SyncStatus.PENDING_CREATE,
        )
        db.bodyWeightDao().upsert(entry)

        val api = HealthApiClient(
            "http://test",
            HttpClient(MockEngine { error("connection refused") }) {
                install(ContentNegotiation) { json() }
            },
        )

        BodyWeightSyncService(api, db).sync("token")

        assertEquals(1, db.bodyWeightDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }

    @Test
    fun `SYNCED entries are not re-uploaded`() = runTest {
        val entry = aBodyWeightEntry(
            id = "2026-06-11", date = "2026-06-11", kg = 82.5,
            syncStatus = SyncStatus.SYNCED,
        )
        db.bodyWeightDao().upsert(entry)

        var callCount = 0
        val api = mockApiClient { _ ->
            callCount++
            respond(
                """{"date":"2026-06-11","kg":82.5}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        BodyWeightSyncService(api, db).sync("token")

        assertEquals(0, callCount)
    }
}
