package org.branneman.health.onboarding

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
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
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnboardingRepositoryTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun apiClient(handler: MockRequestHandler): HealthApiClient =
        HealthApiClient(
            baseUrl = "http://test",
            client = HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json() }
            }
        )

    private fun profileJson() =
        """{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}"""

    private fun weightJson() = """{"date":"${LocalDate.now()}","kg":84.0}"""

    @Test
    fun `save writes profile and weight to Room on success`() = runTest {
        val client = apiClient { request ->
            when {
                request.url.encodedPath.endsWith("/profile") -> respond(
                    profileJson(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> respond(
                    weightJson(), HttpStatusCode.Created,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        }
        val repo = OnboardingRepository(client, db)
        val result = repo.save(
            token = "t", userId = "user-1",
            sex = "male", heightCm = 177, currentWeightKg = 84.0,
            goalWeightKg = 74.0, birthYear = 1986,
            activityLevel = "lightly_active", targetDeficit = 300,
        )
        assertTrue(result.isSuccess)
        assertNotNull(db.userProfileDao().get())
        assertEquals(SyncStatus.SYNCED, db.userProfileDao().get()!!.syncStatus)
        val weight = db.bodyWeightDao().observeAll().first().firstOrNull()
        assertNotNull(weight)
        assertEquals(84.0, weight.kg)
        assertEquals(SyncStatus.SYNCED, weight.syncStatus)
        // existsFlow must now emit true so auth state transitions
        assertTrue(db.userProfileDao().existsFlow().first())
    }

    @Test
    fun `save returns failure and does not write to Room when putProfile fails`() = runTest {
        val client = apiClient { respond("", HttpStatusCode.InternalServerError) }
        val repo = OnboardingRepository(client, db)
        val result = repo.save(
            token = "t", userId = "user-1",
            sex = "male", heightCm = 177, currentWeightKg = 84.0,
            goalWeightKg = 74.0, birthYear = 1986,
            activityLevel = "lightly_active", targetDeficit = 300,
        )
        assertTrue(result.isFailure)
        assertNull(db.userProfileDao().get())
        assertTrue(db.bodyWeightDao().observeAll().first().isEmpty())
    }

    @Test
    fun `save returns failure and does not write to Room when postBodyWeight fails`() = runTest {
        var callCount = 0
        val client = apiClient { request ->
            callCount++
            if (callCount == 1) {
                // putProfile succeeds
                respond(
                    profileJson(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                // postBodyWeight fails
                respond("", HttpStatusCode.InternalServerError)
            }
        }
        val repo = OnboardingRepository(client, db)
        val result = repo.save(
            token = "t", userId = "user-1",
            sex = "male", heightCm = 177, currentWeightKg = 84.0,
            goalWeightKg = 74.0, birthYear = 1986,
            activityLevel = "lightly_active", targetDeficit = 300,
        )
        assertTrue(result.isFailure)
        assertNull(db.userProfileDao().get())
    }

    @Test
    fun `save succeeds when postBodyWeight returns 409 (weight already exists)`() = runTest {
        val client = apiClient { request ->
            when {
                request.url.encodedPath.endsWith("/profile") -> respond(
                    profileJson(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> respond("", HttpStatusCode.Conflict)
            }
        }
        val repo = OnboardingRepository(client, db)
        val result = repo.save(
            token = "t", userId = "user-1",
            sex = "male", heightCm = 177, currentWeightKg = 84.0,
            goalWeightKg = 74.0, birthYear = 1986,
            activityLevel = "lightly_active", targetDeficit = 300,
        )
        assertTrue(result.isSuccess)
        assertNotNull(db.userProfileDao().get())
    }

    // BMR computation tests — pure functions, no infrastructure needed

    @Test
    fun `computeBmr male 84kg 177cm age 39`() {
        val bmr = computeBmr(sex = "male", weightKg = 84.0, heightCm = 177, age = 39)
        // 10×84 + 6.25×177 − 5×39 + 5 = 840 + 1106.25 − 195 + 5 = 1756.25
        assertEquals(1756.25, bmr, 0.01)
    }

    @Test
    fun `computeBmr female uses −161 constant`() {
        val bmr = computeBmr(sex = "female", weightKg = 70.0, heightCm = 165, age = 35)
        // 10×70 + 6.25×165 − 5×35 − 161 = 700 + 1031.25 − 175 − 161 = 1395.25
        assertEquals(1395.25, bmr, 0.01)
    }

    @Test
    fun `activityMultiplier returns correct values`() {
        assertEquals(1.20,   activityMultiplier("sedentary"),         0.001)
        assertEquals(1.375,  activityMultiplier("lightly_active"),    0.001)
        assertEquals(1.55,   activityMultiplier("moderately_active"), 0.001)
        assertEquals(1.375,  activityMultiplier("unknown"),           0.001) // default
    }
}
