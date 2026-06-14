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
import org.branneman.health.aMealTemplate
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
class MealTemplateSyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun mockApi(handler: MockRequestHandler) = HealthApiClient(
        "http://test",
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } }
    )

    @Test
    fun `pushPending sends PENDING_CREATE template and marks it SYNCED`() = runTest {
        val userId = "u1"
        val t = aMealTemplate(userId = userId, name = "Usual breakfast",
            sortOrder = 0, quickAddKcal = 450, syncStatus = SyncStatus.PENDING_CREATE)
        db.mealTemplateDao().upsert(t)

        var body = ""
        val api = mockApi { req ->
            body = req.body.toByteArray().toString(Charsets.UTF_8)
            respond(
                """[{"id":"${t.id}","name":"Usual breakfast","sortOrder":0,"quickAddKcal":450,"items":[]}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        MealTemplateSyncService(api, db).pushPending("token", userId)

        assertEquals(1, db.mealTemplateDao().getByStatus(SyncStatus.SYNCED).size)
        assertTrue(body.contains("Usual breakfast"))
    }

    @Test
    fun `pushPending does nothing when no pending templates`() = runTest {
        val userId = "u1"
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, sortOrder = 0, quickAddKcal = 300,
            syncStatus = SyncStatus.SYNCED))

        var called = false
        val api = mockApi { _ ->
            called = true
            respond("[]", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        MealTemplateSyncService(api, db).pushPending("token", userId)

        assertTrue(!called)
    }

    @Test
    fun `pull upserts server templates into Room as SYNCED`() = runTest {
        val userId = "u1"
        val api = mockApi { _ ->
            respond(
                """[{"id":"abc","name":"Usual lunch","sortOrder":0,"quickAddKcal":600,"items":[]}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        MealTemplateSyncService(api, db).pull("token", userId)

        val pinned = db.mealTemplateDao().observePinned().first()
        assertEquals(1, pinned.size)
        assertEquals("Usual lunch", pinned[0].name)
        assertEquals(600, pinned[0].quickAddKcal)
        assertEquals(SyncStatus.SYNCED, pinned[0].syncStatus)
    }

    @Test
    fun `pushPending stays PENDING_CREATE on network error`() = runTest {
        val userId = "u1"
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, sortOrder = 0, quickAddKcal = 300,
            syncStatus = SyncStatus.PENDING_CREATE))

        val api = HealthApiClient("http://test",
            HttpClient(MockEngine { error("network error") }) { install(ContentNegotiation) { json() } })

        runCatching { MealTemplateSyncService(api, db).pushPending("token", userId) }

        assertEquals(1, db.mealTemplateDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }
}
