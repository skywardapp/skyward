package dev.fritze.skyward.core.sync

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.SavedLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class SyncMergeTest {

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun loc(id: String, modifiedAt: Instant) =
        SavedLocation(id = id, name = id, point = GeoPoint(0.0, 0.0), isPrimary = false, createdAt = t0, modifiedAt = modifiedAt)

    @Test
    fun newIncomingIdIsIncluded() {
        val result = SyncMerge.newerOrMissing(local = emptyList(), incoming = listOf(loc("a", t0)), id = { it.id }, modifiedAt = { it.modifiedAt })
        assertEquals(listOf(loc("a", t0)), result)
    }

    @Test
    fun incomingWinsOnlyWhenStrictlyNewer() {
        val local = listOf(loc("a", t0))

        val older = SyncMerge.newerOrMissing(local, listOf(loc("a", t0 - 1.days)), { it.id }, { it.modifiedAt })
        assertEquals(emptyList(), older, "older incoming must not overwrite local")

        val same = SyncMerge.newerOrMissing(local, listOf(loc("a", t0)), { it.id }, { it.modifiedAt })
        assertEquals(emptyList(), same, "equal modifiedAt keeps local, not a tie-break overwrite")

        val newer = SyncMerge.newerOrMissing(local, listOf(loc("a", t0 + 1.days)), { it.id }, { it.modifiedAt })
        assertEquals(listOf(loc("a", t0 + 1.days)), newer)
    }

    @Test
    fun localOnlyRecordsAreNeverTouched() {
        // §12.3: "Never delete local records absent from the file" -- newerOrMissing only ever
        // returns entries to *write*; a local-only record simply never appears in `incoming`,
        // so it's never a candidate for deletion by construction.
        val local = listOf(loc("a", t0), loc("local-only", t0))
        val result = SyncMerge.newerOrMissing(local, incoming = listOf(loc("a", t0 + 1.days)), id = { it.id }, modifiedAt = { it.modifiedAt })
        assertEquals(listOf(loc("a", t0 + 1.days)), result)
    }

    @Test
    fun duplicateIncomingIdsReduceToTheNewestBeforeComparingToLocal() {
        // A file (accidentally, or from a buggy exporter) containing two records sharing an id
        // must not let file order decide the winner -- only modifiedAt may.
        val incoming = listOf(loc("a", t0 + 1.days), loc("a", t0 + 2.days), loc("a", t0))
        val result = SyncMerge.newerOrMissing(local = emptyList(), incoming = incoming, id = { it.id }, modifiedAt = { it.modifiedAt })
        assertEquals(listOf(loc("a", t0 + 2.days)), result)
    }

    @Test
    fun duplicateIncomingIdsStillRespectAnEvenNewerLocalRecord() {
        val local = listOf(loc("a", t0 + 5.days))
        val incoming = listOf(loc("a", t0 + 1.days), loc("a", t0 + 2.days))
        val result = SyncMerge.newerOrMissing(local, incoming, id = { it.id }, modifiedAt = { it.modifiedAt })
        assertEquals(emptyList(), result, "even the newest duplicate is still older than local")
    }

    @Test
    fun syntheticFiredHistoryEntryDerivesOccurrenceIdAndIsFired() {
        val entry = SyncMerge.syntheticFiredHistoryEntry("se:20260812|1786556760|7200", t0)
        assertEquals("se:20260812", entry.occurrenceId)
        assertEquals(NotificationStatus.FIRED, entry.status)
        assertEquals("se:20260812|1786556760|7200", entry.id)
    }
}
