package dev.fritze.skyward.core.sources

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** One 3-hourly slot of the SWPC planetary-Kp forecast (§7.3.1/§7.3.3). */
data class KpSlot(val time: Instant, val kp: Double, val state: String?)

private val swpcJson = Json { ignoreUnknownKeys = true }

/**
 * §7.3.1/Appendix C: `/products/` files are `[["header",...],["val",...],...]`
 * with *string* values, header-driven column lookup (order isn't
 * guaranteed to stay fixed). Throws on structurally invalid JSON -- callers
 * (i.e. [AuroraSource.refresh]) let that propagate to
 * [dev.fritze.skyward.core.sources.SourceRunner]'s existing diagnose+backoff
 * path (§19 R3) rather than swallowing it here. Individual malformed rows
 * are skipped rather than failing the whole parse.
 */
fun parseSwpcKpForecast(raw: String): List<KpSlot> {
    val rows = swpcJson.decodeFromString<List<List<String>>>(raw)
    if (rows.isEmpty()) return emptyList()
    val header = rows.first()
    val idx = header.withIndex().associate { (i, k) -> k to i }
    val timeCol = idx["time_tag"] ?: return emptyList()
    val kpCol = idx["kp"] ?: return emptyList()
    val observedCol = idx["observed"]

    return rows.drop(1).mapNotNull { row ->
        runCatching {
            // Kp is a quoted string in this shape; String.toDouble() accepts
            // "NaN"/"Infinity" outside JSON's own number grammar, so reject
            // those explicitly rather than let a corrupt Kp value silently
            // pass every threshold comparison downstream.
            val kp = row[kpCol].toDouble()
            require(kp.isFinite()) { "non-finite kp value" }
            KpSlot(
                time = parseUtcNoZoneInstant(row[timeCol]),
                kp = kp,
                state = observedCol?.let { row.getOrNull(it) },
            )
        }.getOrNull()
    }
}

@Serializable
private data class OvationRawResponse(
    @SerialName("Observation Time") val observationTime: String,
    @SerialName("Forecast Time") val forecastTime: String,
    val coordinates: List<JsonElement> = emptyList(),
)

/** Decoded OVATION nowcast payload, ready to persist (gzipped) and to build an [dev.fritze.skyward.core.visibility.OvationGrid] from. */
data class ParsedOvationGrid(val observationTime: Instant, val forecastTime: Instant, val probBytes: ByteArray, val cellsParsed: Int)

private const val GRID_LON = 360
private const val GRID_LAT = 181

/**
 * §7.3.1: object `{"Observation Time", "Forecast Time", "coordinates": [[lon,lat,prob],...]}`,
 * 360x181 triples. Builds the exact `(lon*181)+(lat+90)` byte layout
 * [dev.fritze.skyward.core.visibility.OvationGrid] expects. Malformed or
 * out-of-range triples are skipped (left at probability 0), not fatal --
 * this is what makes a truncated response degrade gracefully (§19 R3)
 * instead of crashing: any cells present are used, any missing stay 0.
 */
fun parseOvationGridJson(raw: String): ParsedOvationGrid {
    val response = swpcJson.decodeFromString<OvationRawResponse>(raw)
    val bytes = ByteArray(GRID_LON * GRID_LAT)
    var parsed = 0
    for (entry in response.coordinates) {
        val triple = runCatching { entry.jsonArray }.getOrNull() ?: continue
        if (triple.size < 3) continue
        // Double.toInt() maps NaN->0 and +/-Infinity->MAX/MIN_VALUE rather than
        // throwing, so a non-finite cell wouldn't be caught by runCatching --
        // require finiteness explicitly before converting.
        val lonD = runCatching { triple[0].jsonPrimitive.double }.getOrNull()?.takeIf { it.isFinite() } ?: continue
        val latD = runCatching { triple[1].jsonPrimitive.double }.getOrNull()?.takeIf { it.isFinite() } ?: continue
        val probD = runCatching { triple[2].jsonPrimitive.double }.getOrNull()?.takeIf { it.isFinite() } ?: continue
        val lon = lonD.toInt()
        val lat = latD.toInt()
        val prob = probD.toInt()
        if (lat < -90 || lat > 90) continue
        val lonIndex = lon.mod(GRID_LON)
        bytes[(lonIndex * GRID_LAT) + (lat + 90)] = prob.coerceIn(0, 100).toByte()
        parsed++
    }
    return ParsedOvationGrid(
        observationTime = parseUtcNoZoneInstant(response.observationTime),
        forecastTime = parseUtcNoZoneInstant(response.forecastTime),
        probBytes = bytes,
        cellsParsed = parsed,
    )
}

/**
 * SWPC timestamps are `"yyyy-MM-dd HH:mm:ss"`, UTC, with no zone suffix
 * (§7.3.1). Also tolerates an already-ISO `T`/`Z` form so the same helper
 * covers both the `/products/` and `/json/` shapes.
 */
internal fun parseUtcNoZoneInstant(raw: String): Instant {
    val trimmed = raw.trim()
    val withT = if (trimmed.contains('T')) trimmed else trimmed.replace(' ', 'T')
    val normalized = if (withT.endsWith('Z') || withT.contains('+')) withT else "${withT}Z"
    return Instant.parse(normalized)
}
