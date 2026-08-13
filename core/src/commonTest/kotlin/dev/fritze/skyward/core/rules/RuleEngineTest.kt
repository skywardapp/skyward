package dev.fritze.skyward.core.rules

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LunarEclipseKind
import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.MoonEventKind
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.model.VisibilityResult
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class RuleEngineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val loc = SavedLocation(id = "loc", name = "Home", point = GeoPoint(52.0, 7.6), isPrimary = true, createdAt = now, modifiedAt = now)

    private fun visres(
        // Every real model keeps visibleAtLocation == (quality != NONE) --
        // TerrestrialVisibilityModel is the sole, deliberate exception
        // (§8.8: it always reports false and defers to ReachableWithin) --
        // so default this the same way rather than requiring every call
        // site to keep the two arguments in sync by hand.
        visible: Boolean? = null,
        quality: Quality = Quality.NONE,
        travelKm: Double? = null,
        qualityAtNearest: Quality? = null,
    ) = VisibilityResult(
        visibleAtLocation = visible ?: (quality != Quality.NONE),
        quality = quality,
        localDetails = null,
        nearestVisiblePoint = null,
        travelDistanceKm = travelKm,
        travelBearingDeg = null,
        qualityAtNearestPoint = qualityAtNearest,
    )

    private fun solarOcc(kind: SolarEclipseKind = SolarEclipseKind.TOTAL, peakTime: Instant = now) = Occurrence(
        id = "se:test", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "t",
        window = TimeWindow(peakTime, peakTime), peakTime = peakTime, certainty = Certainty.CERTAIN,
        payload = SolarEclipsePayload(kind, GeoPoint(0.0, 0.0), peakTime, emptyList(), 1.0),
        fetchedAt = now, expiresAt = null,
    )

    private fun lunarOcc(kind: LunarEclipseKind = LunarEclipseKind.TOTAL) = Occurrence(
        id = "le:test", phenomenon = Phenomenon.LUNAR_ECLIPSE, sourceId = "eclipse", title = "t",
        window = TimeWindow(now, now), peakTime = now, certainty = Certainty.CERTAIN,
        payload = LunarEclipsePayload(kind, now, now, now, now, now, now),
        fetchedAt = now, expiresAt = null,
    )

    private fun meteorOcc(zhr: Int? = 100, moonIllum: Double = 0.1) = Occurrence(
        id = "ms:test", phenomenon = Phenomenon.METEOR_SHOWER, sourceId = "meteors", title = "t",
        window = TimeWindow(now, now), peakTime = now, certainty = Certainty.CERTAIN,
        payload = MeteorShowerPayload("PER", "Perseids", zhr, null, 46.0, 58.0, 59.0, "109P", now, now, moonIllum),
        fetchedAt = now, expiresAt = null,
    )

    private fun auroraOcc(kp: Double = 5.0, kind: AuroraForecastKind = AuroraForecastKind.THREE_DAY) = Occurrence(
        id = "au:test", phenomenon = Phenomenon.AURORA, sourceId = "swpc", title = "t",
        window = TimeWindow(now, now), peakTime = null, certainty = Certainty.FORECAST,
        payload = AuroraPayload(kp, kind, now),
        fetchedAt = now, expiresAt = null,
    )

    private fun cometOcc(peakMag: Double = 4.0) = Occurrence(
        id = "cm:test", phenomenon = Phenomenon.COMET, sourceId = "jpl", title = "t",
        window = TimeWindow(now, now), peakTime = now, certainty = Certainty.FORECAST,
        payload = CometPayload(
            "C/test", null,
            CometElements(now, 0.9, 1.0, 0.0, 0.0, 0.0, now),
            CometMagParams(4.0, 4.5),
            now, peakMag, now, peakMag,
        ),
        fetchedAt = now, expiresAt = null,
    )

    private fun terrestrialOcc(categoryId: String = "volcanoes") = Occurrence(
        id = "eo:test", phenomenon = Phenomenon.TERRESTRIAL, sourceId = "eonet", title = "t",
        window = TimeWindow(now, now), peakTime = null, certainty = Certainty.FORECAST,
        payload = TerrestrialPayload("EONET_1", categoryId, "Volcanoes", GeoPoint(0.0, 0.0), now, null, null, "https://x", false),
        fetchedAt = now, expiresAt = null,
    )

    private fun moonEventOcc() = Occurrence(
        id = "sm:test", phenomenon = Phenomenon.MOON_EVENT, sourceId = "moon", title = "t",
        window = TimeWindow(now, now), peakTime = now, certainty = Certainty.CERTAIN,
        payload = MoonEventPayload(MoonEventKind.SUPERMOON, now, now, 356000.0),
        fetchedAt = now, expiresAt = null,
    )

    // ---- combinators ----

    @Test
    fun andRequiresAllChildrenTrue() {
        val cond = Cond.And(listOf(Cond.CertaintyIs(Certainty.CERTAIN), Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL))))
        assertTrue(RuleEngine.evaluate(cond, solarOcc(), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(cond, solarOcc(kind = SolarEclipseKind.PARTIAL), loc, visres(), now))
    }

    @Test
    fun orRequiresAnyChildTrue() {
        val cond = Cond.Or(listOf(Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL)), Cond.EclipseKindIn(setOf(SolarEclipseKind.PARTIAL))))
        assertTrue(RuleEngine.evaluate(cond, solarOcc(kind = SolarEclipseKind.PARTIAL), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(cond, solarOcc(kind = SolarEclipseKind.ANNULAR), loc, visres(), now))
    }

    @Test
    fun notNegates() {
        val cond = Cond.Not(Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL)))
        assertFalse(RuleEngine.evaluate(cond, solarOcc(kind = SolarEclipseKind.TOTAL), loc, visres(), now))
        assertTrue(RuleEngine.evaluate(cond, solarOcc(kind = SolarEclipseKind.PARTIAL), loc, visres(), now))
    }

    // ---- visibility-derived ----

    @Test
    fun visibleAtLocationComparesQualityOrdinally() {
        assertTrue(RuleEngine.evaluate(Cond.VisibleAtLocation(Quality.GOOD), solarOcc(), loc, visres(quality = Quality.EXCELLENT), now))
        assertTrue(RuleEngine.evaluate(Cond.VisibleAtLocation(Quality.GOOD), solarOcc(), loc, visres(quality = Quality.GOOD), now))
        assertFalse(RuleEngine.evaluate(Cond.VisibleAtLocation(Quality.GOOD), solarOcc(), loc, visres(quality = Quality.MARGINAL), now))
    }

    @Test
    fun reachableWithinIsSatisfiedLocallyOrByTravel() {
        val cond = Cond.ReachableWithin(km = 500.0, minQualityThere = Quality.EXCELLENT)
        // Locally already at/above the threshold quality.
        assertTrue(RuleEngine.evaluate(cond, solarOcc(), loc, visres(quality = Quality.EXCELLENT), now))
        // Not locally, but reachable within distance at the right quality.
        assertTrue(RuleEngine.evaluate(cond, solarOcc(), loc, visres(quality = Quality.MARGINAL, travelKm = 200.0, qualityAtNearest = Quality.EXCELLENT), now))
        // Reachable distance, but the far quality doesn't clear the bar.
        assertFalse(RuleEngine.evaluate(cond, solarOcc(), loc, visres(quality = Quality.MARGINAL, travelKm = 200.0, qualityAtNearest = Quality.GOOD), now))
        // Right quality, but too far.
        assertFalse(RuleEngine.evaluate(cond, solarOcc(), loc, visres(quality = Quality.MARGINAL, travelKm = 900.0, qualityAtNearest = Quality.EXCELLENT), now))
        // No travel data at all.
        assertFalse(RuleEngine.evaluate(cond, solarOcc(), loc, visres(quality = Quality.MARGINAL), now))
    }

    @Test
    fun visibleAtLocationAndReachableWithinIgnoreQualityWhenTheModelSaysNotLocallyVisible() {
        // TerrestrialVisibilityModel (§8.8) always reports visibleAtLocation
        // = false with a fixed non-NONE quality, deliberately, so that
        // ReachableWithin's *local* branch never trusts quality alone --
        // only the travel (distance + qualityAtNearestPoint) branch may
        // pass such a result.
        val notLocallyVisible = visres(visible = false, quality = Quality.GOOD)
        assertFalse(RuleEngine.evaluate(Cond.VisibleAtLocation(Quality.GOOD), solarOcc(), loc, notLocallyVisible, now))

        val cond = Cond.ReachableWithin(km = 300.0, minQualityThere = Quality.GOOD)
        assertFalse(RuleEngine.evaluate(cond, solarOcc(), loc, notLocallyVisible, now), "quality alone must not satisfy the local branch")
        val reachableByTravel = visres(visible = false, quality = Quality.GOOD, travelKm = 100.0, qualityAtNearest = Quality.GOOD)
        assertTrue(RuleEngine.evaluate(cond, solarOcc(), loc, reachableByTravel, now), "the travel branch is unaffected")
    }

    // ---- phenomenon-parameter conditions, including the missing-field=false rule ----

    @Test
    fun kpAtLeastOnlyAppliesToAurora() {
        assertTrue(RuleEngine.evaluate(Cond.KpAtLeast(5.0), auroraOcc(kp = 6.0), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.KpAtLeast(5.0), auroraOcc(kp = 4.0), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.KpAtLeast(5.0), solarOcc(), loc, visres(), now), "wrong payload type must be false, not an error")
    }

    @Test
    fun zhrAtLeastTreatsNullZhrAsZero() {
        assertTrue(RuleEngine.evaluate(Cond.ZhrAtLeast(20), meteorOcc(zhr = 60), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.ZhrAtLeast(20), meteorOcc(zhr = null), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.ZhrAtLeast(20), solarOcc(), loc, visres(), now))
    }

    @Test
    fun magnitudeAtMostTestsPeakMagNotIngestMag() {
        assertTrue(RuleEngine.evaluate(Cond.MagnitudeAtMost(5.0), cometOcc(peakMag = 4.0), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.MagnitudeAtMost(3.0), cometOcc(peakMag = 4.0), loc, visres(), now))
    }

    @Test
    fun eclipseKindInAndLunarKindIn() {
        assertTrue(RuleEngine.evaluate(Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL, SolarEclipseKind.ANNULAR)), solarOcc(kind = SolarEclipseKind.ANNULAR), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL)), solarOcc(kind = SolarEclipseKind.PARTIAL), loc, visres(), now))
        assertTrue(RuleEngine.evaluate(Cond.LunarKindIn(setOf(LunarEclipseKind.TOTAL)), lunarOcc(kind = LunarEclipseKind.TOTAL), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.LunarKindIn(setOf(LunarEclipseKind.TOTAL)), solarOcc(), loc, visres(), now))
    }

    @Test
    fun moonIlluminationAtMost() {
        assertTrue(RuleEngine.evaluate(Cond.MoonIlluminationAtMost(0.3), meteorOcc(moonIllum = 0.1), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.MoonIlluminationAtMost(0.3), meteorOcc(moonIllum = 0.5), loc, visres(), now))
    }

    @Test
    fun eonetCategoryIn() {
        assertTrue(RuleEngine.evaluate(Cond.EonetCategoryIn(setOf("volcanoes")), terrestrialOcc("volcanoes"), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.EonetCategoryIn(setOf("volcanoes")), terrestrialOcc("wildfires"), loc, visres(), now))
    }

    @Test
    fun certaintyIs() {
        assertTrue(RuleEngine.evaluate(Cond.CertaintyIs(Certainty.CERTAIN), solarOcc(), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.CertaintyIs(Certainty.FORECAST), solarOcc(), loc, visres(), now))
    }

    @Test
    fun auroraKindIs() {
        assertTrue(RuleEngine.evaluate(Cond.AuroraKindIs(AuroraForecastKind.NOWCAST), auroraOcc(kind = AuroraForecastKind.NOWCAST), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.AuroraKindIs(AuroraForecastKind.NOWCAST), auroraOcc(kind = AuroraForecastKind.THREE_DAY), loc, visres(), now))
    }

    @Test
    fun occurrenceIdIs() {
        val occ = solarOcc()
        assertTrue(RuleEngine.evaluate(Cond.OccurrenceIdIs(occ.id), occ, loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.OccurrenceIdIs("other"), occ, loc, visres(), now))
    }

    // ---- temporal ----

    @Test
    fun peakInDaysAheadExcludesThePastAndFarFuture() {
        assertTrue(RuleEngine.evaluate(Cond.PeakInDaysAhead(7), solarOcc(peakTime = now + 3.days), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.PeakInDaysAhead(7), solarOcc(peakTime = now + 10.days), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.PeakInDaysAhead(7), solarOcc(peakTime = now - 1.days), loc, visres(), now), "a past peak is not 'ahead'")
    }

    @Test
    fun peakOnWeekendUsesApproximateLocalTime() {
        // loc.point.lonDeg = 7.6 -> negligible offset from UTC.
        // 2026-01-02 is a Friday.
        val fridayEvening = Instant.parse("2026-01-02T19:00:00Z")
        val fridayAfternoon = Instant.parse("2026-01-02T14:00:00Z")
        assertTrue(RuleEngine.evaluate(Cond.PeakOnWeekend(includeFridayNight = true), solarOcc(peakTime = fridayEvening), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.PeakOnWeekend(includeFridayNight = true), solarOcc(peakTime = fridayAfternoon), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(Cond.PeakOnWeekend(includeFridayNight = false), solarOcc(peakTime = fridayEvening), loc, visres(), now))
    }

    @Test
    fun peakInLocalHoursWrapsMidnight() {
        val cond = Cond.PeakInLocalHours(fromHour = 22, toHour = 6)
        assertTrue(RuleEngine.evaluate(cond, solarOcc(peakTime = Instant.parse("2026-01-02T23:00:00Z")), loc, visres(), now))
        assertTrue(RuleEngine.evaluate(cond, solarOcc(peakTime = Instant.parse("2026-01-02T02:00:00Z")), loc, visres(), now))
        assertFalse(RuleEngine.evaluate(cond, solarOcc(peakTime = Instant.parse("2026-01-02T12:00:00Z")), loc, visres(), now))
    }

    // ---- Rule.matches gating (enabled / phenomena / locationIds) ----

    @Test
    fun matchesRespectsEnabledPhenomenaAndLocationIds() {
        val base = Rule(
            id = "r", name = "n", enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE),
            locationIds = null, condition = Cond.CertaintyIs(Certainty.CERTAIN),
            schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
            createdAt = now, modifiedAt = now,
        )
        assertTrue(RuleEngine.matches(base, solarOcc(), loc, visres(), now))
        assertFalse(RuleEngine.matches(base.copy(enabled = false), solarOcc(), loc, visres(), now))
        assertFalse(RuleEngine.matches(base, lunarOcc(), loc, visres(), now), "wrong phenomenon")
        assertFalse(RuleEngine.matches(base.copy(locationIds = listOf("other")), solarOcc(), loc, visres(), now))
        assertTrue(RuleEngine.matches(base.copy(locationIds = listOf(loc.id)), solarOcc(), loc, visres(), now))
    }

    // ---- serialization round-trip for every Cond type (§17.4) ----

    @Test
    fun everyCondTypeRoundTripsThroughJson() {
        val samples: List<Cond> = listOf(
            Cond.And(listOf(Cond.CertaintyIs(Certainty.CERTAIN))),
            Cond.Or(listOf(Cond.CertaintyIs(Certainty.CERTAIN))),
            Cond.Not(Cond.CertaintyIs(Certainty.CERTAIN)),
            Cond.VisibleAtLocation(Quality.GOOD),
            Cond.ReachableWithin(500.0, Quality.EXCELLENT),
            Cond.KpAtLeast(5.0),
            Cond.ZhrAtLeast(20),
            Cond.MagnitudeAtMost(4.0),
            Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL, SolarEclipseKind.HYBRID)),
            Cond.LunarKindIn(setOf(LunarEclipseKind.TOTAL)),
            Cond.MoonIlluminationAtMost(0.6),
            Cond.EonetCategoryIn(setOf("volcanoes", "wildfires")),
            Cond.CertaintyIs(Certainty.FORECAST),
            Cond.AuroraKindIs(AuroraForecastKind.NOWCAST),
            Cond.OccurrenceIdIs("se:20260812"),
            Cond.PeakInDaysAhead(7),
            Cond.PeakOnWeekend(includeFridayNight = false),
            Cond.PeakInLocalHours(22, 6),
        )
        for (cond in samples) {
            val encoded = json.encodeToString(Cond.serializer(), cond)
            val decoded = json.decodeFromString(Cond.serializer(), encoded)
            assertEquals(cond, decoded, "round-trip mismatch for $cond")
        }
    }

    @Test
    fun aFullRuleRoundTripsThroughJson() {
        val rule = defaultRules(now).first()
        val encoded = json.encodeToString(Rule.serializer(), rule)
        val decoded = json.decodeFromString(Rule.serializer(), encoded)
        assertEquals(rule, decoded)
    }
}
