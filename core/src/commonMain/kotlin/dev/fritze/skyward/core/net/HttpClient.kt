package dev.fritze.skyward.core.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * §7.4.1 ("send a descriptive `User-Agent`") applies to every POLLED source,
 * not just JPL -- P1 (local-only, direct device-to-provider HTTPS) means
 * these are the only network calls the app ever makes, so every one of them
 * should identify itself politely to the public services it depends on.
 */
const val SKYWARD_USER_AGENT = "Skyward/1.0 (+https://github.com/skywardapp/skyward; local-only sky-event reminder app)"

/**
 * One shared client, engine auto-selected per platform via the engine
 * artifact already on that source set's classpath (ktor-client-okhttp for
 * androidMain, ktor-client-cio for desktopMain, both declared in
 * `core/build.gradle.kts`) -- no expect/actual needed for the client itself.
 *
 * `expectSuccess = true` so a non-2xx response throws
 * (ClientRequestException/ServerResponseException), letting POLLED sources
 * simply propagate it and rely on [dev.fritze.skyward.core.sources.SourceRunner]'s
 * existing catch-diagnose-backoff path (§6.2) instead of hand-rolling status
 * checks in each source.
 */
fun createHttpClient(): HttpClient = HttpClient {
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    install(UserAgent) { agent = SKYWARD_USER_AGENT }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    // §19 R3: transient network blips shouldn't burn a whole poll cycle into
    // exponential backoff on their own -- a couple of quick retries first.
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 2)
        retryOnException(maxRetries = 2, retryOnTimeout = true)
        exponentialDelay()
    }
}

suspend fun HttpClient.getText(url: String): String {
    val response: HttpResponse = get(url)
    return response.bodyAsText()
}
