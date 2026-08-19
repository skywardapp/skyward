package dev.fritze.skyward.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

class AboutLinksTest {

    @Test
    fun releaseBuildsLinkToTheMatchingTag() {
        assertEquals(
            "https://github.com/skywardapp/skyward/tree/v1.2.3",
            sourceRepositoryUrl("v1.2.3"),
        )
    }

    @Test
    fun untaggedBuildsFallBackToTheRepositoryRoot() {
        assertEquals(SOURCE_REPOSITORY_URL, sourceRepositoryUrl(""))
        assertEquals(SOURCE_REPOSITORY_URL, sourceRepositoryUrl("v1.2.3-4-gabc1234"))
        assertEquals(SOURCE_REPOSITORY_URL, sourceRepositoryUrl(null))
    }
}
