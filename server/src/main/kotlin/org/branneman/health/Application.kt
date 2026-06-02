package org.branneman.health

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
    val apiUser = System.getenv("API_USER") ?: error("API_USER not set")
    val apiPassword = System.getenv("API_PASSWORD") ?: error("API_PASSWORD not set")

    Database.connect(HikariDataSource(HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 5
    }))

    install(ContentNegotiation) { json() }

    install(Authentication) {
        basic("api") {
            validate { creds ->
                if (creds.name == apiUser && creds.password == apiPassword)
                    UserIdPrincipal(creds.name)
                else null
            }
        }
    }

    routing {
        get("/") {
            call.respondText("OK")
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
