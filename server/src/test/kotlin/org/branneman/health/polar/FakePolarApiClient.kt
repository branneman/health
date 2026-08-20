package org.branneman.health.polar

import java.time.LocalDate
import java.util.UUID

class FakePolarApiClient(
    private val activities: List<PolarActivity> = emptyList(),
    private val exercises: List<PolarExercise> = emptyList(),
    private val throwRateLimitForToken: String? = null,
    private val tokenToReturn: String = "fake-polar-token",
    private val xUserIdToReturn: Long = 12345L,
) : PolarApiClient {
    val registeredUsers = mutableListOf<UUID>()
    val exchangedCodes  = mutableListOf<String>()

    /** The date range the service last asked for — lets tests pin the sync window. */
    var lastActivitiesFrom: LocalDate? = null
        private set
    var lastActivitiesTo: LocalDate? = null
        private set

    override fun buildAuthorizationUrl(state: String) =
        "https://flow.polar.com/oauth2/authorization?client_id=test-client-id&state=$state"

    override suspend fun exchangeCode(code: String): PolarTokenResponse {
        exchangedCodes.add(code)
        return PolarTokenResponse(tokenToReturn, xUserIdToReturn)
    }

    override suspend fun registerUser(accessToken: String, memberIdUuid: UUID) {
        registeredUsers.add(memberIdUuid)
    }

    override suspend fun getActivities(accessToken: String, from: LocalDate, to: LocalDate): List<PolarActivity> {
        lastActivitiesFrom = from
        lastActivitiesTo   = to
        if (accessToken == throwRateLimitForToken) throw PolarRateLimitException()
        return activities
    }

    override suspend fun getExercises(accessToken: String): List<PolarExercise> {
        if (accessToken == throwRateLimitForToken) throw PolarRateLimitException()
        return exercises
    }
}
