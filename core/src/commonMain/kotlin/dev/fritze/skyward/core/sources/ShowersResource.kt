package dev.fritze.skyward.core.sources

/**
 * Loads the bundled Stellarium meteor-shower catalog (§7.2.1, §16) as raw
 * JSON text. `commonMain` can't reach `java.lang.Class`'s resource-loading
 * API directly (androidTarget and the desktop jvm target are distinct KMP
 * targets even though both happen to run on a JVM), hence expect/actual.
 */
expect fun loadShowersJsonText(): String
