package dev.fritze.skyward.desktop.ui.skychart

import dev.fritze.skyward.core.astro.earthHeliocentricPositionEcliptic
import dev.fritze.skyward.core.astro.heliocentricPosition
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.Vector
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.rotationEclEqj
import kotlin.time.Instant

/** Where something sits in the local sky, plus what to say about it. */
data class SkyObject(
    val label: String,
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val kind: SkyObjectKind,
    /** Non-null for occurrence-backed objects — clicking one opens its detail pane. */
    val occurrenceId: String? = null,
    val annotation: String? = null,
    /** Moon only: illuminated fraction, for the phase glyph (§14.3). */
    val phaseFraction: Double? = null,
)

enum class SkyObjectKind { SUN, MOON, PLANET, RADIANT, COMET, ECLIPSE }

/** Everything drawn for one instant. Sun altitude drives the background gradient (§14.3). */
data class SkyScene(
    val time: Instant,
    val sunAltitudeDeg: Double,
    val objects: List<SkyObject>,
)

/**
 * §14.3's scene, computed with Astronomy Engine's `equator`→`horizon` pair
 * exactly as the design specifies. Pure and synchronous — the caller runs it
 * off the UI thread (§4.3).
 *
 * v1 is explicitly starless (§14.3, §19 R10): no catalog is loaded, and none
 * should be added here without revisiting that decision.
 */
object SkySceneBuilder {

    private val PLANETS = listOf(
        Body.Mercury to "Mercury",
        Body.Venus to "Venus",
        Body.Mars to "Mars",
        Body.Jupiter to "Jupiter",
        Body.Saturn to "Saturn",
    )

    fun build(location: SavedLocation, instant: Instant, occurrences: List<Occurrence>): SkyScene {
        val observer = Observer(location.point.latDeg, location.point.lonDeg, 0.0)
        val time = instant.toAstroTime()
        val objects = mutableListOf<SkyObject>()

        val sun = horizonOf(Body.Sun, time, observer)
        objects += SkyObject("Sun", sun.first, sun.second, SkyObjectKind.SUN)

        val moon = horizonOf(Body.Moon, time, observer)
        objects += SkyObject(
            label = "Moon",
            altitudeDeg = moon.first,
            azimuthDeg = moon.second,
            kind = SkyObjectKind.MOON,
            phaseFraction = illumination(Body.Moon, time).phaseFraction,
        )

        for ((body, label) in PLANETS) {
            val position = horizonOf(body, time, observer)
            objects += SkyObject(label, position.first, position.second, SkyObjectKind.PLANET)
        }

        objects += radiants(occurrences, instant, time, observer)
        objects += comets(occurrences, instant, time, observer)
        objects += eclipseMarkers(occurrences, instant, time, observer)

        return SkyScene(instant, sun.first, objects)
    }

    /**
     * §14.3: "meteor-shower radiants active that night (crosshair + IAU code
     * + expected-rate annotation)". The radiant is stored as J2000 (§7.2);
     * feeding it to `horizon()` as if it were of-date costs a few tenths of a
     * degree of precession over the app's horizon — invisible on a crosshair
     * that marks a region of sky tens of degrees across. The visibility model
     * makes the same approximation for the same reason.
     */
    private fun radiants(occurrences: List<Occurrence>, instant: Instant, time: Time, observer: Observer): List<SkyObject> =
        occurrences.filter { it.phenomenon == Phenomenon.METEOR_SHOWER && instant in it.window.start..it.window.end }
            .mapNotNull { occurrence ->
                val payload = occurrence.payload as? MeteorShowerPayload ?: return@mapNotNull null
                val position = horizon(time, observer, payload.radiantRaDeg / 15.0, payload.radiantDecDeg, Refraction.Normal)
                SkyObject(
                    label = payload.iauCode,
                    altitudeDeg = position.altitude,
                    azimuthDeg = position.azimuth,
                    kind = SkyObjectKind.RADIANT,
                    occurrenceId = occurrence.id,
                    // §10.5's honesty rule for ZHR applies on screen too: it is a
                    // perfect-conditions ceiling, never a promise.
                    annotation = payload.zhr?.let { "up to ~$it/hr in perfect conditions" } ?: payload.zhrNote,
                )
            }

    /** §14.3: "comet positions from the Kepler propagator (§7.4.2) for any comet occurrence active that night". */
    private fun comets(occurrences: List<Occurrence>, instant: Instant, time: Time, observer: Observer): List<SkyObject> =
        occurrences.filter { it.phenomenon == Phenomenon.COMET && instant in it.window.start..it.window.end }
            .mapNotNull { occurrence ->
                val payload = occurrence.payload as? CometPayload ?: return@mapNotNull null
                val cometHelio = heliocentricPosition(payload.elements, instant) ?: return@mapNotNull null
                val geocentricEcliptic = cometHelio - earthHeliocentricPositionEcliptic(instant)
                val equatorial = rotationEclEqj()
                    .rotate(Vector(geocentricEcliptic.x, geocentricEcliptic.y, geocentricEcliptic.z, time))
                    .toEquatorial()
                val position = horizon(time, observer, equatorial.ra, equatorial.dec, Refraction.Normal)
                SkyObject(
                    label = payload.designation,
                    altitudeDeg = position.altitude,
                    azimuthDeg = position.azimuth,
                    kind = SkyObjectKind.COMET,
                    occurrenceId = occurrence.id,
                    annotation = "predicted mag ${payload.peakMag}",
                )
            }

    /**
     * §14.3: "eclipse sun/moon position at eclipse times". Only while the
     * eclipse's own window contains the slider instant — outside it, the Sun
     * and Moon markers above already say where they are.
     */
    private fun eclipseMarkers(occurrences: List<Occurrence>, instant: Instant, time: Time, observer: Observer): List<SkyObject> =
        occurrences.filter {
            (it.phenomenon == Phenomenon.SOLAR_ECLIPSE || it.phenomenon == Phenomenon.LUNAR_ECLIPSE) &&
                instant in it.window.start..it.window.end
        }.map { occurrence ->
            val body = if (occurrence.phenomenon == Phenomenon.SOLAR_ECLIPSE) Body.Sun else Body.Moon
            val position = horizonOf(body, time, observer)
            SkyObject(
                label = occurrence.title,
                altitudeDeg = position.first,
                azimuthDeg = position.second,
                kind = SkyObjectKind.ECLIPSE,
                occurrenceId = occurrence.id,
            )
        }

    /** Apparent (refraction-corrected) altitude and azimuth of [body]. */
    private fun horizonOf(body: Body, time: Time, observer: Observer): Pair<Double, Double> {
        val equatorial = equator(body, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        val position = horizon(time, observer, equatorial.ra, equatorial.dec, Refraction.Normal)
        return position.altitude to position.azimuth
    }
}
