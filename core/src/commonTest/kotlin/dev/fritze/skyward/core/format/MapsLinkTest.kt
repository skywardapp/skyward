package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class MapsLinkTest {

    @Test
    fun `geo URI encodes point and label`() {
        assertEquals(
            "geo:0,0?q=52.52,13.405(Best%20viewing%20spot)",
            geoUri(GeoPoint(52.52, 13.405), "Best viewing spot"),
        )
    }

    @Test
    fun `geo URI handles negative coordinates`() {
        assertEquals("geo:0,0?q=-33.87,-70.65(Spot)", geoUri(GeoPoint(-33.87, -70.65), "Spot"))
    }

    @Test
    fun `Google Maps URL encodes point`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=52.52,13.405",
            googleMapsUrl(GeoPoint(52.52, 13.405)),
        )
    }

    @Test
    fun `Google Maps URL handles negative coordinates`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=-33.87,-70.65",
            googleMapsUrl(GeoPoint(-33.87, -70.65)),
        )
    }
}
