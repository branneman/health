package org.branneman.health.network

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath

class AuthPluginConfig {
    lateinit var onRefreshNeeded: suspend () -> String?
    lateinit var onExpired: suspend () -> Unit
}

val AuthPlugin = createClientPlugin("AuthPlugin", ::AuthPluginConfig) {
    val onRefreshNeeded = pluginConfig.onRefreshNeeded
    val onExpired = pluginConfig.onExpired

    on(Send) { request ->
        val originalCall = proceed(request)
        if (originalCall.response.status != HttpStatusCode.Unauthorized) {
            return@on originalCall
        }

        // Never retry or refresh for /auth/ paths — prevents infinite refresh loops.
        // /auth/token never calls onExpired (it's a login attempt, not a session failure).
        val path = request.url.encodedPath
        if (path.startsWith("/auth/")) {
            if (!path.endsWith("/token")) onExpired()
            return@on originalCall
        }

        val newToken = onRefreshNeeded()
        if (newToken == null) {
            onExpired()
            return@on originalCall
        }

        request.headers["Authorization"] = "Bearer $newToken"
        proceed(request)
    }
}
