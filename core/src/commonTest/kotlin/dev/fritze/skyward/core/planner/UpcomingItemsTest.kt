package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class UpcomingItemsTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.0, 0.0), isPrimary = true, createdAt = now, modifiedAt = now)

    private class FixedQualityModel(private val quality: Quality) : VisibilityModel {
        override val phenomenon = Phenomenon.SOLAR_ECLIPSE
        override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext) =
            VisibilityResult(quality != Quality.NONE, quality, null, null, null, null, null)
    }

    private fun occ(id: String, peakTime: Instant, certainty: Certainty = Certainty.CERTAIN, expiresAt: Instant? = null) = Occurrence(
        id = id, phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "t $id",
        window = TimeWindow(peakTime - 1.hours, peakTime + 1.hours), peakTime = peakTime, certainty = certainty,
        payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), peakTime, emptyList(), 1.0),
        fetchedAt = now, expiresAt = expiresAt,
    )

    private fun rule(minQuality: Quality) = Rule(
        id = "r", name = "My rule", enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE), locationIds = null,
        condition = Cond.VisibleAtLocation(minQuality), schedule = NotifySchedule(listOf(1.days), Anchor.PEAK, false, null),
        createdAt = now, modifiedAt = now,
    )

    @Test
    fun matchedScopeIncludesEveryRuleMatchWithItsRuleName() {
        val matching = occ("matches", now + 1.days)
        val nonMatching = occ("no-match", now + 2.days)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.GOOD))
        val rules = listOf(rule(Quality.MARGINAL))

        val items = computeUpcomingItems(listOf(matching, nonMatching), listOf(home), rules, models, ctx, UpcomingFilter(UpcomingScope.MATCHED))

        // Both occurrences evaluate to GOOD >= MARGINAL, so both match the rule.
        assertEquals(2, items.size)
        assertTrue(items.all { it.matchedRuleNames == listOf("My rule") })
    }

    @Test
    fun anExpiredForecastIsExcludedFromBothScopes() {
        // §5/§13.2: a stale forecast is last-known data. ALL drops the
        // "matches a rule" restriction, not the "still current" one -- and
        // EXCELLENT quality doesn't earn an expired row a "notable anyway"
        // slot either.
        val live = occ("live", now + 1.days, Certainty.FORECAST, expiresAt = now + 2.hours)
        val expired = occ("expired", now + 1.days, Certainty.FORECAST, expiresAt = now - 1.hours)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.EXCELLENT))
        val rules = listOf(rule(Quality.MARGINAL))

        for (scope in UpcomingScope.entries) {
            val items = computeUpcomingItems(listOf(live, expired), listOf(home), rules, models, ctx, UpcomingFilter(scope))
            assertEquals(listOf("live"), items.map { it.occurrence.id }, "scope $scope")
        }
    }

    @Test
    fun matchedScopeExcludesOccurrencesMatchingNoRuleAndNotNotable() {
        val occurrence = occ("neither", now + 1.days)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.MARGINAL))
        val rules = listOf(rule(Quality.GOOD)) // MARGINAL < GOOD, so this never matches

        val items = computeUpcomingItems(listOf(occurrence), listOf(home), rules, models, ctx, UpcomingFilter(UpcomingScope.MATCHED))

        assertTrue(items.isEmpty())
    }

    @Test
    fun notableAnywayIsIncludedInMatchedScopeEvenWithoutARuleMatch() {
        val occurrence = occ("notable", now + 1.days)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.EXCELLENT))

        val items = computeUpcomingItems(listOf(occurrence), listOf(home), emptyList(), models, ctx, UpcomingFilter(UpcomingScope.MATCHED))

        assertEquals(1, items.size, "EXCELLENT quality alone must surface the card even with zero rules")
        assertTrue(items.first().matchedRuleNames.isEmpty())
    }

    @Test
    fun allScopeIncludesEverythingRegardlessOfMatchOrNotability() {
        val occurrence = occ("marginal-unmatched", now + 1.days)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.MARGINAL))

        val matchedResult = computeUpcomingItems(listOf(occurrence), listOf(home), emptyList(), models, ctx, UpcomingFilter(UpcomingScope.MATCHED))
        val allResult = computeUpcomingItems(listOf(occurrence), listOf(home), emptyList(), models, ctx, UpcomingFilter(UpcomingScope.ALL))

        assertTrue(matchedResult.isEmpty())
        assertEquals(1, allResult.size)
    }

    @Test
    fun hiddenRulesDoNotCountAsAMatchForDisplayPurposes() {
        val occurrence = occ("muted", now + 1.days)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.MARGINAL))
        val hiddenRule = rule(Quality.MARGINAL).copy(hidden = true)

        val items = computeUpcomingItems(listOf(occurrence), listOf(home), listOf(hiddenRule), models, ctx, UpcomingFilter(UpcomingScope.MATCHED))

        assertTrue(items.isEmpty(), "a hidden rule match shouldn't surface a card as if the user asked for it")
    }

    @Test
    fun phenomenonChipFurtherNarrowsEitherScope() {
        val occurrence = occ("eclipse", now + 1.days)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.EXCELLENT))

        val matchingChip = computeUpcomingItems(listOf(occurrence), listOf(home), emptyList(), models, ctx, UpcomingFilter(UpcomingScope.ALL, setOf(Phenomenon.SOLAR_ECLIPSE)))
        val otherChip = computeUpcomingItems(listOf(occurrence), listOf(home), emptyList(), models, ctx, UpcomingFilter(UpcomingScope.ALL, setOf(Phenomenon.COMET)))

        assertEquals(1, matchingChip.size)
        assertTrue(otherChip.isEmpty())
    }

    @Test
    fun itemsAreSortedByPeakTime() {
        val later = occ("later", now + 5.days)
        val earlier = occ("earlier", now + 1.days)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.EXCELLENT))

        val items = computeUpcomingItems(listOf(later, earlier), listOf(home), emptyList(), models, ctx, UpcomingFilter(UpcomingScope.ALL))

        assertEquals(listOf("earlier", "later"), items.map { it.occurrence.id })
    }

    @Test
    fun emptyLocationsProducesNoItems() {
        val occurrence = occ("x", now + 1.days)
        val ctx = VisibilityContext(now, null)
        val models = mapOf(Phenomenon.SOLAR_ECLIPSE to FixedQualityModel(Quality.EXCELLENT))

        val items = computeUpcomingItems(listOf(occurrence), emptyList(), emptyList(), models, ctx, UpcomingFilter(UpcomingScope.ALL))

        assertTrue(items.isEmpty())
    }
}
