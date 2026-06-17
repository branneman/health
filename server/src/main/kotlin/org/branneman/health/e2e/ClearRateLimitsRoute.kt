package org.branneman.health.e2e

import io.ktor.http.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.branneman.health.auth.RateLimiter

@Serializable
data class ClearRateLimitsRequest(val username: String)

fun Route.clearRateLimitsRoute(
    e2ePassword: String,
    usernameRateLimiter: RateLimiter,
    ipRateLimiter: RateLimiter,
) {
    post("/internal/clear-rate-limits") {
        val bearer = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?: return@post call.respond(HttpStatusCode.Unauthorized)

        if (bearer != e2ePassword) return@post call.respond(HttpStatusCode.Unauthorized)

        val body = call.receive<ClearRateLimitsRequest>()
        usernameRateLimiter.reset(body.username)
        ipRateLimiter.reset(call.request.origin.remoteHost)

        call.respond(HttpStatusCode.OK)
    }
}
