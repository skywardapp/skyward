package dev.fritze.skyward.desktop.notify

/**
 * Escapes the markup characters the freedesktop notification spec gives
 * meaning to inside a notification *body*.
 *
 * §10.5's copy is rendered in `:core` from occurrence payloads, and two of
 * those payload fields are strings the app never wrote: EONET's
 * `categoryTitle` (§7.5) and JPL's comet `name`/`designation` (§7.4). The
 * freedesktop spec allows a body to carry a small HTML subset — `<b>`, `<i>`,
 * `<u>`, `<a href>`, `<img src>` — and most daemons render it, so a hostile
 * or compromised upstream could put a link into a notification the user has
 * every reason to trust. Escaping at this boundary closes that off without
 * `:core` having to know what a desktop notification daemon parses.
 *
 * Only `&`, `<` and `>` are escaped, and `&` must come first or the
 * escapes would escape each other. `"` and `'` are deliberately left alone:
 * they are only special inside an attribute value, which nothing here can
 * produce once `<` is gone, and mangling the apostrophes in ordinary English
 * copy to guard against nothing would be the worse trade.
 *
 * The title is *not* escaped, here or by the callers: the spec's summary
 * field is plain text that no daemon parses as markup, so escaping it would
 * only put a literal `&amp;` in front of a user whose location is called
 * "Ben & Jerry's". The same literal can appear in a body on a daemon that
 * doesn't implement body markup, which is the accepted cost — the spec's
 * `body-markup` capability is not reachable through either backend
 * ([TwoSlicesNotifier] hides it, `notify-send` has no way to report it), so
 * escaping unconditionally is the only safe default available.
 */
internal fun escapeNotificationBodyMarkup(body: String): String = body
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
