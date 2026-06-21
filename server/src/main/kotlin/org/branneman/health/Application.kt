package org.branneman.health

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import org.branneman.health.QuickAddRequestDto
import org.branneman.health.DailyEnergyDto
import org.branneman.health.FoodItemDto
import org.branneman.health.LogEntryDto
import org.branneman.health.LogEntryItemDto
import org.branneman.health.MealTemplateDto
import org.branneman.health.MealTemplateItemDto
import org.branneman.health.WorkoutDto
import org.branneman.health.auth.AuthService
import org.branneman.health.auth.DbLoginAttemptsStore
import org.branneman.health.auth.LoginResult
import org.branneman.health.auth.RateLimiter
import org.branneman.health.budget.BudgetComputer
import org.branneman.health.budget.EnergyRow
import org.branneman.health.budget.HistoricalDay
import org.branneman.health.budget.UserProfileInput
import org.branneman.health.data.BodyWeight
import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.FoodItem
import org.branneman.health.data.LogEntry
import org.branneman.health.data.LogEntryItem
import org.branneman.health.data.MealTemplate
import org.branneman.health.data.MealTemplateItem
import org.branneman.health.data.Shortcut
import org.branneman.health.data.UserProfile
import org.branneman.health.data.Workout
import org.branneman.health.polar.HttpPolarApiClient
import org.branneman.health.polar.PolarApiClient
import org.branneman.health.polar.PolarSyncService
import org.branneman.health.polar.TokenCipher
import org.branneman.health.e2e.clearRateLimitsRoute
import org.branneman.health.e2e.e2eSeedRoute
import org.branneman.health.food.OfdImportService
import org.branneman.health.food.ofdAdminRoute
import org.branneman.health.polar.polarRoutes
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.util.UUID

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val dbUrl = System.getenv("DATABASE_URL") ?: error("DATABASE_URL not set")
    val dbUser = System.getenv("POSTGRES_USER") ?: error("POSTGRES_USER not set")
    val dbPassword = System.getenv("POSTGRES_PASSWORD") ?: error("POSTGRES_PASSWORD not set")

    Flyway.configure()
        .dataSource(dbUrl, dbUser, dbPassword)
        .load()
        .migrate()

    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 5
    })

    val clientId     = System.getenv("POLAR_CLIENT_ID")            ?: ""
    val clientSecret = System.getenv("POLAR_CLIENT_SECRET")        ?: ""
    val redirectUri  = System.getenv("POLAR_REDIRECT_URI")         ?: ""
    val encKeyBase64 = System.getenv("POLAR_TOKEN_ENCRYPTION_KEY")

    val polarApiClient: PolarApiClient? = if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
        HttpPolarApiClient(
            httpClient   = buildPolarHttpClient(),
            clientId     = clientId,
            clientSecret = clientSecret,
            redirectUri  = redirectUri,
        )
    } else null

    val polarCipher: TokenCipher? = encKeyBase64?.let { TokenCipher.fromBase64(it) }

    val ofdAdminSecret = System.getenv("OFD_ADMIN_SECRET")

    module(dataSource, polarApiClient, polarCipher, ofdAdminSecret)
}

fun Application.module(
    dataSource: javax.sql.DataSource,
    polarApiClient: PolarApiClient? = null,
    polarCipher: TokenCipher? = null,
    ofdAdminSecret: String? = null,
) {
    Database.connect(dataSource)

    val ofdHttpClient = buildOfdHttpClient()
    val ofdImportService = OfdImportService(dataSource, ofdHttpClient)

    val authService = AuthService()
    val ipRateLimiter = RateLimiter(store = DbLoginAttemptsStore())
    val usernameRateLimiter = RateLimiter(store = DbLoginAttemptsStore())

    install(XForwardedHeaders)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception on ${call.request.httpMethod.value} ${call.request.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }
    install(ContentNegotiation) { json() }

    intercept(ApplicationCallPipeline.Plugins) {
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > 65_536L) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            finish()
        }
    }

    install(Authentication) {
        bearer("api") {
            authenticate { credential ->
                authService.lookupToken(credential.token)
                    ?.let { UserIdPrincipal(it.toString()) }
            }
        }
    }

    val polarSyncService: PolarSyncService? =
        if (polarApiClient != null && polarCipher != null) PolarSyncService(polarApiClient, dataSource, polarCipher)
        else null

    routing {
        get("/") {
            call.respondText("OK")
        }

        get("/server-health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }

        get("/version") {
            val sha = System.getenv("GIT_SHA") ?: "unknown"
            call.respondText("""{"sha":"$sha"}""", ContentType.Application.Json)
        }

        route("/auth") {
            post("/token") {
                val start = System.currentTimeMillis()
                suspend fun applyFloor() {
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed < 500L) delay(500L - elapsed)
                }

                val ip = call.request.origin.remoteHost

                ipRateLimiter.isLocked(ip)?.let { retryAfter ->
                    applyFloor()
                    call.response.headers.append("Retry-After", retryAfter.toString())
                    call.respond(HttpStatusCode.TooManyRequests)
                    return@post
                }

                val body = call.receive<TokenRequest>()

                usernameRateLimiter.isLocked(body.username)?.let { retryAfter ->
                    applyFloor()
                    call.response.headers.append("Retry-After", retryAfter.toString())
                    call.respond(HttpStatusCode.TooManyRequests)
                    return@post
                }

                when (val result = authService.login(body.username, body.password)) {
                    is LoginResult.Failure -> {
                        ipRateLimiter.recordFailure(ip)
                        usernameRateLimiter.recordFailure(body.username)
                        applyFloor()
                        call.respond(HttpStatusCode.Unauthorized)
                    }
                    is LoginResult.Success -> {
                        ipRateLimiter.reset(ip)
                        usernameRateLimiter.reset(body.username)
                        applyFloor()
                        call.respond(TokenResponse(result.token, result.expiresAt.toString(), result.userId.toString()))
                    }
                }
            }

            authenticate("api") {
                post("/refresh") {
                    val token = call.request.headers[HttpHeaders.Authorization]!!
                        .removePrefix("Bearer ")
                    when (val result = authService.refresh(token)) {
                        is LoginResult.Failure -> call.respond(HttpStatusCode.Unauthorized)
                        is LoginResult.Success -> call.respond(
                            TokenResponse(result.token, result.expiresAt.toString(), result.userId.toString())
                        )
                    }
                }

                post("/logout") {
                    val token = call.request.headers[HttpHeaders.Authorization]!!
                        .removePrefix("Bearer ")
                    authService.logout(token)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        authenticate("api") {
            route("/body") {
                get("/weight") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val entries = transaction {
                        BodyWeight.selectAll()
                            .where { BodyWeight.userId eq userId }
                            .orderBy(BodyWeight.date, SortOrder.DESC)
                            .map { WeightEntryDto(it[BodyWeight.date].toString(), it[BodyWeight.kg].toDouble()) }
                    }
                    call.respond(entries)
                }

                post("/weight") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val dto = call.receive<WeightEntryDto>()
                    val date = java.time.LocalDate.parse(dto.date)

                    transaction {
                        val existing = BodyWeight.selectAll()
                            .where { (BodyWeight.userId eq userId) and (BodyWeight.date eq date) }
                            .singleOrNull()
                        if (existing != null) {
                            BodyWeight.update({ (BodyWeight.userId eq userId) and (BodyWeight.date eq date) }) {
                                it[BodyWeight.kg] = dto.kg.toBigDecimal()
                            }
                        } else {
                            BodyWeight.insert {
                                it[BodyWeight.id]        = UUID.randomUUID()
                                it[BodyWeight.userId]    = userId
                                it[BodyWeight.date]      = date
                                it[BodyWeight.kg]        = dto.kg.toBigDecimal()
                                it[BodyWeight.createdAt] = OffsetDateTime.now()
                            }
                        }
                    }
                    call.respond(HttpStatusCode.OK, dto)
                }
            }

            get("/profile") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val profile = transaction {
                    UserProfile.selectAll()
                        .where { UserProfile.userId eq userId }
                        .singleOrNull()
                        ?.let {
                            UserProfileDto(
                                heightCm      = it[UserProfile.heightCm],
                                birthYear     = it[UserProfile.birthYear],
                                sex           = it[UserProfile.sex],
                                goalWeightKg  = it[UserProfile.goalWeightKg].toDouble(),
                                activityLevel = it[UserProfile.activityLevel],
                                targetDeficit = it[UserProfile.targetDeficit],
                                phase         = it[UserProfile.phase],
                                vacationMode  = it[UserProfile.vacationMode],
                                wakeTime      = it[UserProfile.wakeTime].format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                                bedtime       = it[UserProfile.bedtime].format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                            )
                        }
                }
                if (profile == null) call.respond(HttpStatusCode.NotFound)
                else call.respond(profile)
            }

            put("/profile") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val dto = call.receive<UserProfileDto>()
                transaction {
                    UserProfile.upsert {
                        it[UserProfile.userId]        = userId
                        it[UserProfile.heightCm]      = dto.heightCm
                        it[UserProfile.birthYear]     = dto.birthYear
                        it[UserProfile.sex]           = dto.sex
                        it[UserProfile.goalWeightKg]  = dto.goalWeightKg.toBigDecimal()
                        it[UserProfile.activityLevel] = dto.activityLevel
                        it[UserProfile.targetDeficit] = dto.targetDeficit
                        it[UserProfile.phase]         = dto.phase
                        it[UserProfile.vacationMode]  = dto.vacationMode
                        it[UserProfile.wakeTime]      = java.time.LocalTime.parse(dto.wakeTime)
                        it[UserProfile.bedtime]       = java.time.LocalTime.parse(dto.bedtime)
                        it[UserProfile.updatedAt]     = OffsetDateTime.now()
                    }
                }
                call.respond(HttpStatusCode.OK, dto)
            }

            get("/shortcuts") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val shortcuts = transaction {
                    Shortcut.selectAll()
                        .where { Shortcut.userId eq userId }
                        .orderBy(Shortcut.sortOrder, SortOrder.ASC)
                        .map {
                            ShortcutDto(
                                id        = it[Shortcut.id].toString(),
                                emoji     = it[Shortcut.emoji],
                                label     = it[Shortcut.label],
                                kcal      = it[Shortcut.kcal],
                                sortOrder = it[Shortcut.sortOrder],
                            )
                        }
                }
                call.respond(shortcuts)
            }

            put("/shortcuts") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val incoming = call.receive<List<ShortcutDto>>()
                val saved = transaction {
                    Shortcut.deleteWhere { Op.build { Shortcut.userId eq userId } }
                    incoming.map { dto ->
                        val newId = UUID.randomUUID()
                        Shortcut.insert {
                            it[Shortcut.id]        = newId
                            it[Shortcut.userId]    = userId
                            it[Shortcut.emoji]     = dto.emoji
                            it[Shortcut.label]     = dto.label
                            it[Shortcut.kcal]      = dto.kcal
                            it[Shortcut.sortOrder] = dto.sortOrder
                            it[Shortcut.updatedAt] = OffsetDateTime.now()
                        }
                        dto.copy(id = newId.toString())
                    }
                }
                call.respond(HttpStatusCode.OK, saved)
            }

            get("/out/energy") {
                val userId   = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val fromStr  = call.request.queryParameters["from"]
                val fromDate = fromStr?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                val rows = transaction {
                    DailyEnergy.selectAll()
                        .where {
                            if (fromDate != null) {
                                (DailyEnergy.userId eq userId) and (DailyEnergy.date greaterEq fromDate)
                            } else {
                                DailyEnergy.userId eq userId
                            }
                        }
                        .orderBy(DailyEnergy.date, SortOrder.DESC)
                        .map {
                            DailyEnergyDto(
                                date       = it[DailyEnergy.date].toString(),
                                bmrKcal    = it[DailyEnergy.bmrKcal],
                                activeKcal = it[DailyEnergy.activeKcal],
                                totalKcal  = it[DailyEnergy.totalKcal],
                                steps      = it[DailyEnergy.steps],
                                source     = it[DailyEnergy.dataSource],
                            )
                        }
                }
                call.respond(rows)
            }

            get("/out/workouts") {
                val userId   = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val fromStr  = call.request.queryParameters["from"]
                val fromDate = fromStr?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                val rows = transaction {
                    Workout.selectAll()
                        .where {
                            if (fromDate != null) {
                                (Workout.userId eq userId) and (Workout.date greaterEq fromDate)
                            } else {
                                Workout.userId eq userId
                            }
                        }
                        .orderBy(Workout.date, SortOrder.DESC)
                        .map {
                            WorkoutDto(
                                id           = it[Workout.id].toString(),
                                date         = it[Workout.date].toString(),
                                type         = it[Workout.type],
                                durationSecs = it[Workout.durationSecs],
                                avgHr        = it[Workout.avgHr],
                                kcal         = it[Workout.kcal],
                            )
                        }
                }
                call.respond(rows)
            }

            get("/in/food-items") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val rows = transaction {
                    FoodItem.selectAll()
                        .where { FoodItem.userId eq userId }
                        .map {
                            FoodItemDto(
                                id             = it[FoodItem.id].toString(),
                                barcode        = it[FoodItem.barcode],
                                name           = it[FoodItem.name],
                                kcalPer100g    = it[FoodItem.kcalPer100g].toDouble(),
                                proteinPer100g = it[FoodItem.proteinPer100g]?.toDouble(),
                                carbsPer100g   = it[FoodItem.carbsPer100g]?.toDouble(),
                                fatPer100g     = it[FoodItem.fatPer100g]?.toDouble(),
                                source         = it[FoodItem.dataSource],
                            )
                        }
                }
                call.respond(rows)
            }

            get("/in/templates") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val templates = transaction {
                    val templateRows = MealTemplate.selectAll()
                        .where { MealTemplate.userId eq userId }
                        .toList()
                    templateRows.map { tRow ->
                        val items = MealTemplateItem.selectAll()
                            .where { MealTemplateItem.templateId eq tRow[MealTemplate.id] }
                            .map { iRow ->
                                MealTemplateItemDto(
                                    foodItemId = iRow[MealTemplateItem.foodItemId].toString(),
                                    grams      = iRow[MealTemplateItem.grams].toDouble(),
                                )
                            }
                        MealTemplateDto(
                            id           = tRow[MealTemplate.id].toString(),
                            name         = tRow[MealTemplate.name],
                            sortOrder    = tRow[MealTemplate.sortOrder],
                            quickAddKcal = tRow[MealTemplate.quickAddKcal],
                            items        = items,
                        )
                    }
                }
                call.respond(templates)
            }

            put("/in/templates") {
                val userId   = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val incoming = call.receive<List<MealTemplateDto>>()
                val saved = transaction {
                    val existingIds = MealTemplate.select(MealTemplate.id)
                        .where { MealTemplate.userId eq userId }
                        .map { it[MealTemplate.id] }
                    if (existingIds.isNotEmpty()) {
                        MealTemplateItem.deleteWhere {
                            Op.build { templateId inList existingIds }
                        }
                    }
                    MealTemplate.deleteWhere { Op.build { MealTemplate.userId eq userId } }
                    incoming.map { dto ->
                        val newId = UUID.randomUUID()
                        MealTemplate.insert {
                            it[MealTemplate.id]           = newId
                            it[MealTemplate.userId]       = userId
                            it[MealTemplate.name]         = dto.name
                            it[MealTemplate.quickAddKcal] = dto.quickAddKcal
                            it[MealTemplate.sortOrder]    = dto.sortOrder
                            it[MealTemplate.createdAt]    = OffsetDateTime.now()
                            it[MealTemplate.updatedAt]    = OffsetDateTime.now()
                        }
                        dto.copy(id = newId.toString())
                    }
                }
                call.respond(HttpStatusCode.OK, saved)
            }

            get("/in/log") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val entries = transaction {
                    val entryRows = LogEntry.selectAll()
                        .where { LogEntry.userId eq userId }
                        .orderBy(LogEntry.loggedAt, SortOrder.DESC)
                        .toList()
                    entryRows.map { eRow ->
                        val items = LogEntryItem.selectAll()
                            .where { LogEntryItem.logEntryId eq eRow[LogEntry.id] }
                            .map { iRow ->
                                LogEntryItemDto(
                                    foodItemId     = iRow[LogEntryItem.foodItemId].toString(),
                                    grams          = iRow[LogEntryItem.grams].toDouble(),
                                    kcalPer100g    = iRow[LogEntryItem.kcalPer100g].toDouble(),
                                    proteinPer100g = iRow[LogEntryItem.proteinPer100g]?.toDouble(),
                                    carbsPer100g   = iRow[LogEntryItem.carbsPer100g]?.toDouble(),
                                    fatPer100g     = iRow[LogEntryItem.fatPer100g]?.toDouble(),
                                )
                            }
                        LogEntryDto(
                            id            = eRow[LogEntry.id].toString(),
                            loggedAt      = eRow[LogEntry.loggedAt].toString(),
                            mealType      = eRow[LogEntry.mealType],
                            quickAddKcal  = eRow[LogEntry.quickAddKcal],
                            quickAddLabel = eRow[LogEntry.quickAddLabel],
                            items         = items,
                        )
                    }
                }
                call.respond(entries)
            }

            post("/in/log/quick-add") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val dto = call.receive<QuickAddRequestDto>()
                val id = runCatching { UUID.fromString(dto.id) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (dto.quickAddKcal <= 0) return@post call.respond(HttpStatusCode.BadRequest)
                val loggedAt = dto.loggedAt
                    ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                    ?: OffsetDateTime.now()

                val inserted = transaction {
                    val exists = LogEntry.selectAll()
                        .where { (LogEntry.id eq id) and (LogEntry.userId eq userId) }
                        .count() > 0
                    if (exists) return@transaction false
                    LogEntry.insert {
                        it[LogEntry.id]            = id
                        it[LogEntry.userId]        = userId
                        it[LogEntry.loggedAt]      = loggedAt
                        it[LogEntry.mealType]      = "unknown"
                        it[LogEntry.quickAddKcal]  = dto.quickAddKcal
                        it[LogEntry.quickAddLabel] = dto.quickAddLabel
                        it[LogEntry.createdAt]     = OffsetDateTime.now()
                    }
                    true
                }
                if (!inserted) return@post call.respond(HttpStatusCode.Conflict)

                call.respond(HttpStatusCode.Created, LogEntryDto(
                    id            = id.toString(),
                    loggedAt      = loggedAt.toString(),
                    mealType      = "unknown",
                    quickAddKcal  = dto.quickAddKcal,
                    quickAddLabel = dto.quickAddLabel,
                    items         = emptyList(),
                ))
            }

            delete("/in/log/{id}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val entryId = call.parameters["id"]
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                val deleted = transaction {
                    LogEntry.deleteWhere {
                        Op.build { (LogEntry.id eq entryId) and (LogEntry.userId eq userId) }
                    } > 0
                }
                if (deleted) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound)
            }

            get("/summary/today") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val dateParam = call.request.queryParameters["date"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val today = runCatching { java.time.LocalDate.parse(dateParam) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val dayStart = today.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                val dayEnd = dayStart.plusDays(1)

                val dto = transaction {
                    val profileRow = UserProfile.selectAll()
                        .where { UserProfile.userId eq userId }
                        .singleOrNull()
                        ?: return@transaction null

                    val profileInput = UserProfileInput(
                        heightCm      = profileRow[UserProfile.heightCm],
                        birthYear     = profileRow[UserProfile.birthYear],
                        sex           = profileRow[UserProfile.sex],
                        activityLevel = profileRow[UserProfile.activityLevel],
                        targetDeficit = profileRow[UserProfile.targetDeficit],
                        goalWeightKg  = profileRow[UserProfile.goalWeightKg].toDouble(),
                    )
                    val latestWeightKg = BodyWeight.selectAll()
                        .where { BodyWeight.userId eq userId }
                        .orderBy(BodyWeight.date, SortOrder.DESC)
                        .limit(1)
                        .singleOrNull()
                        ?.get(BodyWeight.kg)?.toDouble()

                    // Today + yesterday for static budget; today's row for actualBurnedSoFar
                    val recentEnergyRows = DailyEnergy.selectAll()
                        .where {
                            (DailyEnergy.userId eq userId) and
                            (DailyEnergy.date greaterEq today.minusDays(1)) and
                            (DailyEnergy.date lessEq today)
                        }
                        .map { EnergyRow(it[DailyEnergy.date], it[DailyEnergy.totalKcal]) }

                    val actualBurnedToday = recentEnergyRows.firstOrNull { it.date == today }?.totalKcal

                    // 30-day history (yesterday and earlier) for dynamic budget
                    val historyStart = today.minusDays(29)
                    val historyEnd   = today.minusDays(1)

                    val historyEnergy = DailyEnergy.selectAll()
                        .where {
                            (DailyEnergy.userId eq userId) and
                            (DailyEnergy.date greaterEq historyStart) and
                            (DailyEnergy.date lessEq historyEnd)
                        }
                        .associate { it[DailyEnergy.date] to it[DailyEnergy.totalKcal] }

                    val sportDates = Workout.selectAll()
                        .where {
                            (Workout.userId eq userId) and
                            (Workout.date greaterEq historyStart) and
                            (Workout.date lessEq historyEnd)
                        }
                        .map { it[Workout.date] }
                        .toSet()

                    val history = historyEnergy.map { (date, out) ->
                        HistoricalDay(
                            date        = date,
                            caloriesOut = out,
                            isSportDay  = date in sportDates,
                        )
                    }

                    val quickAddKcal = LogEntry.selectAll()
                        .where {
                            (LogEntry.userId eq userId) and
                            (LogEntry.quickAddKcal.isNotNull()) and
                            (LogEntry.loggedAt greaterEq dayStart) and
                            (LogEntry.loggedAt less dayEnd)
                        }
                        .sumOf { it[LogEntry.quickAddKcal] ?: 0 }

                    val itemKcal = LogEntry
                        .join(LogEntryItem, JoinType.INNER, LogEntry.id, LogEntryItem.logEntryId)
                        .selectAll()
                        .where {
                            (LogEntry.userId eq userId) and
                            (LogEntry.quickAddKcal.isNull()) and
                            (LogEntry.loggedAt greaterEq dayStart) and
                            (LogEntry.loggedAt less dayEnd)
                        }
                        .sumOf { row ->
                            (row[LogEntryItem.kcalPer100g].toDouble() * row[LogEntryItem.grams].toDouble() / 100.0).toInt()
                        }

                    val budget = BudgetComputer.compute(
                        today          = today,
                        profile        = profileInput,
                        latestWeightKg = latestWeightKg,
                        energyRows     = recentEnergyRows,
                        caloriesIn     = quickAddKcal + itemKcal,
                    )

                    val dynamic = BudgetComputer.computeDynamic(
                        history           = history,
                        actualBurnedToday = actualBurnedToday,
                    )

                    TodaySummaryDto(
                        date                  = today.toString(),
                        caloriesIn            = budget.caloriesIn,
                        caloriesOut           = budget.caloriesOut,
                        budgetRemaining       = budget.budgetRemaining,
                        targetDeficit         = budget.targetDeficit,
                        caloriesOutSource     = budget.caloriesOutSource,
                        expectedTodaySport    = dynamic.expectedTodaySport,
                        expectedTodayNonSport = dynamic.expectedTodayNonSport,
                        actualBurnedSoFar     = dynamic.actualBurnedSoFar,
                    )
                }

                if (dto == null) call.respond(HttpStatusCode.NotFound)
                else call.respond(dto)
            }
        }

        if (polarApiClient != null && polarCipher != null && polarSyncService != null) {
            polarRoutes(polarApiClient, polarCipher, polarSyncService)
        }

        ofdAdminSecret?.let { secret ->
            ofdAdminRoute(secret, ofdImportService)
        }

        System.getenv("E2E_PASSWORD")?.let { pwd ->
            e2eSeedRoute(pwd)
            clearRateLimitsRoute(pwd, usernameRateLimiter, ipRateLimiter)
        }
    }

    if (polarSyncService != null) {
        launch {
            while (true) {
                delay(1.hours)
                runCatching { polarSyncService.syncAll() }
            }
        }
    }
}

private fun buildPolarHttpClient(): io.ktor.client.HttpClient {
    return io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json()
        }
    }
}

private fun buildOfdHttpClient(): io.ktor.client.HttpClient {
    return io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json()
        }
    }
}
