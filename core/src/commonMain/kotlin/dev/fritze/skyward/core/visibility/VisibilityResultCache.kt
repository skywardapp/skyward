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
 * The full `visibility_cache.data_version` for one (occurrence, location,
 * context) evaluation. [computeDataVersion] alone only covers §8.6's
 * per-occurrence staleness; every [VisibilityModel] also takes [loc], so a
 * saved location's coordinates changing under the same id must bust the
 * cache too, or an edited location keeps serving verdicts computed for its
 * old coordinates until the occurrence itself happens to be re-fetched.
 */
internal fun cacheVersion(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext, zone: TimeZone): String =
    "${computeDataVersion(occ, ctx, zone)}|${loc.modifiedAt}"

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
    private val persistedKeys = mutableSetOf<VisibilityCacheKey>()

    /**
     * Entries computed fresh (cache miss or version mismatch) during this
     * pass and not yet persisted -- [markPersisted] shrinks this without
     * dropping the entry from the in-memory cache itself, so a repeat lookup
     * still hits [mutableDirty] rather than recomputing.
     */
    val dirty: Map<VisibilityCacheKey, VisibilityCacheEntry>
        get() = mutableDirty.filterKeys { it !in persistedKeys }

    /**
     * Marks [keys] as written to `VisibilityCacheRepo`, so [dirty] stops
     * offering them for persistence again. A long-lived cache (the Android
     * Upcoming ticker reuses one instance across many ticks) would otherwise
     * keep re-persisting the same rows on every subsequent tick. The entries
     * stay in the in-memory cache and keep serving repeats within this
     * pass -- leave a key unmarked if persisting it failed, so it's retried
     * rather than lost.
     */
    fun markPersisted(keys: Collection<VisibilityCacheKey>) {
        persistedKeys += keys
    }

    fun wrap(models: Map<Phenomenon, VisibilityModel>): Map<Phenomenon, VisibilityModel> =
        models.mapValues { (_, model) -> CachedModel(model) }

    private inner class CachedModel(private val delegate: VisibilityModel) : VisibilityModel {
        override val phenomenon get() = delegate.phenomenon

        override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
            val key = VisibilityCacheKey(occ.id, loc.id)
            val version = cacheVersion(occ, loc, ctx, zone)

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
