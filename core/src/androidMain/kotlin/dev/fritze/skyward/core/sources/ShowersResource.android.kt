package dev.fritze.skyward.core.sources

// core/src/androidMain/resources/showers.json is a duplicate of
// core/src/commonMain/resources/showers.json, not a mistake: verified
// empirically that AGP does not merge commonMain/resources into the
// androidTarget's packaged resources the way it does for the desktop jvm
// target (the file was simply absent from the built APK until copied here
// directly). If a future AGP/KMP version wires this automatically, drop the
// androidMain copy and re-verify with `unzip -l app.apk | grep showers.json`.
private class ResourceMarker

actual fun loadShowersJsonText(): String =
    checkNotNull(ResourceMarker::class.java.classLoader?.getResourceAsStream("showers.json")) {
        "showers.json not found on the Android classpath"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
