package dev.fritze.skyward.core.format

private val releaseTagPattern = Regex("""^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")

/** §13.1/§15.4: stable hosted policy URL for the in-app About screen. */
const val PRIVACY_POLICY_URL = "https://skywardapp.github.io/skyward/privacy-policy.html"

/** The public repository root, used as the dev-build fallback for §13.1's source link. */
const val SOURCE_REPOSITORY_URL = "https://github.com/skywardapp/skyward"

/**
 * §16's EONET row: the About screen must surface NASA EONET's "general
 * information purposes" disclaimer rather than only naming the source.
 */
const val EONET_ATTRIBUTION_NOTE =
    "Event data: NASA EONET. Positions and dates are approximate and provided for general information purposes."

/**
 * §13.1 / GPL §6(d): a shipped release must link to the source tree at the tag
 * matching that release's versionCode. Untagged or post-tag dev builds fall
 * back to the repository root so local builds still have a meaningful link.
 */
fun sourceRepositoryUrl(releaseTag: String?): String =
    releaseTag
        ?.takeIf { releaseTagPattern.matches(it) }
        ?.let { "$SOURCE_REPOSITORY_URL/tree/$it" }
        ?: SOURCE_REPOSITORY_URL
