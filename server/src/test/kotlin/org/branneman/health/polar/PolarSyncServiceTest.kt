package org.branneman.health.polar

import kotlinx.coroutines.runBlocking
import org.branneman.health.TestDatabase
import org.branneman.health.auth.Users
import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.PolarAuth
import org.branneman.health.data.PolarConnectState
import org.branneman.health.data.Workout
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*

class PolarSyncServiceTest {
    companion object {
        private val ds = TestDatabase.dataSource
        private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
        private val userId2 = UUID.fromString("00000000-0000-0000-0000-000000000098")
        private val testKey = ByteArray(32) { 0x42 }
        private val cipher = TokenCipher(testKey)

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { Users.id eq userId }
                Users.deleteWhere { Users.id eq userId2 }
                Users.insert { it[id] = userId; it[username] = "polar-sync-test@test.local"; it[passwordHash] = BCrypt.hashpw("x", BCrypt.gensalt(4)) }
                Users.insert { it[id] = userId2; it[username] = "polar-sync-test2@test.local"; it[passwordHash] = BCrypt.hashpw("x", BCrypt.gensalt(4)) }
            }
        }
    }

    private fun insertPolarAuth(hUserId: UUID, plainToken: String) {
        transaction {
            PolarAuth.upsert {
                it[PolarAuth.userId]       = "polar-${hUserId}"
                it[PolarAuth.accessToken]  = cipher.encrypt(plainToken)
                it[PolarAuth.healthUserId] = hUserId
                it[PolarAuth.createdAt]    = OffsetDateTime.now()
            }
        }
    }

    private fun cleanUp(hUserId: UUID) {
        transaction {
            DailyEnergy.deleteWhere { DailyEnergy.userId eq hUserId }
            Workout.deleteWhere { Workout.userId eq hUserId }
            PolarAuth.deleteWhere { PolarAuth.userId eq "polar-${hUserId}" }
        }
    }

    @BeforeTest fun setUp() { cleanUp(userId); cleanUp(userId2) }

    private fun service(activities: List<PolarActivity> = emptyList(), exercises: List<PolarExercise> = emptyList(), rateLimitToken: String? = null) =
        PolarSyncService(FakePolarApiClient(activities, exercises, rateLimitToken), ds, cipher)

    @Test
    fun `activities are upserted with correct field mapping`() = runBlocking {
        insertPolarAuth(userId, "tok-a")
        val activity = PolarActivity(LocalDate.of(2026, 6, 10), totalKcal = 2100, activeKcal = 400, steps = 8500)
        service(activities = listOf(activity)).syncAll()

        val row = transaction {
            DailyEnergy.selectAll().where { (DailyEnergy.userId eq userId) and (DailyEnergy.date eq LocalDate.of(2026, 6, 10)) }.single()
        }
        assertEquals(2100, row[DailyEnergy.totalKcal])
        assertEquals(400, row[DailyEnergy.activeKcal])
        assertEquals(1700, row[DailyEnergy.bmrKcal])
        assertEquals(8500, row[DailyEnergy.steps])
        assertEquals("polar", row[DailyEnergy.dataSource])
    }

    @Test
    fun `syncing same date twice produces one row`() = runBlocking {
        insertPolarAuth(userId, "tok-b")
        val activity = PolarActivity(LocalDate.of(2026, 6, 11), 2000, 350, null)
        service(listOf(activity)).syncAll()
        service(listOf(activity)).syncAll()
        val count = transaction { DailyEnergy.selectAll().where { DailyEnergy.userId eq userId }.count() }
        assertEquals(1L, count)
    }

    @Test
    fun `updated total for same date overwrites`() = runBlocking {
        insertPolarAuth(userId, "tok-c")
        service(listOf(PolarActivity(LocalDate.of(2026, 6, 12), 1800, 300, null))).syncAll()
        service(listOf(PolarActivity(LocalDate.of(2026, 6, 12), 2200, 500, null))).syncAll()
        val row = transaction {
            DailyEnergy.selectAll().where { (DailyEnergy.userId eq userId) and (DailyEnergy.date eq LocalDate.of(2026, 6, 12)) }.single()
        }
        assertEquals(2200, row[DailyEnergy.totalKcal])
    }

    @Test
    fun `same exercise fetched twice produces one workout row`() = runBlocking {
        insertPolarAuth(userId, "tok-d")
        val exercise = PolarExercise("EX001", LocalDate.of(2026, 6, 9), "RUNNING", 3600, 450, 145)
        service(exercises = listOf(exercise)).syncAll()
        service(exercises = listOf(exercise)).syncAll()
        val count = transaction { Workout.selectAll().where { Workout.userId eq userId }.count() }
        assertEquals(1L, count)
    }

    @Test
    fun `rate limit on one user skips that user but processes others`() = runBlocking {
        insertPolarAuth(userId, "rate-limited-token")
        insertPolarAuth(userId2, "normal-token")
        val activity = PolarActivity(LocalDate.of(2026, 6, 13), 2000, 400, null)
        service(activities = listOf(activity), rateLimitToken = "rate-limited-token").syncAll()

        val user1Count = transaction { DailyEnergy.selectAll().where { DailyEnergy.userId eq userId }.count() }
        val user2Count = transaction { DailyEnergy.selectAll().where { DailyEnergy.userId eq userId2 }.count() }
        assertEquals(0L, user1Count)
        assertEquals(1L, user2Count)
    }

    @Test
    fun `expired polar_connect_state rows are GC-ed`() = runBlocking {
        val ownerUserId = userId
        transaction {
            PolarConnectState.insert {
                it[state] = "expired-state-001"
                it[PolarConnectState.userId] = ownerUserId
                it[expiresAt] = OffsetDateTime.now().minusHours(1)
            }
        }
        service().syncAll()
        val count = transaction {
            PolarConnectState.selectAll().where { PolarConnectState.state eq "expired-state-001" }.count()
        }
        assertEquals(0L, count)
    }
}
