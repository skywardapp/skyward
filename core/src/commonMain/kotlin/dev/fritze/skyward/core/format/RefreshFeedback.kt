package dev.fritze.skyward.core.format

/**
 * §13.2's pull-to-refresh is allowed to fail — the sources are third-party
 * HTTPS endpoints and the device is often offline. What is not allowed is
 * failing *silently*: `SourceRunner.runDue` records each failure in that
 * source's [dev.fritze.skyward.core.sources.SourceDiagnostics] and carries
 * on, which is right for the pipeline and wrong for the person who just
 * pulled and watched a spinner stop with nothing to show for it.
 *
 * This renders the ids that came back failing into one line for a transient
 * surface (a snackbar, a banner). Pure, so both frontends can say the same
 * thing, and so §17's tests can pin the wording without a screen.
 *
 * [failedSourceIds] is expected in a stable order (the order the runner ran
 * them in); this function does not sort, so a caller that wants a different
 * emphasis chooses it.
 */
fun refreshFailureMessage(failedSourceIds: List<String>): String? {
    val names = failedSourceIds.map { sourceDisplayName(it) }
    val subject = when (names.size) {
        0 -> return null
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        // Beyond two the list stops being readable at a glance and the detail
        // is a tap away in Settings > Sources anyway, which is where the line
        // already points.
        else -> "${names[0]} and ${names.size - 1} more sources"
    }
    return "Couldn't reach $subject — see Settings > Sources"
}
