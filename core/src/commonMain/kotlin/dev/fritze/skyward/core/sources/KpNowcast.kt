package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.net.createHttpClient
import dev.fritze.skyward.core.net.getText
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.time.Instant

/** One sample of SWPC's 1-minute estimated planetary K index (§7.3.1). */
data class KpEstimate(val time: Instant, val estimatedKp: Double)

/**
 * §14.4 Row 1: "current estimated Kp (1-min product) as gauge".
 *
 * Deliberately *not* part of [AuroraSource.refresh]: the 1-minute product
 * feeds a dashboard gauge and nothing else — no occurrence, no rule, no
 * notification depends on it — so polling it on the refresh cycle would be
 * pure traffic for a screen nobody may have open. §7.3.2's tiering exists
 * precisely to avoid that kind of politeness failure. The dashboard fetches
 * it when it opens, and that is the only time it is fetched.
 *
 * The parser lives in `:core` with its sibling SWPC parsers regardless, so
 * §17.3's fixture-based tests cover it like every other SWPC shape.
 */
object KpNowcast {

    const val URL = "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"

    suspend fun fetchLatest(httpClient: HttpClient = createHttpClient()): KpEstimate? =
        parseEstimatedKp1m(httpClient.getText(URL)).maxByOrNull { it.time }

    /**
     * §7.3.1's "parser warning": files under `/json/` are conventional object
     * arrays (unlike `/products/`, which are arrays of string arrays). Rows
     * that don't parse are skipped rather than failing the batch — a single
     * bad minute should not blank the gauge.
     */
    fun parseEstimatedKp1m(raw: String): List<KpEstimate> =
        // Decoded element by element, not as one `List<Row>`: `time_tag` is a
        // required field, so a single row missing it would throw before any
        // per-row filtering could skip it, and blank the gauge over one bad
        // minute.
        json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val row = runCatching { json.decodeFromJsonElement(Row.serializer(), element) }.getOrNull() ?: return@mapNotNull null
            val kp = row.estimatedKp ?: row.kpIndex ?: return@mapNotNull null
            if (!kp.isFinite()) return@mapNotNull null
            runCatching { KpEstimate(parseUtcNoZoneInstant(row.timeTag), kp) }.getOrNull()
        }

    @Serializable
    private data class Row(
        @SerialName("time_tag") val timeTag: String,
        @SerialName("estimated_kp") val estimatedKp: Double? = null,
        @SerialName("kp_index") val kpIndex: Double? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
}
