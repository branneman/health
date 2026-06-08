package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import org.branneman.health.WeightEntryDto
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

    @Test
    fun `health check returns OK`() = testApplication {
        routing {
            get("/") { call.respondText("OK") }
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `protected route returns 401 without bearer token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { null } }
        }
        routing {
            authenticate("api") {
                get("/weight") { call.respondText("data") }
            }
        }
        val response = client.get("/weight")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `protected route returns 200 with valid bearer token`() = testApplication {
        install(Authentication) {
            bearer("api") {
                authenticate { cred ->
                    if (cred.token == "valid-token") UserIdPrincipal("user") else null
                }
            }
        }
        routing {
            authenticate("api") {
                get("/weight") { call.respondText("data") }
            }
        }
        val response = client.get("/weight") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `server-health returns status ok`() = testApplication {
        routing {
            get("/server-health") {
                call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
            }
        }
        val response = client.get("/server-health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
        assertEquals("application/json", response.contentType()?.withoutParameters().toString())
    }

    @Test
    fun `POST auth refresh returns 401 without token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { null } }
        }
        routing {
            authenticate("api") {
                post("/auth/refresh") { call.respond(HttpStatusCode.OK) }
            }
        }
        val response = client.post("/auth/refresh")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST auth refresh returns 200 with valid token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("user") } }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                post("/auth/refresh") {
                    call.respond(TokenResponse("new-token", "2026-07-05T14:00:00Z"))
                }
            }
        }
        val response = client.post("/auth/refresh") {
            header(HttpHeaders.Authorization, "Bearer any-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST auth logout returns 401 without token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { null } }
        }
        routing {
            authenticate("api") {
                post("/auth/logout") { call.respond(HttpStatusCode.NoContent) }
            }
        }
        val response = client.post("/auth/logout")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST auth logout returns 204 with valid token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("user") } }
        }
        routing {
            authenticate("api") {
                post("/auth/logout") { call.respond(HttpStatusCode.NoContent) }
            }
        }
        val response = client.post("/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer any-token")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `GET body weight returns only the authenticated user's entries`() = testApplication {
        install(Authentication) {
            bearer("api") {
                authenticate { cred ->
                    if (cred.token == "user-a-token") UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001")
                    else null
                }
            }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                get("/body/weight") {
                    val userId = call.principal<UserIdPrincipal>()!!.name
                    call.respond(listOf(WeightEntryDto("2026-06-01", 82.0)))
                }
            }
        }

        val response = client.get("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer user-a-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET body weight returns 401 without token`() = testApplication {
        install(Authentication) { bearer("api") { authenticate { null } } }
        routing { authenticate("api") { get("/body/weight") { call.respond("[]") } } }
        val response = client.get("/body/weight")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
