package dev.fritze.skyward.desktop.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Remote strings reach notification bodies (issue #78): EONET category
 * titles (§7.5) and JPL comet names (§7.4) are rendered into §10.5's copy
 * verbatim, and freedesktop notification daemons parse a small HTML subset
 * in the body.
 */
class NotificationMarkupTest {

    @Test
    fun leavesOrdinaryCopyAlone() {
        val body = "Predicted magnitude 4.2 — binocular target. Highest at 38° around 04:10."
        assertEquals(body, escapeNotificationBodyMarkup(body))
    }

    @Test
    fun neutralisesALinkInjectedThroughARemoteName() {
        // What a hostile JPL/EONET response would aim for: a clickable link in
        // a notification the user trusts because Skyward posted it.
        val escaped = escapeNotificationBodyMarkup(
            """Comet <a href="https://phish.example/">C/2027 A1</a> near its best""",
        )

        assertFalse("<a" in escaped)
        assertEquals(
            "Comet &lt;a href=\"https://phish.example/\"&gt;C/2027 A1&lt;/a&gt; near its best",
            escaped,
        )
    }

    @Test
    fun escapesTheAmpersandFirstSoTheEscapesDoNotEscapeEachOther() {
        // "&lt;" escaped in the wrong order becomes "&amp;lt;" — the daemon
        // would then render the literal text "&lt;" rather than "<".
        assertEquals("&amp;lt;b&amp;gt;", escapeNotificationBodyMarkup("&lt;b&gt;"))
        assertEquals("&amp;amp;", escapeNotificationBodyMarkup("&amp;"))
    }

    @Test
    fun escapesEveryOccurrence() {
        assertEquals("&lt;b&gt;bold&lt;/b&gt; &amp; &lt;i&gt;italic&lt;/i&gt;", escapeNotificationBodyMarkup("<b>bold</b> & <i>italic</i>"))
    }

    @Test
    fun leavesQuotesAndApostrophesAlone() {
        // Only special inside an attribute value, which nothing can produce
        // once "<" is gone — and English copy is full of apostrophes.
        val body = "Tonight's peak, the so-called \"shooting stars\"."
        assertEquals(body, escapeNotificationBodyMarkup(body))
    }
}
