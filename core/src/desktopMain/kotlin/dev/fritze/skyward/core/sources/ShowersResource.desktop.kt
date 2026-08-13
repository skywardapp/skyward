package dev.fritze.skyward.core.sources

private class ResourceMarker

actual fun loadShowersJsonText(): String =
    checkNotNull(ResourceMarker::class.java.classLoader.getResourceAsStream("showers.json")) {
        "showers.json not found on the desktop classpath"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
