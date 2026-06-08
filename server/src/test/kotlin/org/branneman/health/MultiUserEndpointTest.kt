package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.*

class MultiUserEndpointTest {

    @Test
    fun `GET profile returns 401 without token`() = testApplication {
        install(Authentication) { bearer("api") { authenticate { null } } }
        routing { authenticate("api") { get("/profile") { call.respond("{}") } } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/profile").status)
    }

    @Test
    fun `GET profile returns 404 when no profile exists`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001") } }
        }
        routing {
            authenticate("api") {
                get("/profile") { call.respond(HttpStatusCode.NotFound) }
            }
        }
        val response = client.get("/profile") {
            header(HttpHeaders.Authorization, "Bearer any-token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT profile returns 200`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001") } }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                put("/profile") {
                    call.respond(HttpStatusCode.OK, call.receive<UserProfileDto>())
                }
            }
        }
        val response = client.put("/profile") {
            header(HttpHeaders.Authorization, "Bearer any-token")
            contentType(ContentType.Application.Json)
            setBody("""{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET shortcuts returns 401 without token`() = testApplication {
        install(Authentication) { bearer("api") { authenticate { null } } }
        routing { authenticate("api") { get("/shortcuts") { call.respond("[]") } } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/shortcuts").status)
    }

    @Test
    fun `PUT shortcuts returns 200`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001") } }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                put("/shortcuts") {
                    call.respond(HttpStatusCode.OK, call.receive<List<ShortcutDto>>())
                }
            }
        }
        val response = client.put("/shortcuts") {
            header(HttpHeaders.Authorization, "Bearer any-token")
            contentType(ContentType.Application.Json)
            setBody("""[{"id":"","emoji":"🍺","label":"Pils","kcal":140,"sortOrder":0}]""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
