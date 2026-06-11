package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aQuickAddEntry
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogEntrySyncServiceTest {

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
    fun `PENDING_CREATE is posted and marked SYNCED on 201`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 500)
        db.logEntryDao().upsert(entry)

        val api = mockApiClient { _ ->
            respond(
                """{"id":"${entry.id}","loggedAt":"2026-06-11T12:00:00Z","mealType":"unknown","quickAddKcal":500,"quickAddLabel":null,"items":[]}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        LogEntrySyncService(api, db).sync("token")

        val synced = db.logEntryDao().getByStatus(SyncStatus.SYNCED)
        assertEquals(1, synced.size)
        assertEquals(entry.id, synced[0].id)
    }

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE on network error`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 500)
        db.logEntryDao().upsert(entry)

        val api = HealthApiClient("http://test", HttpClient(MockEngine { error("connection refused") }) {
            install(ContentNegotiation) { json() }
        })

        LogEntrySyncService(api, db).sync("token")

        assertEquals(1, db.logEntryDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }

    @Test
    fun `PENDING_DELETE is deleted from server and hard-deleted from Room on 204`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 300, syncStatus = SyncStatus.PENDING_DELETE)
        db.logEntryDao().upsert(entry)

        val api = mockApiClient { _ -> respond("", HttpStatusCode.NoContent) }

        LogEntrySyncService(api, db).sync("token")

        assertTrue(db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).isEmpty())
        assertTrue(db.logEntryDao().observeAll().first().isEmpty())
    }

    @Test
    fun `PENDING_DELETE is hard-deleted from Room even when server returns 404`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 300, syncStatus = SyncStatus.PENDING_DELETE)
        db.logEntryDao().upsert(entry)

        val api = mockApiClient { _ -> respond("", HttpStatusCode.NotFound) }

        LogEntrySyncService(api, db).sync("token")

        assertTrue(db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).isEmpty())
    }

    @Test
    fun `PENDING_DELETE stays on 500 server error`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 300, syncStatus = SyncStatus.PENDING_DELETE)
        db.logEntryDao().upsert(entry)

        val api = mockApiClient { _ -> respond("", HttpStatusCode.InternalServerError) }

        LogEntrySyncService(api, db).sync("token")

        assertEquals(1, db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).size)
    }
}
