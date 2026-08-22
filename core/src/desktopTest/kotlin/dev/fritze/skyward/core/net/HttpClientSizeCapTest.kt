package dev.fritze.skyward.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The [MAX_RESPONSE_BYTES] cap (issue #78). Lives in `desktopTest` rather
 * than `commonTest` because ktor-client-mock is a desktop-only test
 * dependency (`core/build.gradle.kts`), like the other MockEngine-based
 * source tests.
 */
class HttpClientSizeCapTest {

    private val url = "https://services.swpc.noaa.gov/text/example.txt"

    @Test
    fun readsANormalSizedBodyUnchanged() = runTest {
        val body = "a".repeat(100_000)
        assertEquals(body, client { respond(body, HttpStatusCode.OK) }.getText(url, maxBytes = 1_000_000))
    }

    @Test
    fun readsMultiByteCharactersSplitAcrossReadChunks() = runTest {
        // "é" is two UTF-8 bytes, so with 64 KiB read chunks one of them
        // straddles a chunk boundary — decoding chunk by chunk would corrupt
        // it, and every one of these bodies feeds a parser.
        val body = "é".repeat(200_000)
        assertEquals(body, client { respond(body, HttpStatusCode.OK) }.getText(url, maxBytes = 1_000_000))
    }

    @Test
    fun acceptsABodyExactlyAtTheCap() = runTest {
        val body = "b".repeat(1_024)
        assertEquals(body, client { respond(body, HttpStatusCode.OK) }.getText(url, maxBytes = 1_024))
    }

    @Test
    fun rejectsABodyOneByteOverTheCap() = runTest {
        val body = "b".repeat(1_025)
        val error = assertFailsWith<ResponseTooLargeException> {
            client { respond(body, HttpStatusCode.OK) }.getText(url, maxBytes = 1_024)
        }
        assertEquals(url, error.url)
        assertEquals(1_024L, error.limitBytes)
    }

    @Test
    fun rejectsAResponseWhoseDeclaredLengthIsOverTheCap() = runTest {
        // In production this check is what keeps a hugely-advertised payload
        // from being pulled down at all rather than merely discarded
        // afterwards. That "not pulled down" half can't be asserted here:
        // MockEngine enforces agreement between Content-Length and the body it
        // hands back, so a peer that lies about its length — the case the
        // header check is really for — is not expressible through it.
        val client = client {
            respond("b".repeat(2_048), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "2048"))
        }
        assertFailsWith<ResponseTooLargeException> { client.getText(url, maxBytes = 1_024) }
    }

    @Test
    fun rejectsAnOversizedBodyThatDeclaresNoLength() = runTest {
        // Chunked transfer encoding declares no Content-Length, so the header
        // check above cannot see it and the read itself is the only backstop.
        val client = client { respond(ByteReadChannel(ByteArray(4_096)), HttpStatusCode.OK) }
        assertFailsWith<ResponseTooLargeException> { client.getText(url, maxBytes = 1_024) }
    }

    @Test
    fun stopsPullingFromAnEndlessBodyInsteadOfBufferingIt() = runBlocking {
        // The case the cap exists for: a peer that keeps sending and never
        // closes. Without the cap this buffers until the 30 s request timeout
        // fires, by which time a fast link has put hundreds of megabytes in
        // the heap.
        //
        // Driven against [readTextAtMost] directly rather than through a
        // client, because MockEngine buffers a mock body itself — the reader's
        // restraint is invisible from the far side of it. Real time, not
        // `runTest`'s virtual clock, since the producer runs on a real
        // dispatcher.
        val chunk = ByteArray(CHUNK_BYTES) { 'x'.code.toByte() }
        val writtenBytes = AtomicLong()
        val channel = ByteChannel(autoFlush = true)
        val producer = launch(Dispatchers.Default) {
            try {
                while (true) {
                    channel.writeFully(chunk)
                    writtenBytes.addAndGet(chunk.size.toLong())
                }
            } catch (e: Throwable) {
                // The cap cancelled the channel out from under the writer.
                // That is the pass condition, not a failure.
            }
        }

        assertFailsWith<ResponseTooLargeException> { channel.readTextAtMost(url, CAP_BYTES) }
        // Joins rather than sampling: if cancelling the channel did not stop
        // the writer this hangs out to the timeout instead of passing by luck.
        withTimeout(30.seconds) { producer.join() }
        assertTrue(
            writtenBytes.get() < RUNAWAY_BYTES,
            "kept pulling: ${writtenBytes.get()} bytes written past a $CAP_BYTES-byte cap",
        )
    }

    private fun client(handler: MockRequestHandleScope.() -> HttpResponseData): HttpClient =
        HttpClient(MockEngine { handler() })

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val CAP_BYTES = 256L * 1024

        /**
         * Generous: a `ByteChannel` lets the writer run a bounded distance
         * ahead of the reader, so the bar is "did it stop", not an exact byte
         * count that would make this test brittle.
         */
        const val RUNAWAY_BYTES = 8L * 1024 * 1024
    }
}
