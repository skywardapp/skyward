package dev.fritze.skyward.ui.eventdetail

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.MoonEventKind
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §13.3: the three states the detail screen has to tell apart. Before this,
 * "still reading the database" and "this occurrence is gone" were the same
 * null and rendered the same permanent "Loading…" (issue #53).
 */
class EventDetailViewModelTest {

    private val now = Instant.parse("2026-08-21T18:00:00Z")
    private val home = SavedLocation("home", "Home", GeoPoint(52.5, 13.4), isPrimary = true, createdAt = now, modifiedAt = now)
    private val ctx = VisibilityContext(now, null)
    private val models = mapOf(Phenomenon.MOON_EVENT to FakeVisibilityModel)

    @Test
    fun theInitialStateIsLoadingRatherThanMissing() {
        val initial = EventDetailUiState()

        assertTrue(initial.isLoading)
        assertNull(initial.occurrence)
    }

    @Test
    fun anOccurrenceThatLeftTheHorizonWindowIsNotLoading() {
        val state = eventDetailUiState("withdrawn", emptyList(), listOf(home), emptyList(), models, ctx)

        assertFalse(state.isLoading, "a withdrawn occurrence would render as a permanent spinner")
        assertNull(state.occurrence)
        assertTrue(state.perLocation.isEmpty())
    }

    @Test
    fun aPresentOccurrenceCarriesItsPerLocationVisibility() {
        val state = eventDetailUiState("supermoon", listOf(occurrence()), listOf(home), emptyList(), models, ctx)

        assertFalse(state.isLoading)
        assertEquals("supermoon", state.occurrence?.id)
        assertEquals(listOf("Home"), state.perLocation.map { it.first.name })
        assertTrue(state.hasSavedLocations)
    }

    /**
     * Distinguished from "not evaluated yet" so the screen can say which one
     * it is.
     */
    @Test
    fun noSavedLocationsIsItsOwnState() {
        val state = eventDetailUiState("supermoon", listOf(occurrence()), emptyList(), emptyList(), models, ctx)

        assertFalse(state.hasSavedLocations)
        assertTrue(state.perLocation.isEmpty())
    }

    @Test
    fun theHiddenMuteAndExtraReminderRulesAreReadBackOntoTheScreen() {
        val rules = listOf(
            hiddenRule(muteRuleId("supermoon"), leads = emptyList()),
            hiddenRule(extraReminderRuleId("supermoon"), leads = listOf(6.hours)),
        )

        val state = eventDetailUiState("supermoon", listOf(occurrence()), listOf(home), rules, models, ctx)

        assertTrue(state.isMuted)
        assertEquals(6.hours, state.extraReminderLead)
    }

    @Test
    fun aDisabledMuteRuleDoesNotReadAsMuted() {
        val rules = listOf(hiddenRule(muteRuleId("supermoon"), leads = emptyList()).copy(enabled = false))

        val state = eventDetailUiState("supermoon", listOf(occurrence()), listOf(home), rules, models, ctx)

        assertFalse(state.isMuted)
    }

    private fun occurrence() = Occurrence(
        id = "supermoon",
        phenomenon = Phenomenon.MOON_EVENT,
        sourceId = "astro",
        title = "Supermoon",
        window = TimeWindow(now + 20.days, now + 21.days),
        peakTime = now + 20.days + 6.hours,
        certainty = Certainty.CERTAIN,
        payload = MoonEventPayload(
            kind = MoonEventKind.SUPERMOON,
            fullMoonTime = now + 20.days + 6.hours,
            perigeeTime = now + 20.days + 2.hours,
            perigeeDistanceKm = 356_500.0,
        ),
        fetchedAt = now,
        expiresAt = null,
    )

    private fun hiddenRule(id: String, leads: List<kotlin.time.Duration>) = Rule(
        id = id,
        name = id,
        enabled = true,
        phenomena = setOf(Phenomenon.MOON_EVENT),
        locationIds = null,
        condition = Cond.OccurrenceIdIs("supermoon"),
        schedule = NotifySchedule(leads, Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
        hidden = true,
        createdAt = now,
        modifiedAt = now,
    )

    private object FakeVisibilityModel : VisibilityModel {
        override val phenomenon = Phenomenon.MOON_EVENT

        override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext) = VisibilityResult(
            visibleAtLocation = true,
            quality = Quality.GOOD,
            localDetails = LocalDetails.GenericLocal("Full moon near perigee"),
            nearestVisiblePoint = null,
            travelDistanceKm = null,
            travelBearingDeg = null,
            qualityAtNearestPoint = null,
        )
    }
}
