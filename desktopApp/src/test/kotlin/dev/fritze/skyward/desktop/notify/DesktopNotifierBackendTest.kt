package dev.fritze.skyward.desktop.notify

import com.sshtools.twoslices.ToasterFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * §17.5's desktop half: "DBus notification call succeeds on a CI container
 * with a mock notification daemon (or skip-if-no-dbus guard)".
 *
 * [FallbackChainNotifierTest] covers the chain's *logic* with fake backends;
 * this covers the two real ones, which nothing exercised. The distinction
 * matters because both of their failure modes live outside the chain: a
 * `notify-send` that isn't on PATH, hangs, or rejects our argument vector,
 * and a two-slices that reports success from a console backend nobody is
 * looking at.
 *
 * The end-to-end leg needs a session bus with something answering
 * `org.freedesktop.Notifications`; `tools/ci/mock-notification-daemon.py`
 * provides one, and CI runs the desktop tests under it. Where it is absent —
 * any developer machine without a desktop session — that one test skips, per
 * §17.5's own "or skip-if-no-dbus guard", while everything else here still
 * runs: those cases drive `notify-send` through a stub binary, so they assert
 * the same code paths deterministically and without a daemon.
 */
class DesktopNotifierBackendTest {

    private val reminder = DesktopNotification(
        title = "Supermoon tonight",
        body = "Rises at 20:41 at Home.",
        occurrenceId = "moon:2026-08",
    )

    /** Kept in step with `TwoSlicesNotifier.CONSOLE_ONLY_TOASTERS`, which is private to it. */
    private val consoleOnlyToasters = setOf("com.sshtools.twoslices.impl.SysOutToaster")

    /**
     * A stand-in `notify-send` that records its argument vector and exits
     * with [exitCode]. `sleepSeconds` covers the "daemon never answers" case
     * the real notifier has a timeout for.
     */
    private fun stubNotifySend(exitCode: Int = 0, sleepSeconds: Int = 0): Pair<String, File> {
        val directory = Files.createTempDirectory("skyward-notify-send").toFile().apply { deleteOnExit() }
        val log = File(directory, "argv.txt")
        val script = File(directory, "notify-send")
        script.writeText(
            """
            #!/bin/sh
            printf '%s\n' "${'$'}@" >> "${log.absolutePath}"
            ${if (sleepSeconds > 0) "sleep $sleepSeconds" else ""}
            exit $exitCode
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.absolutePath to log
    }

    @Test
    fun notifySendReceivesTheReminderTextAndTheOptionTerminator() {
        val (command, log) = stubNotifySend()

        assertTrue(NotifySendNotifier(command).post(reminder, onActivated = {}))

        val argv = log.readLines()
        assertContains(argv, "--app-name=Skyward")
        assertContains(argv, reminder.title)
        assertContains(argv, reminder.body)
        // Without "--" a body starting with "-" is parsed as an option and
        // the reminder is silently mangled or rejected; it has to come
        // immediately before the two positional arguments.
        val terminator = argv.indexOf("--")
        assertTrue(terminator >= 0, "expected an option terminator in $argv")
        assertEquals(listOf(reminder.title, reminder.body), argv.drop(terminator + 1))
    }

    @Test
    fun aNonZeroExitIsAFailedDeliveryRatherThanASilentDrop() {
        val (command, _) = stubNotifySend(exitCode = 1)

        // Returning true here is the dangerous answer: the chain would
        // remember this backend and the scheduler would record the reminder
        // FIRED without anything reaching the user.
        assertFalse(NotifySendNotifier(command).post(reminder, onActivated = {}))
    }

    @Test
    fun aMissingBinaryIsAFailedDeliveryRatherThanAnEscapingException() {
        // The Flatpak-sandbox case the fallback exists for: nothing on PATH.
        assertFalse(NotifySendNotifier("skyward-no-such-notify-send").post(reminder, onActivated = {}))
    }

    @Test
    fun aNotifySendThatNeverAnswersIsGivenUpOnInsteadOfWedgingTheScheduler() {
        // §10.3's scheduler posts from its own loop, so a hung notification
        // daemon would stop every later reminder too, not just this one.
        val (command, _) = stubNotifySend(sleepSeconds = 60)

        val started = TimeSource.Monotonic.markNow()
        assertFalse(NotifySendNotifier(command).post(reminder, onActivated = {}))
        val elapsed = started.elapsedNow()

        // The notifier's own timeout is 5s; the bound is generous because
        // this is asserting "it gave up" rather than "it gave up at exactly
        // five seconds", and process teardown on a loaded runner is not free.
        assertTrue(elapsed < 30.seconds, "expected the hung notifier to be abandoned, took $elapsed")
    }

    @Test
    fun twoSlicesNeverReportsSuccessFromAConsoleOnlyBackend() {
        // two-slices settles on SysOutToaster when it can find nothing else:
        // it prints "[!] title - body" to stdout and calls that a success.
        // FallbackChainNotifier would then remember it as the working backend
        // and stop trying notify-send, and the scheduler would record every
        // reminder FIRED while the user saw nothing.
        //
        // A headless CI runner is exactly where that happens, so this is the
        // case the assertion is for. Where a real backend is present the test
        // stops rather than posting: a `./gradlew check` on a developer's
        // machine has no business popping a desktop notification, and the
        // delivery path itself is covered above and by the daemon leg below.
        val chosen = ToasterFactory.getFactory().toaster()::class.java.name
        if (chosen !in consoleOnlyToasters) return

        assertFalse(
            TwoSlicesNotifier().post(reminder, onActivated = {}),
            "a reminder printed to stdout by $chosen is a lost reminder, not a delivered one",
        )
    }

    @Test
    fun theRealNotifySendDeliversToARunningNotificationDaemon() {
        // The one test that needs a session bus. `SKYWARD_MOCK_NOTIFICATION_LOG`
        // is set by the CI step that starts tools/ci/mock-notification-daemon.py
        // inside `dbus-run-session`; on a developer machine without one there
        // is nothing on the bus to deliver to and this is a skip, not a
        // failure (§17.5's own "or skip-if-no-dbus guard").
        //
        // On CI it is a failure, because CI is where the harness is set up and
        // a skip-guard nobody can see fire is how §17.5's requirement quietly
        // stops being met: the job would stay green whether the harness
        // worked, the daemon never claimed the bus name, or the environment
        // never reached the test JVM at all. `ci.yml` is the only workflow
        // that runs this module's tests — `android-ui-tests.yml` runs
        // connectedAndroidTest and nothing else — so there is no job where
        // CI is set and the harness legitimately absent.
        val daemonLog = System.getenv("SKYWARD_MOCK_NOTIFICATION_LOG")?.let(::File)
        val notifySendPresent = onPath("notify-send")
        if (daemonLog == null || !notifySendPresent) {
            assertFalse(
                System.getenv("CI") == "true",
                "on CI this must not skip: SKYWARD_MOCK_NOTIFICATION_LOG=${System.getenv("SKYWARD_MOCK_NOTIFICATION_LOG")}, " +
                    "notify-send on PATH=$notifySendPresent — see the notification-harness step in ci.yml",
            )
            return
        }

        val before = if (daemonLog.exists()) daemonLog.readLines().size else 0
        assertTrue(
            NotifySendNotifier().post(reminder, onActivated = {}),
            "notify-send failed against the mock notification daemon",
        )

        // Asserting on the daemon's own record, not just the exit code: this
        // is what makes it an end-to-end check rather than a claim that a
        // process exited 0.
        val delivered = daemonLog.readLines().drop(before)
        assertTrue(delivered.isNotEmpty(), "the daemon recorded no notification")
        val last = delivered.last()
        assertTrue(last.contains(reminder.title), "daemon saw: $last")
        assertTrue(last.contains(reminder.body), "daemon saw: $last")
    }

    private fun onPath(binary: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { directory ->
            File(directory, binary).canExecute()
        }
}
