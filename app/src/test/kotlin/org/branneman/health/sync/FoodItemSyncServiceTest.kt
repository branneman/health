package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.branneman.health.aFoodItem
import org.branneman.health.uuid
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
class FoodItemSyncServiceTest {

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
    fun `PENDING_CREATE item is posted and marked SYNCED on 201`() = runTest {
        val item = aFoodItem(id = uuid(), syncStatus = SyncStatus.PENDING_CREATE)
        db.foodItemDao().upsertAll(listOf(item))

        val api = mockApiClient { _ ->
            respond(
                """{"id":"${item.id}","barcode":null,"name":"Test Food","kcalPer100g":200.0,"proteinPer100g":null,"carbsPer100g":null,"fatPer100g":null,"source":"manual"}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        FoodItemSyncService(api, db).pushPending("token")

        assertEquals(0, db.foodItemDao().getByStatus(SyncStatus.PENDING_CREATE).size)
        assertEquals(1, db.foodItemDao().getByStatus(SyncStatus.SYNCED).size)
    }

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE on network error`() = runTest {
        val item = aFoodItem(syncStatus = SyncStatus.PENDING_CREATE)
        db.foodItemDao().upsertAll(listOf(item))

        val api = HealthApiClient("http://test", HttpClient(MockEngine { error("network error") }) {
            install(ContentNegotiation) { json() }
        })

        FoodItemSyncService(api, db).pushPending("token")

        assertEquals(1, db.foodItemDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }

    @Test
    fun `409 conflict from server marks item SYNCED (idempotent retry)`() = runTest {
        val item = aFoodItem(syncStatus = SyncStatus.PENDING_CREATE)
        db.foodItemDao().upsertAll(listOf(item))

        val api = mockApiClient { _ -> respond("", HttpStatusCode.Conflict) }

        FoodItemSyncService(api, db).pushPending("token")

        // 409 means it already exists on the server — treat as success
        assertEquals(0, db.foodItemDao().getByStatus(SyncStatus.PENDING_CREATE).size)
        assertEquals(1, db.foodItemDao().getByStatus(SyncStatus.SYNCED).size)
    }

    @Test
    fun `no-op when nothing is PENDING_CREATE`() = runTest {
        db.foodItemDao().upsertAll(listOf(aFoodItem(syncStatus = SyncStatus.SYNCED)))

        var called = false
        val api = mockApiClient { _ -> called = true; respond("", HttpStatusCode.OK) }

        FoodItemSyncService(api, db).pushPending("token")

        assertEquals(false, called)
    }
}
