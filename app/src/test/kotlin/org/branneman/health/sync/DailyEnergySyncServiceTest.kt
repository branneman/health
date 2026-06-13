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
class DailyEnergySyncServiceTest {

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
    fun `sync downloads energy rows and upserts into Room`() = runTest {
        val api = mockApiClient("""[{"date":"2026-06-12","bmrKcal":1700,"activeKcal":400,"totalKcal":2100,"steps":9000,"source":"polar"}]""")
        DailyEnergySyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        val row = db.dailyEnergyDao().getForDate("00000000-0000-0000-0000-000000000001", "2026-06-12")
        assertEquals(2100, row?.totalKcal)
    }

    @Test
    fun `sync with same date twice produces one row with latest value`() = runTest {
        val api1 = mockApiClient("""[{"date":"2026-06-12","bmrKcal":1600,"activeKcal":300,"totalKcal":1900,"steps":null,"source":"polar"}]""")
        DailyEnergySyncService(api1, db).sync("token", "00000000-0000-0000-0000-000000000001")

        val api2 = mockApiClient("""[{"date":"2026-06-12","bmrKcal":1700,"activeKcal":400,"totalKcal":2100,"steps":8000,"source":"polar"}]""")
        DailyEnergySyncService(api2, db).sync("token", "00000000-0000-0000-0000-000000000001")

        val row = db.dailyEnergyDao().getForDate("00000000-0000-0000-0000-000000000001", "2026-06-12")
        assertEquals(2100, row?.totalKcal)
    }

    @Test
    fun `network error leaves Room unchanged`() = runTest {
        val api = HealthApiClient("http://test", HttpClient(MockEngine { error("no network") }) {
            install(ContentNegotiation) { json() }
        })
        DailyEnergySyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        val row = db.dailyEnergyDao().getForDate("00000000-0000-0000-0000-000000000001", "2026-06-12")
        assertEquals(null, row)
    }
}
