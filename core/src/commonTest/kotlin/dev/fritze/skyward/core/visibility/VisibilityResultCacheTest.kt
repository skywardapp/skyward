package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.model.VisibilityResult
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §9.2 step 1/§11: [VisibilityResultCache] is the read-through cache that
 * sits in front of `VisibilityModel`s inside `Planner`/`computeUpcomingItems`
 * (issue #18 -- the `visibility_cache` table existed but nothing read or
 * wrote it). Covers the two regression cases the issue names: a stale-date
 * comet entry must not be served, and any `data_version` mismatch must force
 * recomputation.
 */
class VisibilityResultCacheTest {

    private val now = Instant.parse("2026-06-15T12:00:00Z")
    private val utc = TimeZone.UTC
    private val loc = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.0, 11.0), isPrimary = true, createdAt = now, modifiedAt = now)

    private class CountingModel(override val phenomenon: Phenomenon, private val response: VisibilityResult) : VisibilityModel {
        var evaluations = 0
        override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
            evaluations++
            return response
        }
    }

    private fun eclipseOcc(fetchedAt: Instant) = Occurrence(
        id = "se:1", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "Eclipse",
        window = TimeWindow(now, now + 3.hours), peakTime = now + 1.hours, certainty = Certainty.CERTAIN,
        payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), now + 1.hours, emptyList(), 1.0),
        fetchedAt = fetchedAt, expiresAt = null,
    )

    private val enckeElements = CometElements(
        epoch = Instant.parse("2023-10-22T03:35:18.402Z"),
        eccentricity = 0.8477496967533629,
        perihelionDistanceAu = 0.3379482792219925,
        inclinationDeg = 11.41227811179314,
        ascendingNodeDeg = 334.1935846036774,
        argPerihelionDeg = 187.1342463695676,
        tpPerihelion = Instant.parse("2023-10-22T03:35:18.402Z"),
    )

    private fun cometOcc(fetchedAt: Instant) = Occurrence(
        id = "cm:test", phenomenon = Phenomenon.COMET, sourceId = "jpl", title = "Comet",
        window = TimeWindow(now, now + 30.days), peakTime = now + 15.days, certainty = Certainty.FORECAST,
        payload = CometPayload(
            designation = "C/2025 K1", name = null, elements = enckeElements, magParams = CometMagParams(6.0, 10.0),
            perihelionDate = enckeElements.tpPerihelion, peakMag = 4.0, peakMagDate = now + 15.days, magAtIngest = 4.0,
        ),
        fetchedAt = fetchedAt, expiresAt = null,
    )

    private fun result(quality: Quality) = VisibilityResult(quality != Quality.NONE, quality, null, null, null, null, null)

    @Test
    fun aMatchingDataVersionIsServedFromCacheWithoutRecomputing() {
        val occ = eclipseOcc(fetchedAt = now)
        val ctx = VisibilityContext(now = now, ovationGrid = null)
        val version = computeDataVersion(occ, ctx, utc)
        val cachedResult = result(Quality.EXCELLENT)
        val snapshot = mapOf(VisibilityCacheKey(occ.id, loc.id) to VisibilityCacheEntry(version, cachedResult, now))

        val delegate = CountingModel(Phenomenon.SOLAR_ECLIPSE, result(Quality.NONE))
        val cache = VisibilityResultCache(snapshot, utc)
        val wrapped = cache.wrap(mapOf(Phenomenon.SOLAR_ECLIPSE to delegate)).getValue(Phenomenon.SOLAR_ECLIPSE)

        val evaluated = wrapped.evaluate(occ, loc, ctx)

        assertEquals(cachedResult, evaluated)
        assertEquals(0, delegate.evaluations, "a matching data_version must be served from cache, not recomputed")
        assertTrue(cache.dirty.isEmpty())
    }

    @Test
    fun aDataVersionMismatchForcesRecomputation() {
        val occ = eclipseOcc(fetchedAt = now)
        val ctx = VisibilityContext(now = now, ovationGrid = null)
        val staleResult = result(Quality.MARGINAL)
        // Cached under yesterday's fetchedAt -- a re-fetch changed the version.
        val staleVersion = computeDataVersion(eclipseOcc(fetchedAt = now - 1.days), ctx, utc)
        val snapshot = mapOf(VisibilityCacheKey(occ.id, loc.id) to VisibilityCacheEntry(staleVersion, staleResult, now))

        val freshResult = result(Quality.GOOD)
        val delegate = CountingModel(Phenomenon.SOLAR_ECLIPSE, freshResult)
        val cache = VisibilityResultCache(snapshot, utc)
        val wrapped = cache.wrap(mapOf(Phenomenon.SOLAR_ECLIPSE to delegate)).getValue(Phenomenon.SOLAR_ECLIPSE)

        val evaluated = wrapped.evaluate(occ, loc, ctx)

        assertEquals(freshResult, evaluated, "a data_version mismatch must force recomputation, not serve the stale entry")
        assertEquals(1, delegate.evaluations)
        assertEquals(1, cache.dirty.size, "the fresh result must be queued for persistence")
    }

    @Test
    fun aStaleDateCometEntryIsNotServed() {
        // §8.6's cache note: a comet cached "as of" one calendar date must
        // recompute once `now` has rolled into the next one, even though the
        // occurrence itself was never re-fetched.
        val occ = cometOcc(fetchedAt = now)
        val today = VisibilityContext(now = now, ovationGrid = null)
        val tomorrow = VisibilityContext(now = now + 1.days, ovationGrid = null)

        val staleResult = result(Quality.EXCELLENT)
        val staleVersion = computeDataVersion(occ, today, utc)
        val snapshot = mapOf(VisibilityCacheKey(occ.id, loc.id) to VisibilityCacheEntry(staleVersion, staleResult, now))

        val freshResult = result(Quality.NONE) // e.g. the comet has since dimmed below the magnitude gate
        val delegate = CountingModel(Phenomenon.COMET, freshResult)
        val cache = VisibilityResultCache(snapshot, utc)
        val wrapped = cache.wrap(mapOf(Phenomenon.COMET to delegate)).getValue(Phenomenon.COMET)

        val evaluated = wrapped.evaluate(occ, loc, tomorrow)

        assertEquals(freshResult, evaluated, "a stale-date comet cache entry must not be served (§8.6)")
        assertEquals(1, delegate.evaluations)
    }

    @Test
    fun aSameDateCometEntryIsServedFromCache() {
        val occ = cometOcc(fetchedAt = now)
        val ctxA = VisibilityContext(now = now, ovationGrid = null)
        val ctxB = VisibilityContext(now = now + 2.hours, ovationGrid = null) // same UTC calendar date

        val cachedResult = result(Quality.GOOD)
        val version = computeDataVersion(occ, ctxA, utc)
        val snapshot = mapOf(VisibilityCacheKey(occ.id, loc.id) to VisibilityCacheEntry(version, cachedResult, now))

        val delegate = CountingModel(Phenomenon.COMET, result(Quality.NONE))
        val cache = VisibilityResultCache(snapshot, utc)
        val wrapped = cache.wrap(mapOf(Phenomenon.COMET to delegate)).getValue(Phenomenon.COMET)

        val evaluated = wrapped.evaluate(occ, loc, ctxB)

        assertEquals(cachedResult, evaluated)
        assertEquals(0, delegate.evaluations, "still within the same local calendar date -- the cache entry is still valid")
    }
}
