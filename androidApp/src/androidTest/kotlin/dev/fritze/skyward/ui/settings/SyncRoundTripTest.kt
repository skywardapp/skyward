package dev.fritze.skyward.ui.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.alarm.AndroidAlarmScheduler
import dev.fritze.skyward.alarm.FakeAlarmScheduler
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
// Aliased: the domain type and JUnit's @Rule annotation are both `Rule`, and
// this class needs both.
import dev.fritze.skyward.core.rules.Rule as SkywardRule
import dev.fritze.skyward.core.sync.SyncCodec
import dev.fritze.skyward.ui.awaitText
import dev.fritze.skyward.ui.awaitTextGone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * §17.5's "SAF export/import round-trip", which §18/M5 accepts on ("two-device
 * (or wiped-emulator) sync round-trip keeps history deduped") and which was
 * explicitly deferred out of M3 and then never written (#55).
 *
 * `SyncCodec`, `SyncMerge` and `SyncImportRepo` are well covered by `:core`'s
 * own tests. What had no coverage at all is everything between them and the
 * user: [SyncScreen]'s three launchers, `SyncUiController`'s `ContentResolver`
 * reads and writes, and [SyncViewModel]. All of that runs here for real --
 * only the system document picker is replaced, by
 * [RecordingActivityResultRegistry] through `LocalActivityResultRegistryOwner`
 * (ADR 0018).
 *
 * Every assertion goes through [awaitText] rather than `waitForIdle`:
 * `SyncUiController` deliberately runs on `container.applicationScope`
 * (`Dispatchers.Default`) so that navigating away cannot cancel a destructive
 * import mid-flight, and Compose's idling resource knows nothing about that.
 */
@RunWith(AndroidJUnit4::class)
class SyncRoundTripTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<SkywardApplication>()
    private val container = context.container
    private val registry = RecordingActivityResultRegistry()

    private val documents = mutableListOf<Uri>()
    private val seededLocationIds = mutableListOf<String>()
    private val seededRuleIds = mutableListOf<String>()

    /**
     * `applyImport` ends in `container.replanAndSync()` (§12.3's "after
     * import: full re-plan"), which with the real scheduler would register OS
     * alarms and WorkManager jobs for everything the import produced -- and
     * those outlive this class. Pinning a fake keeps a sync test from posting
     * notifications into whichever suite runs next.
     */
    @Before
    fun pinTheScheduler() {
        container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = true)
    }

    @After
    fun restoreSharedState() {
        container.alarmScheduler = AndroidAlarmScheduler(context)
        runBlocking {
            for (id in seededRuleIds) container.ruleRepo.delete(id)
            for (id in seededLocationIds) container.locationRepo.delete(id)
        }
        seededRuleIds.clear()
        seededLocationIds.clear()
        for (uri in documents) context.deleteDocumentUri(uri)
        documents.clear()
    }

    @Test
    fun exportWritesAFileTheAppCanReadBack() {
        val location = seedLocation("Tromsø")
        val rule = seedRule("Aurora over Tromsø")
        showSyncScreen()

        val uri = newDocument()
        registry.nextResult = uri
        composeRule.onNodeWithText("Export to file").performClick()
        composeRule.awaitText(EXPORT_DONE)

        // §12.2 names the file `skyward-settings-<yyyyMMdd-HHmm>.json`, and the
        // launcher's input *is* that name -- the one place it can be asserted.
        val suggested = registry.lastInput as String
        assertTrue("export filename must match §12.2, was $suggested", suggested.matches(EXPORT_FILENAME))

        val parsed = SyncCodec.parseForImport(context.readDocument(uri))
        assertTrue("the export must carry the saved location", parsed.locations.any { it.id == location.id })
        assertTrue("the export must carry the saved rule", parsed.rules.any { it.id == rule.id })
    }

    /**
     * M5's acceptance in miniature: export, wipe, import, and the device is
     * back where it started -- through the real file, the real codec and the
     * real merge.
     */
    @Test
    fun importRestoresWhatExportWrote() {
        val location = seedLocation("Cabin")
        val rule = seedRule("Total eclipses within 500 km")
        showSyncScreen()
        val uri = exportTo(newDocument())

        runBlocking {
            container.locationRepo.delete(location.id)
            container.ruleRepo.delete(rule.id)
        }
        assertNull("the location must really be gone before importing", runBlocking { container.locationRepo.getById(location.id) })

        registry.nextResult = uri
        composeRule.onNodeWithText("Import from file").performClick()
        composeRule.awaitText(IMPORT_DONE)

        assertNotNull("import must restore the exported location", runBlocking { container.locationRepo.getById(location.id) })
        assertNotNull("import must restore the exported rule", runBlocking { container.ruleRepo.getById(rule.id) })
    }

    /**
     * §12.3: "Never delete local records absent from the file (a file is a
     * snapshot, not a mirror)." The plain import path must leave a location
     * created after the export alone.
     */
    @Test
    fun importKeepsLocalRecordsTheFileDoesNotCarry() {
        seedLocation("Home")
        showSyncScreen()
        val uri = exportTo(newDocument())
        val laterLocation = seedLocation("Added after the export")

        registry.nextResult = uri
        composeRule.onNodeWithText("Import from file").performClick()
        composeRule.awaitText(IMPORT_DONE)

        assertNotNull(
            "a merge must not delete what the file simply does not mention (§12.3)",
            runBlocking { container.locationRepo.getById(laterLocation.id) },
        )
    }

    /**
     * §12.3's explicit destructive secondary action, including the
     * confirmation it is required to sit behind.
     */
    @Test
    fun replaceEverythingWipesLocalRowsTheFileDoesNotCarry() {
        seedLocation("Home")
        showSyncScreen()
        val uri = exportTo(newDocument())
        val laterLocation = seedLocation("Added after the export")

        registry.nextResult = uri
        composeRule.onNodeWithText(REPLACE_ENTRY).performClick()
        composeRule.awaitText(REPLACE_CONFIRM_TITLE)
        composeRule.onNodeWithText("Pick file and replace").performClick()
        composeRule.awaitText(IMPORT_DONE)

        assertNull(
            "\"Replace everything\" must delete what the file does not carry",
            runBlocking { container.locationRepo.getById(laterLocation.id) },
        )
    }

    @Test
    fun cancellingTheReplaceDialogImportsNothing() {
        showSyncScreen()
        val launchesBefore = registry.launchCount

        composeRule.onNodeWithText(REPLACE_ENTRY).performClick()
        composeRule.awaitText(REPLACE_CONFIRM_TITLE)
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.awaitTextGone(REPLACE_CONFIRM_TITLE)

        assertEquals("cancelling must not open the picker at all", launchesBefore, registry.launchCount)
    }

    /**
     * §12.3: "Unknown `formatVersion` → refuse with message." The same
     * refusal path catches a file that was never a Skyward export -- which is
     * a real possibility, because the picker deliberately accepts any MIME
     * type so that a `.json` mis-typed by a mail client is still selectable.
     */
    @Test
    fun aFileThatIsNotASyncFileIsRejectedByName() {
        val location = seedLocation("Home")
        showSyncScreen()
        val uri = newDocument()
        context.writeDocument(uri, """{"hello":1}""")

        registry.nextResult = uri
        composeRule.onNodeWithText("Import from file").performClick()
        composeRule.awaitText(NOT_A_SYNC_FILE)

        assertNotNull("a rejected import must not touch local data", runBlocking { container.locationRepo.getById(location.id) })
    }

    /** Backing out of the picker returns a null Uri, which must be a no-op rather than an error. */
    @Test
    fun backingOutOfThePickerLeavesTheScreenAlone() {
        showSyncScreen()

        registry.nextResult = null
        composeRule.onNodeWithText("Import from file").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(IMPORT_DONE).assertDoesNotExist()
        composeRule.onNodeWithText(COULD_NOT_READ).assertDoesNotExist()
    }

    private fun showSyncScreen() {
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                SyncScreen(container = container, onBack = {})
            }
        }
        composeRule.awaitText("Export to file")
    }

    private fun exportTo(uri: Uri): Uri {
        registry.nextResult = uri
        composeRule.onNodeWithText("Export to file").performClick()
        composeRule.awaitText(EXPORT_DONE)
        return uri
    }

    private fun newDocument(): Uri =
        context.createDocumentUri("skyward-sync-test-${UUID.randomUUID()}.json").also { documents += it }

    private fun seedLocation(name: String): SavedLocation {
        val now = Clock.System.now()
        val location = SavedLocation(
            id = "loc-${UUID.randomUUID()}",
            name = name,
            point = GeoPoint(69.65, 18.96),
            isPrimary = false,
            createdAt = now,
            modifiedAt = now,
        )
        runBlocking { container.locationRepo.upsert(location) }
        seededLocationIds += location.id
        return location
    }

    private fun seedRule(name: String): SkywardRule {
        val now = Clock.System.now()
        val rule = SkywardRule(
            id = "rule-${UUID.randomUUID()}",
            name = name,
            enabled = true,
            phenomena = setOf(Phenomenon.AURORA),
            locationIds = null,
            condition = Cond.KpAtLeast(5.0),
            schedule = NotifySchedule(
                leads = listOf(1.days, 2.hours),
                anchor = Anchor.PEAK,
                notifyOnFirstSeen = false,
                quietHours = null,
            ),
            createdAt = now,
            modifiedAt = now,
        )
        runBlocking { container.ruleRepo.upsert(rule) }
        seededRuleIds += rule.id
        return rule
    }

    private companion object {
        const val EXPORT_DONE = "Exported your locations, rules, and settings."
        const val IMPORT_DONE = "Import complete"
        const val NOT_A_SYNC_FILE = "That file isn't a Skyward sync file."
        const val COULD_NOT_READ = "Couldn't read that file."
        const val REPLACE_ENTRY = "Replace everything instead…"
        const val REPLACE_CONFIRM_TITLE = "Replace everything?"

        val EXPORT_FILENAME = Regex("""skyward-settings-\d{8}-\d{4}\.json""")
    }
}
