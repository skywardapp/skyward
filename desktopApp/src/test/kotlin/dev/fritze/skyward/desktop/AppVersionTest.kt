package dev.fritze.skyward.desktop

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §15.4's version derivation, at the point where it stops being a build detail:
 * [APP_VERSION] is generated from the git tag by `:desktopApp:generateAppVersion`
 * and then travels a long way — the About section, §12 export headers, and (via
 * `--version`, ADR 0020) the AppStream `<release version=…>` and the AppImage's
 * `X-AppImage-Version`. The last two are XML attribute values and a desktop-entry
 * value respectively, so what the generator emits has to be safe to paste into
 * both, and `--version`'s output shape is a contract the packaging scripts parse.
 */
class AppVersionTest {

    /**
     * `git describe` renders either the bare tag ("0.1.41") or the tag plus
     * "-<commits>-g<sha>" once HEAD has moved past it; a checkout with no tags
     * at all — a shallow clone, a source tarball — falls back to "0.0.0-dev".
     * Anything else means the derivation in the root build.gradle.kts changed
     * shape under the packaging scripts that consume it.
     */
    @Test
    fun versionLooksLikeTheTagDerivationProduces() {
        val describeOrFallback = Regex("""^\d+\.\d+\.\d+(-\d+-g[0-9a-f]+)?$|^0\.0\.0-dev$""")
        assertTrue(
            describeOrFallback.matches(APP_VERSION),
            "APP_VERSION '$APP_VERSION' is not a git-describe-derived version",
        )
    }

    /**
     * The guard that matters for packaging: `<release version="$APP_VERSION">`
     * and `X-AppImage-Version=$APP_VERSION` are both written by substitution,
     * so a quote, an angle bracket or a newline in the version would produce
     * malformed metadata rather than a failed build.
     */
    @Test
    fun versionIsSafeToEmbedInPackagingMetadata() {
        assertTrue(APP_VERSION.isNotBlank(), "APP_VERSION is blank")
        assertTrue(
            APP_VERSION.none { it.isWhitespace() || it in "\"'<>&" },
            "APP_VERSION '$APP_VERSION' contains characters that would break the .desktop or AppStream metadata",
        )
    }

    /**
     * `appimage/build.sh` and `flatpak/build.sh` read the version out of the
     * packaged binary with `--version | awk '{print $NF}'`. Anything printed
     * before the version has to stay on the same line's earlier fields, and the
     * flag has to keep working without a display — it runs before any windowing
     * setup for exactly that reason.
     */
    @Test
    fun versionFlagPrintsTheVersionAsItsLastField() {
        val captured = ByteArrayOutputStream()
        val previous = System.out
        try {
            System.setOut(PrintStream(captured, true))
            main(arrayOf(FLAG_VERSION))
        } finally {
            System.setOut(previous)
        }

        val printed = captured.toString().trim()
        assertEquals("skyward $APP_VERSION", printed)
        assertEquals(APP_VERSION, printed.substringAfterLast(' '))
    }
}
