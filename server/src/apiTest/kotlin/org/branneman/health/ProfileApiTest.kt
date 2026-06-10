package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.branneman.health.UserProfileDto
import org.junit.Test
import kotlin.test.assertEquals

class ProfileApiTest : ApiTestBase() {

    @Test
    fun `PUT profile returns 200 then GET returns same data`() = runTest {
        val token = login()
        val dto = UserProfileDto(177, 1986, "male", 74.0, "lightly_active", 300, "loss", false)

        val putResp = client.put("$serverUrl/profile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(dto)
        }
        assertEquals(HttpStatusCode.OK, putResp.status)

        val getResp = client.get("$serverUrl/profile") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val returned = getResp.body<UserProfileDto>()
        assertEquals(177, returned.heightCm)
        assertEquals("male", returned.sex)
        assertEquals(300, returned.targetDeficit)
    }
}
