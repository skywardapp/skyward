package dev.fritze.skyward.core.map

private class ResourceMarker

actual fun loadNaturalEarthBytes(): ByteArray? =
    ResourceMarker::class.java.classLoader.getResourceAsStream("natural-earth.bin")?.use { it.readBytes() }
