package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * §11/§8.6: the `visibility_cache.data_version` string for one (occurrence,
 * [ctx]) pair. Always keyed on `occurrence.fetchedAt` -- bumped whenever the
 * source re-fetches this occurrence (§6.3) -- plus, for models whose result
 * depends on more than the occurrence row, the piece of state that can move
 * independently of a re-fetch:
 *
 * - Comet (§8.6's cache note): evaluated over "the night containing `now`",
 *   so a version that never changed would serve the same verdict for the
 *   whole 30-day refresh cycle. Keyed on [zone]'s local calendar date.
 * - Aurora NOWCAST: reads `ctx.ovationGrid`, which is refreshed
 *   independently of the occurrence itself (§7.3.1). Keyed on the grid's
 *   `observationTime`. THREE_DAY aurora occurrences never read the grid, so
 *   they fall through to the fetchedAt-only default.
 *
 * Date-independent models (eclipses, showers, supermoon, conjunction,
 * EONET) omit the extra component, per §11's table comment.
 */
fun computeDataVersion(occ: Occurrence, ctx: VisibilityContext, zone: TimeZone): String {
    val extra = when (occ.phenomenon) {
        Phenomenon.COMET -> ctx.now.toLocalDateTime(zone).date.toString()
        Phenomenon.AURORA -> ctx.nowcastGridVersion(occ)
        else -> null
    } ?: return occ.fetchedAt.toString()

    return "${occ.fetchedAt}|$extra"
}

private fun VisibilityContext.nowcastGridVersion(occ: Occurrence): String? {
    val payload = occ.payload as AuroraPayload
    if (payload.forecastKind != AuroraForecastKind.NOWCAST) return null
    return ovationGrid?.observationTime?.toString() ?: "no-grid"
}
