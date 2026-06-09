package org.branneman.health

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

abstract class ApiTestBase {
    protected val serverUrl: String =
        System.getenv("API_TEST_SERVER_URL") ?: error("API_TEST_SERVER_URL not set")
    protected val apiEmail: String =
        System.getenv("API_TEST_EMAIL") ?: error("API_TEST_EMAIL not set")
    protected val apiPassword: String =
        System.getenv("API_TEST_PASSWORD") ?: error("API_TEST_PASSWORD not set")

    protected val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        expectSuccess = false
    }
}
