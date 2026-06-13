package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutSyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun mockApiClient(json: String): HealthApiClient {
        val engine = MockEngine { _ ->
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
    }

    @Test
    fun `sync downloads workouts and upserts into Room`() = runTest {
        val api = mockApiClient("""[{"id":"00000000-0000-0000-0000-000000000001","date":"2026-06-12","type":"RUNNING","durationSecs":3600,"avgHr":145,"kcal":450}]""")
        WorkoutSyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        assertEquals(1, db.workoutDao().getAll("00000000-0000-0000-0000-000000000001").size)
    }

    @Test
    fun `sync twice with same id produces one workout row`() = runTest {
        val json = """[{"id":"00000000-0000-0000-0000-000000000001","date":"2026-06-12","type":"RUNNING","durationSecs":3600,"avgHr":145,"kcal":450}]"""
        val api = mockApiClient(json)
        WorkoutSyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        WorkoutSyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        assertEquals(1, db.workoutDao().getAll("00000000-0000-0000-0000-000000000001").size)
    }

    @Test
    fun `network error leaves Room unchanged`() = runTest {
        val api = HealthApiClient("http://test", HttpClient(MockEngine { error("no network") }) {
            install(ContentNegotiation) { json() }
        })
        WorkoutSyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        assertEquals(0, db.workoutDao().getAll("00000000-0000-0000-0000-000000000001").size)
    }
}
