package dev.fritze.skyward.core.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json

/**
 * §7.4.1 ("send a descriptive `User-Agent`") applies to every POLLED source,
 * not just JPL -- P1 (local-only, direct device-to-provider HTTPS) means
 * these are the only network calls the app ever makes, so every one of them
 * should identify itself politely to the public services it depends on.
 */
const val SKYWARD_USER_AGENT = "Skyward/1.0 (+https://github.com/skywardapp/skyward; local-only sky-event reminder app)"

/**
 * How much of a response body [getText] will read before giving up on it.
 *
 * Most of the five polled endpoints (§6.1) answer in tens of kilobytes; the
 * outlier is OVATION's aurora grid, whose captured fixture is ~0.9 MB. 8 MB
 * is therefore roughly nine times the largest legitimate payload — enough
 * headroom that a feed growing on its own doesn't trip it — and still small
 * enough that buffering one can't exhaust a phone's heap.
 *
 * The cap matters because the timeouts alone don't bound anything useful:
 * the 30 s request timeout permits hundreds of megabytes over a fast link,
 * so a compromised upstream or a MITM could OOM the process on every poll
 * simply by answering with an endless body.
 */
const val MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024

/**
 * Thrown when a response body exceeds [MAX_RESPONSE_BYTES]. Deliberately a
 * plain [Exception]: [dev.fritze.skyward.core.sources.SourceRunner] already
 * catches everything a source throws, records the message in that source's
 * diagnostics and backs off (§6.2), and an oversized body wants exactly that
 * treatment -- it is a broken upstream, not a bug to crash on.
 */
class ResponseTooLargeException(val url: String, val limitBytes: Long) :
    Exception("Response body from $url exceeds the $limitBytes byte cap")

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

/**
 * Fetches [url] as text, reading at most [maxBytes] and throwing
 * [ResponseTooLargeException] rather than buffering an unbounded body.
 *
 * Two checks, because either alone is insufficient: a declared
 * `Content-Length` over the cap is rejected before a single body byte is
 * read, and the streamed read is bounded anyway, since `Content-Length` is
 * absent under chunked transfer encoding and is in any case a claim made by
 * the very peer being defended against.
 *
 * Decoded as UTF-8 rather than by the response's declared charset: all five
 * endpoints serve UTF-8 (JSON, per RFC 8259, plus SWPC's ASCII text), and
 * accumulating raw bytes is what lets the read stop at a byte budget.
 */
suspend fun HttpClient.getText(url: String, maxBytes: Long = MAX_RESPONSE_BYTES): String {
    val response: HttpResponse = get(url)
    val declaredLength = response.contentLength()
    if (declaredLength != null && declaredLength > maxBytes) throw ResponseTooLargeException(url, maxBytes)
    return response.bodyAsChannel().readTextAtMost(url, maxBytes)
}

/**
 * `internal` rather than private so the bounded read can be driven directly
 * from a test: a mock HTTP engine buffers the whole mock body before the
 * client sees it, which is exactly the behaviour this function exists to
 * avoid and therefore the one thing it cannot be tested through.
 */
internal suspend fun ByteReadChannel.readTextAtMost(url: String, maxBytes: Long): String {
    require(maxBytes in 0..Int.MAX_VALUE.toLong()) { "maxBytes must fit in a ByteArray, was $maxBytes" }
    val chunks = mutableListOf<ByteArray>()
    var total = 0L
    val buffer = ByteArray(READ_CHUNK_BYTES)
    while (!isClosedForRead) {
        // Suspends until there is something to read or the channel closes, so
        // this loop cannot spin.
        val read = readAvailable(buffer, 0, buffer.size)
        if (read < 0) break // end of body
        if (read == 0) continue
        total += read
        // Strictly greater: a body of exactly maxBytes is still within budget.
        if (total > maxBytes) {
            val tooLarge = ResponseTooLargeException(url, maxBytes)
            // Cancelling tells the engine to stop pulling bytes off the socket
            // rather than politely draining the rest of an endless body.
            cancel(tooLarge)
            throw tooLarge
        }
        chunks += buffer.copyOf(read)
    }
    val body = ByteArray(total.toInt())
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(body, offset)
        offset += chunk.size
    }
    // Decoded once, over the whole body: a read boundary can fall inside a
    // multi-byte sequence, so decoding chunk by chunk would corrupt it.
    return body.decodeToString()
}

private const val READ_CHUNK_BYTES = 64 * 1024
