package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import kotlin.math.floor
import kotlin.time.Instant

/** §8.1 common contract. */
interface VisibilityModel {
    val phenomenon: Phenomenon

    /** Pure function; must not do I/O except reading pre-fetched grids passed in [ctx]. */
    fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult
}

data class VisibilityContext(
    val now: Instant,
    val ovationGrid: OvationGrid?, // decoded from source_state, may be null
)

/**
 * Decoded OVATION nowcast grid (§7.3.1): 360x181, 1x1 degree, geographic
 * coordinates. `prob[lon0to359][latMinus90to90]` — index = `(lon * 181) + (lat + 90)`.
 */
class OvationGrid(
    val observationTime: Instant,
    val forecastTime: Instant,
    private val prob: ByteArray, // 360*181 entries, values 0..100
) {
    init {
        require(prob.size == GRID_LON * GRID_LAT) {
            "expected a ${GRID_LON}x$GRID_LAT grid (${GRID_LON * GRID_LAT} cells), got ${prob.size}"
        }
    }

    /** Raw cell lookup — [lonDeg0to359] wraps automatically, [latDeg] must be in `[-90, 90]`. */
    fun probabilityAt(lonDeg0to359: Int, latDeg: Int): Int {
        val lon = lonDeg0to359.mod(GRID_LON)
        require(latDeg in -90..90) { "latDeg must be in [-90, 90], got $latDeg" }
        return prob[(lon * GRID_LAT) + (latDeg + 90)].toInt()
    }

    /**
     * Bilinear-interpolated probability at an arbitrary geographic point
     * (§8.4 NOWCAST: "look up the four grid cells around loc"). Handles the
     * longitude wrap at 359 -> 0 and clamps latitude at the poles (no cell
     * beyond +/-90 to interpolate against).
     */
    fun probabilityAt(point: GeoPoint): Double {
        val lon0to360 = point.lonDeg.mod(360.0)
        val lat = point.latDeg.coerceIn(-90.0, 90.0)

        val lon0 = floor(lon0to360).toInt().mod(GRID_LON)
        val lon1 = (lon0 + 1).mod(GRID_LON)
        val lonFrac = lon0to360 - floor(lon0to360)

        val lat0 = floor(lat).toInt().coerceIn(-90, 89)
        val lat1 = (lat0 + 1).coerceIn(-90, 90)
        val latFrac = (lat - lat0).coerceIn(0.0, 1.0)

        val p00 = probabilityAt(lon0, lat0)
        val p10 = probabilityAt(lon1, lat0)
        val p01 = probabilityAt(lon0, lat1)
        val p11 = probabilityAt(lon1, lat1)

        val top = p00 + (p10 - p00) * lonFrac
        val bottom = p01 + (p11 - p01) * lonFrac
        return top + (bottom - top) * latFrac
    }

    private companion object {
        const val GRID_LON = 360
        const val GRID_LAT = 181
    }
}
