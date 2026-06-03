package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
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
}
