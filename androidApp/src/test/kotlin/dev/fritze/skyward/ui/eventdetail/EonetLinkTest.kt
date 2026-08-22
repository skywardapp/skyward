package dev.fritze.skyward.ui.eventdetail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #65: `payload.link` comes straight from the EONET JSON, so
 * "Open on EONET" must only ever offer a link that actually resolves to
 * eonet.gsfc.nasa.gov -- never an arbitrary scheme or a host merely
 * disguised as that one.
 */
class EonetLinkTest {

    @Test
    fun acceptsARealEonetApiLink() {
        assertTrue(isSafeEonetLink("https://eonet.gsfc.nasa.gov/api/v3/events/EONET_1234"))
    }

    @Test
    fun isCaseInsensitiveOnSchemeAndHost() {
        assertTrue(isSafeEonetLink("HTTPS://EONET.GSFC.NASA.GOV/api/v3/events/EONET_1234"))
    }

    @Test
    fun acceptsPlainHttpToo() {
        assertTrue(isSafeEonetLink("http://eonet.gsfc.nasa.gov/api/v3/events/EONET_1234"))
    }

    @Test
    fun rejectsTheParserDefaultEmptyLink() {
        assertFalse(isSafeEonetLink(""))
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(isSafeEonetLink("not a url"))
    }

    @Test
    fun rejectsNonHttpSchemes() {
        assertFalse(isSafeEonetLink("intent://eonet.gsfc.nasa.gov/#Intent;scheme=https;end"))
        assertFalse(isSafeEonetLink("market://details?id=com.evil.app"))
        assertFalse(isSafeEonetLink("file:///etc/passwd"))
        assertFalse(isSafeEonetLink("sms:12345"))
    }

    @Test
    fun rejectsALookalikeSubdomain() {
        assertFalse(isSafeEonetLink("https://eonet.gsfc.nasa.gov.evil.com/x"))
    }

    @Test
    fun rejectsAUserinfoTrick() {
        assertFalse(isSafeEonetLink("https://eonet.gsfc.nasa.gov@evil.com/x"))
    }

    @Test
    fun rejectsAWrongHostAltogether() {
        assertFalse(isSafeEonetLink("https://evil.com/eonet.gsfc.nasa.gov"))
    }
}
