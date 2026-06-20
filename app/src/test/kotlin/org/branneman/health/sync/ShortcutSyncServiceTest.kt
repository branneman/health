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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShortcutSyncServiceTest {

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
    fun `pushPending sends PENDING_CREATE shortcuts and marks them SYNCED`() = runTest {
        val userId = "u1"
        val s = aShortcut(userId = userId, emoji = "🍺", label = "Pils", kcal = 150,
            sortOrder = 0, syncStatus = SyncStatus.PENDING_CREATE)
        db.shortcutDao().upsertAll(listOf(s))

        var body = ""
        val api = mockApi { req ->
            body = req.body.toByteArray().toString(Charsets.UTF_8)
            respond("", HttpStatusCode.OK, headersOf())
        }

        ShortcutSyncService(api, db).pushPending("token")

        assertEquals(1, db.shortcutDao().getByStatus(SyncStatus.SYNCED).size)
        assertTrue(body.contains("Pils"))
    }

    @Test
    fun `pushPending does nothing when no pending shortcuts`() = runTest {
        val userId = "u1"
        db.shortcutDao().upsertAll(listOf(
            aShortcut(userId = userId, syncStatus = SyncStatus.SYNCED)
        ))

        var called = false
        val api = mockApi { _ -> called = true; respond("", HttpStatusCode.OK, headersOf()) }

        ShortcutSyncService(api, db).pushPending("token")

        assertTrue(!called)
    }

    @Test
    fun `pull replaces Room shortcuts with server response marked SYNCED`() = runTest {
        val userId = "u1"
        db.shortcutDao().upsertAll(listOf(aShortcut(userId = userId, label = "Old")))

        val api = mockApi { _ ->
            respond(
                """[{"id":"sc-new","emoji":"🍷","label":"Wine","kcal":120,"sortOrder":0}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        ShortcutSyncService(api, db).pull("token", userId)

        val all = db.shortcutDao().observeAll().first()
        assertEquals(1, all.size)
        assertEquals("Wine", all[0].label)
        assertEquals(SyncStatus.SYNCED, all[0].syncStatus)
    }

    @Test
    fun `pushPending leaves status PENDING_CREATE on network error`() = runTest {
        val userId = "u1"
        db.shortcutDao().upsertAll(listOf(
            aShortcut(userId = userId, syncStatus = SyncStatus.PENDING_CREATE)
        ))

        val api = HealthApiClient("http://test",
            HttpClient(MockEngine { error("network error") }) { install(ContentNegotiation) { json() } })

        runCatching { ShortcutSyncService(api, db).pushPending("token") }

        assertEquals(1, db.shortcutDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }
}
