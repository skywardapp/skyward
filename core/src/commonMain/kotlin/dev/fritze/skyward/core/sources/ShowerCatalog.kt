package dev.fritze.skyward.core.sources

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Raw shape of the bundled `showers.json` (§7.2.1, §16 — Stellarium
 * MeteorShowers plugin catalog, GPL-2.0-or-later). Field semantics verified
 * against the plugin's own parser (`MeteorShower.cpp`, `MeteorShowersMgr.cpp`)
 * rather than assumed — see the correction note on [ShowerActivityJson].
 */
@Serializable
internal data class ShowerCatalogJson(
    val shortName: String? = null,
    val version: Int = 0,
    val showers: Map<String, ShowerJson> = emptyMap(),
)

@Serializable
internal data class ShowerJson(
    val designation: String,
    @SerialName("IAUNo") val iauNo: String? = null,
    val activity: List<ShowerActivityJson> = emptyList(),
    val speed: Double? = null,
    val radiantAlpha: Double = 0.0,
    val radiantDelta: Double = 0.0,
    val driftAlpha: Double = 0.0,
    val driftDelta: Double = 0.0,
    val parentObj: String? = null,
    val pidx: Double? = null,
)

/**
 * §7.2.1 describes `start`/`finish`/`peak` as calendar dates (`MM.DD`); the
 * catalog actually stores them as **solar longitude in degrees** (confirmed
 * against `MeteorShower.cpp`'s `d.start = ...; // solar longitude at start`
 * and its `JDfromSolarLongitude` conversion) — this is R2 in §19
 * materializing exactly as anticipated ("verify against Stellarium plugin
 * source at implementation"). A year-specific entry (`year` != "generic")
 * that omits start/finish/peak/zhr inherits them from the generic (index 0)
 * entry — modeled here as nullable fields the caller merges explicitly,
 * which is more precise than the plugin's own `value == 0` sentinel check
 * (that check is wrong on its own terms for a generic entry whose real
 * value happens to be exactly 0, e.g. `ANT`'s `start: 0`).
 */
@Serializable
internal data class ShowerActivityJson(
    val year: String = "generic",
    val zhr: Int? = null, // -1 means variable; see [variable]
    val variable: String? = null, // "min-max", only meaningful when zhr == -1
    val start: Double? = null, // solar longitude, degrees
    val finish: Double? = null, // solar longitude, degrees
    val peak: Double? = null, // solar longitude, degrees
)

internal val showerCatalogJson = Json { ignoreUnknownKeys = true }

internal fun parseShowerCatalog(text: String): ShowerCatalogJson = showerCatalogJson.decodeFromString(text)
