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
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import org.branneman.health.auth.AuthService
import org.branneman.health.auth.DbLoginAttemptsStore
import org.branneman.health.auth.LoginResult
import org.branneman.health.auth.RateLimiter
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.transactions.transaction

object BodyWeight : Table("body_weight") {
    val id = uuid("id")
    val date = date("date")
    val kg = decimal("kg", 5, 2)
    override val primaryKey = PrimaryKey(id)
}

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

    Database.connect(HikariDataSource(HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 5
    }))

    val authService = AuthService()
    val ipRateLimiter = RateLimiter(store = DbLoginAttemptsStore())
    val usernameRateLimiter = RateLimiter(store = DbLoginAttemptsStore())

    install(XForwardedHeaders)
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

    routing {
        get("/") {
            call.respondText("OK")
        }

        get("/server-health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
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
                        call.respond(TokenResponse(result.token, result.expiresAt.toString()))
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
                            TokenResponse(result.token, result.expiresAt.toString())
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
            get("/weight") {
                val entries = transaction {
                    BodyWeight.selectAll()
                        .orderBy(BodyWeight.date, SortOrder.DESC)
                        .map {
                            WeightEntryDto(
                                it[BodyWeight.date].toString(),
                                it[BodyWeight.kg].toDouble()
                            )
                        }
                }
                call.respond(entries)
            }
        }
    }
}
