package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** One usable row from the SBDB discovery query -- rows missing `M1`/`K1` never reach this type (§7.4.1). */
data class CometCandidate(
    val designation: String, // pdes, e.g. "C/2025 K1"
    val name: String?, // e.g. "(ATLAS)", derived from SBDB's `name` field
    val elements: CometElements,
    val magParams: CometMagParams,
)

@Serializable
private data class SbdbQueryResponse(
    val fields: List<String> = emptyList(),
    val data: List<List<JsonPrimitive?>> = emptyList(),
)

private val sbdbJson = Json { ignoreUnknownKeys = true }

/**
 * §7.4.1: `{signature, count, fields:[...], data:[[...],[...]]}` -- a
 * header/rows shape like SWPC's `/products/` files but with field names in
 * a separate array. Rows missing `M1` or `K1` are skipped (many comets have
 * no fitted total-magnitude parameters); `epoch`/`tp` are Julian dates
 * (TDB) converted to [Instant] here, once, so nothing downstream has to
 * remember to (§7.4.1's "easy to get silently wrong" warning).
 */
fun parseJplSbdbQuery(raw: String): List<CometCandidate> {
    val response = sbdbJson.decodeFromString<SbdbQueryResponse>(raw)
    val idx = response.fields.withIndex().associate { (i, name) -> name to i }
    val pdesCol = idx["pdes"] ?: return emptyList()
    val nameCol = idx["name"]
    val epochCol = idx["epoch"] ?: return emptyList()
    val eCol = idx["e"] ?: return emptyList()
    val qCol = idx["q"] ?: return emptyList()
    val iCol = idx["i"] ?: return emptyList()
    val omCol = idx["om"] ?: return emptyList()
    val wCol = idx["w"] ?: return emptyList()
    val tpCol = idx["tp"] ?: return emptyList()
    val m1Col = idx["M1"]
    val k1Col = idx["K1"]

    return response.data.mapNotNull { row ->
        runCatching {
            val m1 = m1Col?.let { row.getOrNull(it)?.doubleOrNull() } ?: return@mapNotNull null
            val k1 = k1Col?.let { row.getOrNull(it)?.doubleOrNull() } ?: return@mapNotNull null
            val pdes = row[pdesCol]?.content?.trim().orEmpty()
            if (pdes.isEmpty()) return@mapNotNull null
            val rawName = nameCol?.let { row.getOrNull(it)?.content?.trim() }?.takeUnless { it.isEmpty() }
            CometCandidate(
                designation = pdes,
                name = rawName?.let { if (it.startsWith("(")) it else "($it)" },
                elements = CometElements(
                    epoch = julianDateToInstant(row[epochCol].doubleOrThrow()),
                    eccentricity = row[eCol].doubleOrThrow(),
                    perihelionDistanceAu = row[qCol].doubleOrThrow(),
                    inclinationDeg = row[iCol].doubleOrThrow(),
                    ascendingNodeDeg = row[omCol].doubleOrThrow(),
                    argPerihelionDeg = row[wCol].doubleOrThrow(),
                    tpPerihelion = julianDateToInstant(row[tpCol].doubleOrThrow()),
                ),
                magParams = CometMagParams(m1 = m1, k1 = k1),
            )
        }.getOrNull()
    }
}

/** §7.4.1: "JD 2440587.5 = Unix epoch"; 86400 s/day. */
internal fun julianDateToInstant(jd: Double): Instant {
    val unixSeconds = (jd - 2440587.5) * 86400.0
    return Instant.fromEpochMilliseconds((unixSeconds * 1000.0).toLong())
}

private fun JsonPrimitive?.doubleOrNull(): Double? = this?.content?.toDoubleOrNull()
private fun JsonPrimitive?.doubleOrThrow(): Double = requireNotNull(this?.content?.toDouble()) { "missing required numeric field" }
