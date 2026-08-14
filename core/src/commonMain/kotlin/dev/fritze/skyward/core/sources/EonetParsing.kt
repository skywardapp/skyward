package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** One usable EONET event -- events with no category or no geometry are dropped before reaching this type (§7.7). */
data class EonetEvent(
    val eonetId: String,
    val categoryId: String,
    val categoryTitle: String,
    val link: String,
    val closed: Instant?,
    val firstGeometryDate: Instant,
    val latestGeometryDate: Instant,
    val latestGeometry: GeoPoint,
    val magnitudeValue: Double?,
    val magnitudeUnit: String?,
)

@Serializable
private data class EonetResponse(val events: List<EonetEventJson> = emptyList())

@Serializable
private data class EonetEventJson(
    val id: String,
    val title: String = "",
    val link: String = "",
    val closed: String? = null,
    val categories: List<EonetCategoryJson> = emptyList(),
    val geometry: List<EonetGeometryJson> = emptyList(),
)

@Serializable
private data class EonetCategoryJson(val id: String, val title: String)

@Serializable
private data class EonetGeometryJson(
    val date: String,
    val type: String, // "Point" | "Polygon"
    val coordinates: JsonElement,
    val magnitudeValue: Double? = null,
    val magnitudeUnit: String? = null,
)

private val eonetJson = Json { ignoreUnknownKeys = true }

/**
 * §7.7: per event, `latestGeometry` = last element of `geometry[]` (Point
 * directly; Polygon -> arithmetic centroid of the outer ring's vertices,
 * per the doc -- not an area-weighted centroid). Events with no category or
 * no geometry entries are skipped -- there is nothing sane to build a
 * [dev.fritze.skyward.core.model.TerrestrialPayload] from otherwise.
 */
fun parseEonetEvents(raw: String): List<EonetEvent> {
    val response = eonetJson.decodeFromString<EonetResponse>(raw)
    return response.events.mapNotNull { event ->
        runCatching {
            val category = event.categories.firstOrNull() ?: return@mapNotNull null
            if (event.geometry.isEmpty()) return@mapNotNull null
            val first = event.geometry.first()
            val last = event.geometry.last()
            EonetEvent(
                eonetId = event.id,
                categoryId = category.id,
                categoryTitle = category.title,
                link = event.link,
                closed = event.closed?.let { parseUtcNoZoneInstant(it) },
                firstGeometryDate = parseUtcNoZoneInstant(first.date),
                latestGeometryDate = parseUtcNoZoneInstant(last.date),
                latestGeometry = parseEonetGeometryPoint(last.type, last.coordinates),
                magnitudeValue = last.magnitudeValue,
                magnitudeUnit = last.magnitudeUnit,
            )
        }.getOrNull()
    }
}

private fun parseEonetGeometryPoint(type: String, coordinates: JsonElement): GeoPoint = when (type) {
    "Point" -> {
        val arr = coordinates.jsonArray
        GeoPoint(latDeg = arr[1].jsonPrimitive.double, lonDeg = arr[0].jsonPrimitive.double)
    }
    "Polygon" -> {
        val outerRing = coordinates.jsonArray[0].jsonArray
        var points = outerRing.map { it.jsonArray }
        // GeoJSON rings repeat the first vertex as the last to close the
        // loop -- drop that duplicate so it isn't double-weighted in the
        // arithmetic mean (§7.7: "arithmetic centroid of ring vertices",
        // i.e. the distinct vertices, not the closing point twice).
        if (points.size > 1 && points.first() == points.last()) points = points.dropLast(1)
        GeoPoint(
            latDeg = points.map { it[1].jsonPrimitive.double }.average(),
            lonDeg = points.map { it[0].jsonPrimitive.double }.average(),
        )
    }
    else -> error("unsupported EONET geometry type: $type")
}
