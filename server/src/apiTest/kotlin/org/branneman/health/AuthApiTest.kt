package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthApiTest : ApiTestBase() {

    @Test
    fun `login with valid credentials returns token`() = runTest {
        val response = client.post("$serverUrl/auth/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(apiEmail, apiPassword))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<TokenResponse>()
        assertTrue(body.token.isNotBlank())
        assertTrue(body.userId.isNotBlank())
    }

    @Test
    fun `login with wrong password returns 401`() = runTest {
        val response = client.post("$serverUrl/auth/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(apiEmail, "wrong-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `refresh with valid token returns new token`() = runTest {
        val token = login()
        val response = client.post("$serverUrl/auth/refresh") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<TokenResponse>()
        assertTrue(body.token.isNotBlank())
    }

    @Test
    fun `logout with valid token returns 204`() = runTest {
        val token = login()
        val response = client.post("$serverUrl/auth/logout") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `refresh after logout returns 401`() = runTest {
        val token = login()
        client.post("$serverUrl/auth/logout") { bearerAuth(token) }
        val response = client.post("$serverUrl/auth/refresh") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private suspend fun login(): String {
        val response = client.post("$serverUrl/auth/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(apiEmail, apiPassword))
        }
        return response.body<TokenResponse>().token
    }
}
