package dev.fritze.skyward.desktop.ui.common

import java.awt.Desktop
import java.net.URI

/**
 * Opens [url] in the user's browser. Inside Flatpak `java.awt.Desktop` has no
 * portal-aware backend, so `xdg-open` — which the runtime does route through
 * the OpenURI portal — is the fallback rather than an error dialog.
 */
fun openInBrowser(url: String) {
    val uri = runCatching { URI(url) }.getOrNull() ?: return
    if (uri.scheme != "https" && uri.scheme != "http") return

    val opened = runCatching {
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE) }
        desktop?.browse(uri) != null
    }.getOrDefault(false)

    if (!opened) {
        runCatching { ProcessBuilder("xdg-open", uri.toString()).start() }
            .onFailure { System.err.println("could not open $url (${it.message})") }
    }
}
