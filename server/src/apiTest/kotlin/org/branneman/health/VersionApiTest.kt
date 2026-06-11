package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionApiTest : ApiTestBase() {

    @Test
    fun `version endpoint returns 200 with non-blank sha`() = runTest {
        val response = client.get("$serverUrl/version")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<VersionResponse>()
        assertTrue(body.sha.isNotBlank())
    }
}
