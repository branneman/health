package org.branneman.health.polar

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.branneman.health.PolarStatusDto
import org.branneman.health.data.PolarAuth
import org.branneman.health.data.PolarConnectState
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

fun Route.polarRoutes(polarApiClient: PolarApiClient, cipher: TokenCipher, syncService: PolarSyncService) {
    authenticate("api") {
        post("/polar/sync") {
            val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
            try {
                syncService.syncForUser(userId)
                call.respond(HttpStatusCode.NoContent)
            } catch (_: PolarRateLimitException) {
                call.respond(HttpStatusCode.TooManyRequests)
            }
        }

        get("/polar/connect-url") {
            val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
            val stateBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val state = stateBytes.joinToString("") { "%02x".format(it) }

            transaction {
                PolarConnectState.insert {
                    it[PolarConnectState.state]     = state
                    it[PolarConnectState.userId]    = userId
                    it[PolarConnectState.expiresAt] = OffsetDateTime.now().plusMinutes(15)
                }
            }

            call.respond(mapOf("url" to polarApiClient.buildAuthorizationUrl(state)))
        }

        get("/polar/status") {
            val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
            val connected = transaction {
                PolarAuth.selectAll()
                    .where { PolarAuth.healthUserId eq userId }
                    .count() > 0
            }
            call.respond(PolarStatusDto(connected))
        }
    }

    get("/polar/callback") {
        val code  = call.parameters["code"]
        val state = call.parameters["state"]

        if (code == null || state == null) {
            call.respondText(errorHtml(), ContentType.Text.Html, HttpStatusCode.BadRequest)
            return@get
        }

        val userId: UUID? = transaction {
            val row = PolarConnectState.selectAll()
                .where { (PolarConnectState.state eq state) and (PolarConnectState.expiresAt greater OffsetDateTime.now()) }
                .singleOrNull()
                ?: return@transaction null
            PolarConnectState.deleteWhere { Op.build { PolarConnectState.state eq state } }
            row[PolarConnectState.userId]
        }

        if (userId == null) {
            call.respondText(errorHtml(), ContentType.Text.Html, HttpStatusCode.BadRequest)
            return@get
        }

        val tokenResponse = runCatching { polarApiClient.exchangeCode(code) }.getOrElse {
            call.respondText(errorHtml(), ContentType.Text.Html, HttpStatusCode.InternalServerError)
            return@get
        }

        runCatching { polarApiClient.registerUser(tokenResponse.accessToken, userId) }

        val encryptedToken = cipher.encrypt(tokenResponse.accessToken)
        transaction {
            PolarAuth.deleteWhere { Op.build { PolarAuth.healthUserId eq userId } }
            PolarAuth.insert {
                it[PolarAuth.userId]       = tokenResponse.xUserId.toString()
                it[PolarAuth.accessToken]  = encryptedToken
                it[PolarAuth.healthUserId] = userId
                it[PolarAuth.createdAt]    = OffsetDateTime.now()
            }
        }

        call.respondText(successHtml(), ContentType.Text.Html)
    }
}

private fun successHtml() = """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Polar Connected</title></head>
<body>
  <p>Polar connected successfully. Returning to the Health app…</p>
  <a href="branneman-health://polar/connected">Open Health App</a>
  <script>
    setTimeout(function () { window.location = 'branneman-health://polar/connected'; }, 500);
  </script>
</body>
</html>
""".trimIndent()

private fun errorHtml() = """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Error</title></head>
<body><p>An error occurred. Please return to the app and try again.</p></body>
</html>
""".trimIndent()
