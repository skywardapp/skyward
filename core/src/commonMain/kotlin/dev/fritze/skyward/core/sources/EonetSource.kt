package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.net.createHttpClient
import dev.fritze.skyward.core.net.getText
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLQueryComponent
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §7.7: NASA EONET open events (volcanoes/severe storms/wildfires/etc, user
 * enabled per category). `id = "eonet"`, POLLED. Positions/dates are
 * EONET's own approximations (its disclaimer, quoted in §7.7) -- hence
 * `certainty = FORECAST` and no `peakTime`, even though these are real
 * observed events rather than a model prediction: the *rating* (FORECAST
 * vs CERTAIN) tracks "how precisely can the app promise this timing," not
 * "is this real."
 *
 * The bbox payload-size optimization for >=2 nearby saved locations
 * (§7.7's third bullet) is not implemented -- it is a bandwidth nicety, not
 * a correctness requirement, and `status=open&days=30` plus the category
 * filter already keeps responses small.
 */
class EonetSource(private val httpClient: HttpClient = createHttpClient()) : EventSource {
    override val id = "eonet"
    override val phenomena = setOf(Phenomenon.TERRESTRIAL)
    override val kind = SourceKind.POLLED

    override fun schedule(settings: SourceSettings): Schedule = Schedule.Periodic(6.hours)

    override suspend fun refresh(req: RefreshRequest): RefreshResult {
        val categories = req.settings.params["categories"]?.takeUnless { it.isBlank() } ?: DEFAULT_CATEGORIES
        val events = parseEonetEvents(httpClient.getText(eventsUrl(categories)))
        val occurrences = events.map { buildOccurrence(it, req.now) }

        return RefreshResult(
            occurrences = occurrences,
            newState = emptyMap(),
            nextRefreshHint = null,
            diagnostics = SourceDiagnostics(ok = true, itemCount = occurrences.size, lastSuccessAt = req.now),
        )
    }

    private fun buildOccurrence(event: EonetEvent, now: Instant): Occurrence {
        val windowEnd = maxOf(event.closed ?: (now + 7.days), event.firstGeometryDate)
        return Occurrence(
            id = "eonet:${event.eonetId}",
            phenomenon = Phenomenon.TERRESTRIAL,
            sourceId = id,
            title = event.categoryTitle,
            window = TimeWindow(event.firstGeometryDate, windowEnd),
            peakTime = null, // §7.7: no ±minute precision claims -- there is no meaningful "peak" for these
            certainty = Certainty.FORECAST,
            payload = TerrestrialPayload(
                eonetId = event.eonetId,
                categoryId = event.categoryId,
                categoryTitle = event.categoryTitle,
                latestGeometry = event.latestGeometry,
                geometryDate = event.latestGeometryDate,
                magnitudeValue = event.magnitudeValue,
                magnitudeUnit = event.magnitudeUnit,
                link = event.link,
                closed = event.closed != null,
            ),
            fetchedAt = now,
            expiresAt = now + 3.days,
        )
    }

    private fun eventsUrl(categories: String): String =
        "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=30&category=${categories.encodeURLQueryComponent()}"

    private companion object {
        const val DEFAULT_CATEGORIES = "volcanoes,severeStorms,wildfires"
    }
}
