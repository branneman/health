package org.branneman.health

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.*

class SyncDownloadTest {

    @Test
    fun `GET out-energy returns 401 without token`() = testApplication {
        install(Authentication) { bearer("api") { authenticate { null } } }
        routing { authenticate("api") { get("/out/energy") { call.respond("[]") } } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/out/energy").status)
    }

    @Test
    fun `GET out-energy returns 200 with token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001") } }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                get("/out/energy") { call.respond(emptyList<DailyEnergyDto>()) }
            }
        }
        val response = client.get("/out/energy") {
            header(HttpHeaders.Authorization, "Bearer any-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET out-workouts returns 401 without token`() = testApplication {
        install(Authentication) { bearer("api") { authenticate { null } } }
        routing { authenticate("api") { get("/out/workouts") { call.respond("[]") } } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/out/workouts").status)
    }

    @Test
    fun `GET out-workouts returns 200 with token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001") } }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                get("/out/workouts") { call.respond(emptyList<WorkoutDto>()) }
            }
        }
        val response = client.get("/out/workouts") {
            header(HttpHeaders.Authorization, "Bearer any-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET in-food-items returns 401 without token`() = testApplication {
        install(Authentication) { bearer("api") { authenticate { null } } }
        routing { authenticate("api") { get("/in/food-items") { call.respond("[]") } } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/in/food-items").status)
    }

    @Test
    fun `GET in-food-items returns 200 with token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001") } }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                get("/in/food-items") { call.respond(emptyList<FoodItemDto>()) }
            }
        }
        val response = client.get("/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer any-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET in-templates returns 401 without token`() = testApplication {
        install(Authentication) { bearer("api") { authenticate { null } } }
        routing { authenticate("api") { get("/in/templates") { call.respond("[]") } } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/in/templates").status)
    }

    @Test
    fun `GET in-templates returns 200 with token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001") } }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                get("/in/templates") { call.respond(emptyList<MealTemplateDto>()) }
            }
        }
        val response = client.get("/in/templates") {
            header(HttpHeaders.Authorization, "Bearer any-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET in-log returns 401 without token`() = testApplication {
        install(Authentication) { bearer("api") { authenticate { null } } }
        routing { authenticate("api") { get("/in/log") { call.respond("[]") } } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/in/log").status)
    }

    @Test
    fun `GET in-log returns 200 with token`() = testApplication {
        install(Authentication) {
            bearer("api") { authenticate { UserIdPrincipal("aaaaaaaa-0000-0000-0000-000000000001") } }
        }
        install(ContentNegotiation) { json() }
        routing {
            authenticate("api") {
                get("/in/log") { call.respond(emptyList<LogEntryDto>()) }
            }
        }
        val response = client.get("/in/log") {
            header(HttpHeaders.Authorization, "Bearer any-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
