package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.ConjunctionPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
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
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NotificationCopyTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    // lonDeg = 0 so approximateLocalDateTime (§ADR 0005) equals the raw UTC
    // instant -- keeps every worked-example assertion below a direct,
    // unambiguous match against §10.5's literal text instead of needing to
    // account for a longitude offset on every expected time.
    private val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.0, 0.0), isPrimary = true, createdAt = now, modifiedAt = now)

    private fun rule(condition: Cond = Cond.VisibleAtLocation()) = Rule(
        id = "r", name = "n", enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE), locationIds = null,
        condition = condition, schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
        createdAt = now, modifiedAt = now,
    )

    private fun visres(
        visible: Boolean = true,
        quality: Quality = Quality.GOOD,
        localDetails: LocalDetails? = null,
        travelKm: Double? = null,
        travelBearingDeg: Double? = null,
        qualityAtNearest: Quality? = null,
    ) = VisibilityResult(visible, quality, localDetails, null, travelKm, travelBearingDeg, qualityAtNearest)

    // ---- compass ----

    @Test
    fun compassOfMapsBearingsToTheirNearestSixteenthPoint() {
        assertEquals("N", compassOf(0.0))
        assertEquals("SSE", compassOf(157.0))
        assertEquals("S", compassOf(180.0))
        assertEquals("W", compassOf(270.0))
        assertEquals("N", compassOf(359.9))
        assertEquals("", compassOf(null))
    }

    // ---- solar eclipse (§10.5's own worked examples) ----

    @Test
    fun farLeadSolarEclipseMatchesTheDesignDocWorkedExample() {
        val peak = Instant.parse("2027-08-02T10:48:00Z")
        val occ = Occurrence(
            id = "se:20270802", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "t",
            window = TimeWindow(peak - 2.hours, peak + 2.hours), peakTime = peak, certainty = Certainty.CERTAIN,
            payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), peak, emptyList(), 1.0),
            fetchedAt = now, expiresAt = null,
        )
        val details = LocalDetails.SolarEclipseLocal(partialBegin = peak - 1.hours, peak = peak, partialEnd = peak + 1.hours, maxObscuration = 0.92, sunAltAtPeakDeg = 45.0, localKind = SolarEclipseKind.PARTIAL)
        val result = visres(visible = true, localDetails = details, travelKm = 180.0, travelBearingDeg = 157.0)

        val copy = renderNotificationCopy(occ, home, result, rule(Cond.ReachableWithin(500.0)), fireAt = now, leadUntilAnchor = 30.days)

        assertTrue(copy.title.startsWith("Total solar eclipse"), copy.title)
        assertTrue(copy.body.contains("180 km SSE of Home"), copy.body)
        assertTrue(copy.body.contains("92% partial at"), copy.body)
    }

    @Test
    fun nearLeadSolarEclipseRendersTodayVariantWithLeaveByTime() {
        val peak = Instant.parse("2027-08-02T10:48:00Z")
        val occ = Occurrence(
            id = "se:20270802", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "t",
            window = TimeWindow(peak - 2.hours, peak + 2.hours), peakTime = peak, certainty = Certainty.CERTAIN,
            payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), peak, emptyList(), 1.0),
            fetchedAt = now, expiresAt = null,
        )
        val details = LocalDetails.SolarEclipseLocal(partialBegin = Instant.parse("2027-08-02T09:32:00Z"), peak = peak, partialEnd = peak + 1.hours, maxObscuration = 0.92, sunAltAtPeakDeg = 45.0, localKind = SolarEclipseKind.PARTIAL)
        val fireAt = Instant.parse("2027-08-02T08:00:00Z")
        val result = visres(visible = true, localDetails = details, travelKm = 180.0, travelBearingDeg = 157.0)

        val copy = renderNotificationCopy(occ, home, result, rule(Cond.ReachableWithin(500.0)), fireAt = fireAt, leadUntilAnchor = 2.hours)

        assertEquals("Eclipse today", copy.title)
        assertTrue(copy.body.contains("First contact at Home 09:32"), copy.body)
        assertTrue(copy.body.contains("max 10:48 (92%)"), copy.body)
        assertTrue(copy.body.contains("leave by 08:00 to be safe"), copy.body)
    }

    @Test
    fun travelGuidanceIsOmittedWhenBeyondTheRulesOwnReachableWithinKm() {
        val peak = now + 30.days
        val occ = Occurrence(
            id = "se:x", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "t",
            window = TimeWindow(peak - 2.hours, peak + 2.hours), peakTime = peak, certainty = Certainty.CERTAIN,
            payload = SolarEclipsePayload(SolarEclipseKind.PARTIAL, GeoPoint(0.0, 0.0), peak, emptyList(), 0.5),
            fetchedAt = now, expiresAt = null,
        )
        val result = visres(visible = false, quality = Quality.NONE, travelKm = 800.0, travelBearingDeg = 90.0)

        val copy = renderNotificationCopy(occ, home, result, rule(Cond.ReachableWithin(500.0)), fireAt = now, leadUntilAnchor = 30.days)

        assertTrue(!copy.body.contains("km"), "800km exceeds the rule's own 500km threshold: ${copy.body}")
    }

    // ---- meteor shower ----

    @Test
    fun meteorShowerCopyTranslatesZhrToPerMinute() {
        val peak = now + 5.days
        val occ = Occurrence(
            id = "ms:PER:2026", phenomenon = Phenomenon.METEOR_SHOWER, sourceId = "meteors", title = "Perseids",
            window = TimeWindow(peak - 1.days, peak + 1.days), peakTime = peak, certainty = Certainty.CERTAIN,
            payload = MeteorShowerPayload("PER", "Perseids", 60, null, 46.0, 58.0, 59.0, "109P", now, now, 0.08),
            fetchedAt = now, expiresAt = null,
        )
        val details = LocalDetails.MeteorLocal(
            bestViewingStart = Instant.parse("2026-01-06T23:40:00Z"), bestViewingEnd = Instant.parse("2026-01-07T04:10:00Z"),
            maxRadiantAltDeg = 62.0, moonIllumination = 0.08, moonUpDuringBest = false,
        )
        val copy = renderNotificationCopy(occ, home, visres(localDetails = details), rule(), fireAt = now, leadUntilAnchor = null)

        assertEquals("Perseids peak tonight", copy.title)
        assertTrue(copy.body.contains("Radiant up to 62°"), copy.body)
        assertTrue(copy.body.contains("Moon 8%"), copy.body)
        assertTrue(copy.body.contains("1 meteor/min"), copy.body)
        assertTrue(copy.body.contains("dark skies"), copy.body)
    }

    // ---- comet ----

    @Test
    fun cometCopyIncludesTheMandatoryDeviationCaveatAndMagnitudeBand() {
        val peak = now + 10.days
        val elements = CometElements(Instant.parse("2027-02-01T00:00:00Z"), 0.9, 1.0, 0.0, 0.0, 0.0, now)
        val occ = Occurrence(
            id = "cm:test", phenomenon = Phenomenon.COMET, sourceId = "jpl", title = "t",
            window = TimeWindow(peak - 1.days, peak + 1.days), peakTime = peak, certainty = Certainty.FORECAST,
            payload = CometPayload("C/2027 A1", "C/2027 A1", elements, CometMagParams(4.2, 4.5), now, 4.2, peak, 4.2),
            fetchedAt = now, expiresAt = null,
        )
        val details = LocalDetails.CometLocal(predictedMag = 4.2, elementEpoch = elements.epoch, maxAltDeg = 38.0, maxAltTime = Instant.parse("2026-01-11T04:10:00Z"), bestViewingStart = null, bestViewingEnd = Instant.parse("2026-01-11T05:20:00Z"))

        val copy = renderNotificationCopy(occ, home, visres(localDetails = details), rule(), fireAt = now, leadUntilAnchor = null)

        assertEquals("Comet C/2027 A1 near its best", copy.title)
        assertTrue(copy.body.contains("magnitude 4.2"), copy.body)
        assertTrue(copy.body.contains("binocular target"), copy.body)
        assertTrue(copy.body.contains("comets often deviate"), copy.body)
        assertTrue(copy.body.contains("Feb 1, 2027"), copy.body)
    }

    // ---- aurora nowcast ----

    @Test
    fun auroraNowcastCopyRendersIssuedTimeInUtc() {
        val occ = Occurrence(
            id = "au:now:test", phenomenon = Phenomenon.AURORA, sourceId = "swpc", title = "t",
            window = TimeWindow(now, now + 1.hours), peakTime = null, certainty = Certainty.FORECAST,
            payload = AuroraPayload(kpForecast = 6.0, forecastKind = AuroraForecastKind.NOWCAST, issuedAt = Instant.parse("2026-01-01T18:55:00Z")),
            fetchedAt = now, expiresAt = null,
        )
        val details = LocalDetails.AuroraLocal(geomagneticLatDeg = 60.0, kpNeeded = 3.0, ovationProbability = 62, darknessStart = Instant.parse("2026-01-01T21:40:00Z"), darknessEnd = null)

        val copy = renderNotificationCopy(occ, home, visres(localDetails = details), rule(), fireAt = now, leadUntilAnchor = null)

        assertEquals("Aurora possible NOW at Home", copy.title)
        assertTrue(copy.body.contains("OVATION 62% overhead probability (18:55 UTC forecast)"), copy.body)
        assertTrue(copy.body.contains("~21:40"), copy.body)
    }

    // ---- moon event / conjunction / terrestrial: smoke coverage ----

    @Test
    fun supermoonCopyNamesTheLocation() {
        val occ = Occurrence(
            id = "sm:202611", phenomenon = Phenomenon.MOON_EVENT, sourceId = "moon", title = "t",
            window = TimeWindow(now, now + 1.hours), peakTime = now, certainty = Certainty.CERTAIN,
            payload = MoonEventPayload(MoonEventKind.SUPERMOON, now, now, 356000.0),
            fetchedAt = now, expiresAt = null,
        )
        val copy = renderNotificationCopy(occ, home, visres(), rule(), fireAt = now, leadUntilAnchor = null)
        assertEquals("Supermoon tonight", copy.title)
        assertTrue(copy.body.endsWith("visible from Home."), copy.body)
    }

    @Test
    fun conjunctionCopyNamesTheLocation() {
        val occ = Occurrence(
            id = "cj:moon-venus:test", phenomenon = Phenomenon.CONJUNCTION, sourceId = "conjunctions", title = "t",
            window = TimeWindow(now, now + 1.hours), peakTime = now, certainty = Certainty.CERTAIN,
            payload = ConjunctionPayload("Venus", "Jupiter", 0.483, now),
            fetchedAt = now, expiresAt = null,
        )
        val copy = renderNotificationCopy(occ, home, visres(), rule(), fireAt = now, leadUntilAnchor = null)
        assertTrue(copy.title.contains("Venus") && copy.title.contains("Jupiter"), copy.title)
        assertTrue(copy.body.contains("0.5°"), copy.body)
    }

    @Test
    fun terrestrialCopyNamesTheLocation() {
        val occ = Occurrence(
            id = "eo:1", phenomenon = Phenomenon.TERRESTRIAL, sourceId = "eonet", title = "t",
            window = TimeWindow(now, now + 1.hours), peakTime = null, certainty = Certainty.FORECAST,
            payload = TerrestrialPayload("EONET_1", "volcanoes", "Volcanoes", GeoPoint(0.0, 0.0), now, null, null, "https://x", false),
            fetchedAt = now, expiresAt = null,
        )
        val copy = renderNotificationCopy(occ, home, visres(travelKm = 42.0, travelBearingDeg = 0.0), rule(), fireAt = now, leadUntilAnchor = null)
        assertTrue(copy.body.contains("42 km N of Home"), copy.body)
    }

    @Test
    fun lunarEclipseCopySmokeTest() {
        val occ = Occurrence(
            id = "le:test", phenomenon = Phenomenon.LUNAR_ECLIPSE, sourceId = "eclipse", title = "t",
            window = TimeWindow(now, now + 3.hours), peakTime = now + 1.hours, certainty = Certainty.CERTAIN,
            payload = LunarEclipsePayload(LunarEclipseKind.TOTAL, now, now + 30.minutes, now + 1.hours, now + 90.minutes, now + 2.hours, now + 3.hours),
            fetchedAt = now, expiresAt = null,
        )
        val details = LocalDetails.LunarEclipseLocal(visiblePhaseStart = now, visiblePhaseEnd = now + 3.hours, moonAltAtMidDeg = 45.0, umbralFractionVisible = 1.0)
        val copy = renderNotificationCopy(occ, home, visres(localDetails = details), rule(), fireAt = now, leadUntilAnchor = null)
        assertEquals("Total lunar eclipse tonight", copy.title)
        assertTrue(copy.body.contains("100%"), copy.body)
    }

    @Test
    fun auroraThreeDayCopySmokeTest() {
        val occ = Occurrence(
            id = "au:3d:test", phenomenon = Phenomenon.AURORA, sourceId = "swpc", title = "t",
            window = TimeWindow(now, now + 3.hours), peakTime = null, certainty = Certainty.FORECAST,
            payload = AuroraPayload(kpForecast = 6.0, forecastKind = AuroraForecastKind.THREE_DAY, issuedAt = now),
            fetchedAt = now, expiresAt = null,
        )
        val copy = renderNotificationCopy(occ, home, visres(visible = true), rule(), fireAt = now, leadUntilAnchor = null)
        assertTrue(copy.title.contains("Kp 6.0"), copy.title)
        assertTrue(copy.body.contains("Home"), copy.body)
    }

    // ---- APPROXIMATE hedge ----

    @Test
    fun approximateHedgePrefixesEveryTimeAndAppendsTheExplainerOnlyOnce() {
        val body = "Best 23:40–04:10 at Home."

        val first = applyApproximateHedge(body, isFirstApproximateEver = true)
        assertTrue(first.contains("around 23:40"), first)
        assertTrue(first.contains("around 04:10"), first)
        assertTrue(first.endsWith("enable exact alarms in Settings for precise reminders."), first)

        val subsequent = applyApproximateHedge(body, isFirstApproximateEver = false)
        assertTrue(subsequent.contains("around 23:40"), subsequent)
        assertTrue(!subsequent.contains("enable exact alarms"), subsequent)
    }

}
