package dev.fritze.skyward.core.map

private class ResourceMarker

internal actual fun loadNaturalEarthBytes(): ByteArray? =
    ResourceMarker::class.java.classLoader.getResourceAsStream("natural-earth.bin")?.use { it.readBytes() }
