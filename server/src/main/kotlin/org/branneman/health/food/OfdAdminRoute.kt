package org.branneman.health.food

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("OfdAdminRoute")

fun Route.ofdAdminRoute(secret: String, importService: OfdImportService) {
    post("/admin/ofd-import") {
        val provided = call.request.headers["X-Admin-Secret"]
        if (provided != secret) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        if (importService.isImporting()) {
            call.respond(HttpStatusCode.Conflict, "Import already in progress")
            return@post
        }
        val mode = call.request.queryParameters["mode"] ?: "delta"
        call.respond(HttpStatusCode.Accepted)
        call.application.launch {
            runCatching {
                if (mode == "full") importService.importFull() else importService.importDelta()
            }.onSuccess { result ->
                log.info("OFD $mode import complete: upserted=${result.upserted} skipped=${result.skipped}")
            }.onFailure { e ->
                log.error("OFD $mode import failed", e)
            }
        }
    }
}
