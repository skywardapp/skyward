package dev.fritze.skyward.core.map

private class ResourceMarker

/**
 * Unlike `showers.json`, which had to be checked in twice because it is a
 * source file (see `ShowersResource.android.kt`), `natural-earth.bin` is
 * *generated* — so `core/build.gradle.kts` wires the one `convertNaturalEarth`
 * output into both the android and desktop resource paths, and there is no
 * second copy to keep in step. ADR 0023 has the reasoning.
 */
internal actual fun loadNaturalEarthBytes(): ByteArray? =
    ResourceMarker::class.java.classLoader?.getResourceAsStream("natural-earth.bin")?.use { it.readBytes() }
