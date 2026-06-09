package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.db.HealthDatabase
import org.branneman.health.network.HealthApiClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginSyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun apiClient(vararg responses: String): HealthApiClient {
        var call = 0
        val responseList = responses.toList()
        val engine = MockEngine {
            val body = responseList[call.coerceAtMost(responseList.size - 1)]
            call++
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return HealthApiClient("http://test", HttpClient(engine) {
            install(ContentNegotiation) { json() }
        })
    }

    @Test
    fun `sync with no profile returns false and stores shortcuts`() = runTest {
        val userId = "00000000-0000-0000-0000-000000000001"
        val api = apiClient(
            "null",
            """[{"id":"sc-1","emoji":"🍎","label":"Apple","kcal":52,"sortOrder":0}]""",
            "[]", "[]", "[]", "[]", "[]", "[]",
        )
        val service = LoginSyncService(api, db)
        val hasProfile = service.sync("token", userId)

        assertFalse(hasProfile)
        val shortcuts = db.shortcutDao().observeAll().first()
        assertEquals(1, shortcuts.size)
        assertEquals("Apple", shortcuts[0].label)
    }

    @Test
    fun `sync with profile returns true`() = runTest {
        val userId = "00000000-0000-0000-0000-000000000002"
        val profileJson = """{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}"""
        val api = apiClient(
            profileJson,
            "[]", "[]", "[]", "[]", "[]", "[]",
        )
        val service = LoginSyncService(api, db)
        assertTrue(service.sync("token", userId))
    }
}
