package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §9.2 step 1/§11: [computeDataVersion] and the [VisibilityResultCache]
 * read-through decorator that sits in front of `VisibilityModel`s inside
 * `Planner`/`computeUpcomingItems` (issue #18 -- the `visibility_cache`
 * table existed but nothing read or wrote it). Covers the two regression
 * cases the issue names: a stale-date comet entry must not be served, and
 * any `data_version` mismatch must force recomputation.
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

    private fun auroraOcc(kind: AuroraForecastKind, fetchedAt: Instant) = Occurrence(
        id = "au:1", phenomenon = Phenomenon.AURORA, sourceId = "swpc", title = "Aurora",
        window = TimeWindow(now, now + 3.hours), peakTime = null, certainty = Certainty.FORECAST,
        payload = AuroraPayload(kpForecast = 6.0, forecastKind = kind, issuedAt = fetchedAt),
        fetchedAt = fetchedAt, expiresAt = null,
    )

    private fun grid(observationTime: Instant) =
        OvationGrid(observationTime, observationTime + 30.minutes, ByteArray(360 * 181))

    private fun result(quality: Quality) = VisibilityResult(quality != Quality.NONE, quality, null, null, null, null, null)

    /** A cache over [snapshot], and the single [phenomenon] model it wraps [delegate] as. */
    private fun cacheWith(
        snapshot: Map<VisibilityCacheKey, VisibilityCacheEntry>,
        phenomenon: Phenomenon,
        delegate: VisibilityModel,
    ): Pair<VisibilityResultCache, VisibilityModel> {
        val cache = VisibilityResultCache(snapshot, utc)
        return cache to cache.wrap(mapOf(phenomenon to delegate)).getValue(phenomenon)
    }

    // -- computeDataVersion --------------------------------------------------

    @Test
    fun dateIndependentModelsVersionOnFetchedAtAlone() {
        val ctx = VisibilityContext(now = now, ovationGrid = null)
        val v1 = computeDataVersion(eclipseOcc(fetchedAt = now), ctx, utc)
        val v2 = computeDataVersion(eclipseOcc(fetchedAt = now), ctx, utc)
        val v3 = computeDataVersion(eclipseOcc(fetchedAt = now + 1.hours), ctx, utc)

        assertEquals(v1, v2, "same fetchedAt, same version")
        assertNotEquals(v1, v3, "a re-fetch (new fetchedAt) must change the version")
    }

    @Test
    fun cometVersionChangesAcrossLocalCalendarDates() {
        val occ = cometOcc(fetchedAt = now)
        val sameDay = VisibilityContext(now = now + 2.hours, ovationGrid = null)
        val nextDay = VisibilityContext(now = now + 1.days, ovationGrid = null)

        val v1 = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = null), utc)
        val v2 = computeDataVersion(occ, sameDay, utc)
        val v3 = computeDataVersion(occ, nextDay, utc)

        assertEquals(v1, v2, "same fetchedAt and calendar date -- unchanged")
        assertNotEquals(v1, v3, "§8.6: the night containing `now` moved to a new calendar date")
    }

    @Test
    fun threeDayAuroraIsDateIndependent() {
        // Non-null, distinct grids on both sides: a version that (wrongly)
        // picked up ctx.ovationGrid for THREE_DAY too would fail this.
        val occ = auroraOcc(AuroraForecastKind.THREE_DAY, fetchedAt = now)
        val v1 = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = grid(now)), utc)
        val v2 = computeDataVersion(occ, VisibilityContext(now = now + 1.days, ovationGrid = grid(now + 30.minutes)), utc)

        assertEquals(v1, v2, "THREE_DAY aurora never reads ctx.ovationGrid, so it doesn't need a date component")
    }

    @Test
    fun nowcastAuroraVersionTracksTheOvationGridObservationTime() {
        val occ = auroraOcc(AuroraForecastKind.NOWCAST, fetchedAt = now)
        val gridA = grid(now)
        val gridB = grid(now + 30.minutes)

        val vNoGrid = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = null), utc)
        val vGridA = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = gridA), utc)
        val vGridB = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = gridB), utc)

        assertNotEquals(vNoGrid, vGridA, "a grid becoming available must change the version")
        assertNotEquals(vGridA, vGridB, "a newer grid (independent of the occurrence's own fetchedAt) must change the version")
    }

    // -- VisibilityResultCache ------------------------------------------------

    @Test
    fun aMatchingDataVersionIsServedFromCacheWithoutRecomputing() {
        val occ = eclipseOcc(fetchedAt = now)
        val ctx = VisibilityContext(now = now, ovationGrid = null)
        val version = cacheVersion(occ, loc, ctx, utc)
        val cachedResult = result(Quality.EXCELLENT)
        val snapshot = mapOf(VisibilityCacheKey(occ.id, loc.id) to VisibilityCacheEntry(version, cachedResult, now))

        val delegate = CountingModel(Phenomenon.SOLAR_ECLIPSE, result(Quality.NONE))
        val (cache, wrapped) = cacheWith(snapshot, Phenomenon.SOLAR_ECLIPSE, delegate)

        val evaluated = wrapped.evaluate(occ, loc, ctx)

        assertEquals(cachedResult, evaluated)
        assertEquals(0, delegate.evaluations, "a matching data_version must be served from cache, not recomputed")
        assertTrue(cache.dirty.isEmpty())
    }

    @Test
    fun editingALocationsCoordinatesInvalidatesItsCacheEntries() {
        // Every VisibilityModel takes `loc`, so a saved location's
        // coordinates changing under the same id must bust the cache too --
        // computeDataVersion alone only tracks the occurrence side of §8.6.
        val occ = eclipseOcc(fetchedAt = now)
        val ctx = VisibilityContext(now = now, ovationGrid = null)
        val editedLoc = loc.copy(point = GeoPoint(49.0, 12.0), modifiedAt = now + 1.hours)

        val staleResult = result(Quality.EXCELLENT)
        val staleVersion = cacheVersion(occ, loc, ctx, utc)
        val snapshot = mapOf(VisibilityCacheKey(occ.id, loc.id) to VisibilityCacheEntry(staleVersion, staleResult, now))

        val freshResult = result(Quality.MARGINAL)
        val delegate = CountingModel(Phenomenon.SOLAR_ECLIPSE, freshResult)
        val (_, wrapped) = cacheWith(snapshot, Phenomenon.SOLAR_ECLIPSE, delegate)

        val evaluated = wrapped.evaluate(occ, editedLoc, ctx)

        assertEquals(freshResult, evaluated, "an edited location's coordinates must bust the cache entry for its id")
        assertEquals(1, delegate.evaluations)
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
        val (cache, wrapped) = cacheWith(snapshot, Phenomenon.SOLAR_ECLIPSE, delegate)

        val evaluated = wrapped.evaluate(occ, loc, ctx)

        assertEquals(freshResult, evaluated, "a data_version mismatch must force recomputation, not serve the stale entry")
        assertEquals(1, delegate.evaluations)
        assertEquals(1, cache.dirty.size, "the fresh result must be queued for persistence")

        wrapped.evaluate(occ, loc, ctx)
        assertEquals(1, delegate.evaluations, "a same-pass repeat must reuse the freshly computed entry, not recompute")
    }

    @Test
    fun markPersistedStopsOfferingAnEntryWithoutDroppingItFromThePass() {
        // The Android Upcoming ticker reuses one VisibilityResultCache across
        // many ticks (UpcomingViewModel.kt): markPersisted must shrink `dirty`
        // so a tick with nothing new doesn't keep re-persisting the same rows,
        // but a repeat evaluate() must still be served from the in-memory
        // cache rather than recomputed.
        val occ = eclipseOcc(fetchedAt = now)
        val ctx = VisibilityContext(now = now, ovationGrid = null)
        val delegate = CountingModel(Phenomenon.SOLAR_ECLIPSE, result(Quality.GOOD))
        val (cache, wrapped) = cacheWith(emptyMap(), Phenomenon.SOLAR_ECLIPSE, delegate)

        wrapped.evaluate(occ, loc, ctx)
        assertEquals(1, cache.dirty.size, "the fresh result must be queued for persistence")

        cache.markPersisted(cache.dirty.keys)
        assertTrue(cache.dirty.isEmpty(), "a persisted entry must not be offered for persistence again")

        wrapped.evaluate(occ, loc, ctx)
        assertEquals(1, delegate.evaluations, "marking an entry persisted must not evict it from the pass's cache")
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

        // e.g. the comet has since dimmed below the magnitude gate
        val freshResult = result(Quality.NONE)
        val delegate = CountingModel(Phenomenon.COMET, freshResult)
        val (_, wrapped) = cacheWith(snapshot, Phenomenon.COMET, delegate)

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
        val version = cacheVersion(occ, loc, ctxA, utc)
        val snapshot = mapOf(VisibilityCacheKey(occ.id, loc.id) to VisibilityCacheEntry(version, cachedResult, now))

        val delegate = CountingModel(Phenomenon.COMET, result(Quality.NONE))
        val (_, wrapped) = cacheWith(snapshot, Phenomenon.COMET, delegate)

        val evaluated = wrapped.evaluate(occ, loc, ctxB)

        assertEquals(cachedResult, evaluated)
        assertEquals(0, delegate.evaluations, "still within the same local calendar date -- the cache entry is still valid")
    }
}
