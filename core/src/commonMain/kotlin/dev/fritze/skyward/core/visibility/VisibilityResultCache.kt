package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** §11 `visibility_cache` primary key. */
data class VisibilityCacheKey(val occurrenceId: String, val locationId: String)

/** A decoded `visibility_cache` row. */
data class VisibilityCacheEntry(val dataVersion: String, val result: VisibilityResult, val computedAt: Instant)

/**
 * §9.2 step 1/§11: a read-through cache in front of a set of
 * [VisibilityModel]s. Construct with a [snapshot] loaded from
 * `VisibilityCacheRepo` before a Planner or UpcomingItems pass; after the
 * pass, persist whatever landed in [dirty] back through the same repo.
 *
 * Pure and in-memory -- no I/O of its own -- so wrapping models with [wrap]
 * doesn't cost `Planner`/`computeUpcomingItems` their purity (§4.2); the
 * surrounding coordinator or view-model does the actual DB reading and
 * writing, around the pure call.
 */
class VisibilityResultCache(
    private val snapshot: Map<VisibilityCacheKey, VisibilityCacheEntry>,
    private val zone: TimeZone,
) {
    private val mutableDirty = mutableMapOf<VisibilityCacheKey, VisibilityCacheEntry>()

    /**
     * Entries computed fresh (cache miss or version mismatch) during this
     * pass, ready to persist.
     */
    val dirty: Map<VisibilityCacheKey, VisibilityCacheEntry> get() = mutableDirty

    fun wrap(models: Map<Phenomenon, VisibilityModel>): Map<Phenomenon, VisibilityModel> =
        models.mapValues { (_, model) -> CachedModel(model) }

    private inner class CachedModel(private val delegate: VisibilityModel) : VisibilityModel {
        override val phenomenon get() = delegate.phenomenon

        override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
            val key = VisibilityCacheKey(occ.id, loc.id)
            val version = computeDataVersion(occ, ctx, zone)

            // Check mutableDirty first: computeUpcomingItems evaluates the
            // same (occurrence, location) pair twice in one pass (once via
            // Planner.computeMatches, once directly), and a same-pass repeat
            // must reuse what this pass already computed rather than calling
            // delegate.evaluate again.
            val cached = mutableDirty[key]?.takeIf { it.dataVersion == version }
                ?: snapshot[key]?.takeIf { it.dataVersion == version }
            if (cached != null) return cached.result

            val result = delegate.evaluate(occ, loc, ctx)
            mutableDirty[key] = VisibilityCacheEntry(version, result, ctx.now)
            return result
        }
    }
}
