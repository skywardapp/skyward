package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.instantForSolarLongitudeInYear
import dev.fritze.skyward.core.astro.instantForSolarLongitudeNear
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.TimeWindow
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.illumination
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * §7.2: meteor showers, from the bundled Stellarium catalog (§16). `id =
 * "meteors"`, COMPUTED. Curates to the doc's "majors always kept, else
 * generic ZHR >= 10" rule (§7.2.1); "include minor showers" (all catalog
 * entries) is a settings toggle, not implemented by this class itself —
 * see [SourceSettings.params]`["includeMinor"]`.
 */
class MeteorShowerSource : EventSource {
    override val id = "meteors"
    override val phenomena = setOf(Phenomenon.METEOR_SHOWER)
    override val kind = SourceKind.COMPUTED

    override fun schedule(settings: SourceSettings): Schedule = Schedule.OnHorizonChange

    override suspend fun refresh(req: RefreshRequest): RefreshResult {
        val catalog = parseShowerCatalog(loadShowersJsonText())
        val includeMinor = req.settings.params["includeMinor"] == "true"

        val startYear = req.horizon.start.toLocalDateTime(TimeZone.UTC).year
        val endYear = req.horizon.end.toLocalDateTime(TimeZone.UTC).year

        val occurrences = mutableListOf<Occurrence>()
        for ((iauCode, shower) in catalog.showers) {
            if (shower.activity.isEmpty()) continue
            val generic = shower.activity.first()
            if (!includeMinor && !isCurated(iauCode, generic.zhr)) continue

            for (year in startYear..endYear) {
                val occurrence = buildOccurrenceForYear(iauCode, shower, generic, year, req.now) ?: continue
                if (occurrence.peakTime != null &&
                    occurrence.peakTime >= req.horizon.start &&
                    occurrence.peakTime <= req.horizon.end
                ) {
                    occurrences += occurrence
                }
            }
        }

        return RefreshResult(
            occurrences = occurrences,
            newState = req.state,
            nextRefreshHint = null,
            diagnostics = SourceDiagnostics(ok = true, itemCount = occurrences.size, lastSuccessAt = req.now),
        )
    }

    private fun isCurated(iauCode: String, genericZhr: Int?): Boolean =
        iauCode in MAJOR_SHOWER_CODES || (genericZhr != null && genericZhr >= 10)

    private fun buildOccurrenceForYear(
        iauCode: String,
        shower: ShowerJson,
        generic: ShowerActivityJson,
        year: Int,
        now: Instant,
    ): Occurrence? {
        val entry = shower.activity.firstOrNull { it.year == year.toString() }
        val peakDeg = entry?.peak ?: generic.peak ?: return null
        val startDeg = entry?.start ?: generic.start ?: return null
        val finishDeg = entry?.finish ?: generic.finish ?: return null
        val zhr = (entry?.zhr ?: generic.zhr)?.takeIf { it != -1 }
        val variableRaw = if ((entry?.zhr ?: generic.zhr) == -1) (entry?.variable ?: generic.variable) else null

        val peakTime = instantForSolarLongitudeInYear(peakDeg, year)
        val activityStart = instantForSolarLongitudeNear(startDeg, peakTime)
        val activityEnd = instantForSolarLongitudeNear(finishDeg, peakTime)

        // Radiant drift (§7.2.2 step 4): Stellarium's own model
        // (`MeteorShower::update`) advances the radiant by
        // `driftAlpha/Delta * (currentSolarLongitude - activity.peak)` — at
        // the peak instant itself that factor is exactly zero, so "radiant
        // at peak" is the catalog value unmodified. The drift fields matter
        // for a radiant position on an arbitrary *other* night (sky chart,
        // M6), not for this payload.
        val moonIllumination = illumination(Body.Moon, peakTime.toAstroTime()).phaseFraction

        return Occurrence(
            id = "ms:$iauCode:$year",
            phenomenon = Phenomenon.METEOR_SHOWER,
            sourceId = id,
            title = shower.designation,
            window = TimeWindow(activityStart, activityEnd),
            peakTime = peakTime,
            certainty = Certainty.CERTAIN,
            payload = MeteorShowerPayload(
                iauCode = iauCode,
                name = shower.designation,
                zhr = zhr,
                zhrNote = variableRaw?.let { "variable, $it" },
                radiantRaDeg = shower.radiantAlpha,
                radiantDecDeg = shower.radiantDelta,
                speedKmS = shower.speed,
                parentBody = shower.parentObj,
                activityStart = activityStart,
                activityEnd = activityEnd,
                moonIlluminationAtPeak = moonIllumination,
            ),
            fetchedAt = now,
            expiresAt = null,
        )
    }

    private companion object {
        val MAJOR_SHOWER_CODES = setOf("QUA", "LYR", "ETA", "SDA", "PER", "ORI", "LEO", "GEM", "URS")
    }
}
