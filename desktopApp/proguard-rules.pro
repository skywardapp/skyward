# §15.5: "ProGuard release minification: enabled, keep rules for
# kotlinx-serialization + reflection-free config."
#
# CURRENTLY NOT APPLIED — `buildTypes.release.proguard.isEnabled` is false in
# build.gradle.kts, with the evidence recorded there. This file is kept wired
# up (`configurationFiles.from(...)`) so re-enabling minification at M7 is a
# one-line change rather than a rediscovery exercise, and so the keep rules
# below stay under review as dependencies move.
#
# Everything below is either (a) a -dontwarn for an OPTIONAL backend of a
# dependency we deliberately do not ship, or (b) a keep rule for something
# reached by reflection, which minification cannot see.

# ---------------------------------------------------------------------------
# Optimizer scope
# ---------------------------------------------------------------------------
# ProGuard's local-variable reallocation miscompiles `EclipseSource.refresh` —
# the §7.1.3 path sampler, a large suspend function with many live doubles.
# `-dontoptimize` is what actually avoids it; `!code/allocation/variable`
# alone is not enough (measured, not assumed).
-dontoptimize

# Deliberately NOT `-dontshrink`. Shrinking is the half of ProGuard that still
# pays for itself here, and the two shrinker failures ADR 0007 records are
# addressed by the targeted keep rules below rather than by turning it off —
# if it were off, those rules would be dead weight nobody would maintain.
# (Both are moot while `proguard.isEnabled` is false; this file describes the
# configuration M7 will re-enable, not the one currently running.)

# ---------------------------------------------------------------------------
# two-slices' optional notification backends (§10.3, §19 R8)
# ---------------------------------------------------------------------------
# The library ships one toaster per platform integration and picks at runtime.
# We depend on none of their libraries: macOS Notification Center needs JNA,
# the JavaFX toaster needs JavaFX + ControlsFX, and DBUSNotifyToaster needs
# dbus-java. Without those, two-slices selects its `NotifyToaster`, which
# shells out to `notify-send` — present in `org.freedesktop.Platform`, so it
# works inside the Flatpak sandbox.
#
# The practical consequence, worth stating plainly: notifications go out
# through `notify-send` rather than a direct DBus call, so notification
# *actions* are unavailable and §10.3's click-to-raise stays best-effort.
# Adding `dbus-java` would change that; it is a dependency decision, not an
# oversight.
-dontwarn com.sun.jna.**
-dontwarn javafx.**
-dontwarn org.controlsfx.**
-dontwarn org.freedesktop.dbus.**
-dontwarn com.sshtools.twoslices.impl.**

# ---------------------------------------------------------------------------
# kotlinx-serialization (§15.5 names this explicitly)
# ---------------------------------------------------------------------------
# Generated serializers are found by name from the companion, so the shrinker
# must not rename or drop them. Everything Skyward persists (§11) and syncs
# (§12) goes through these.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.fritze.skyward.**$$serializer { *; }
-keepclassmembers class dev.fritze.skyward.** {
    *** Companion;
}
-keepclasseswithmembers class dev.fritze.skyward.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.internal.** { *; }

# ---------------------------------------------------------------------------
# SQLite (§11: JdbcSqliteDriver + Xerial)
# ---------------------------------------------------------------------------
# The driver is loaded by name via JDBC's service lookup, and Xerial unpacks a
# native library it locates by resource path.
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**

# ---------------------------------------------------------------------------
# Logging facades pulled in transitively
# ---------------------------------------------------------------------------
# SLF4J binds its backend reflectively and warns loudly when it can't; neither
# it nor Ktor's optional engines are something this app calls directly.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**

# ---------------------------------------------------------------------------
# Ktor's engine discovery (§7: every polled source's HTTP client)
# ---------------------------------------------------------------------------
# Ktor discovers its engine and its serialization extensions through
# META-INF/services files. Those files survive shrinking but name classes that
# nothing references statically, so the shrinker removes the classes and the
# packaged app dies constructing its first source ("Provider
# io.ktor.client.engine.cio.CIOEngineContainer not found", then the same for
# the kotlinx-serialization extension, then whatever is next).
#
# Kept wholesale rather than provider by provider: ProGuard cannot read
# service files, so every future Ktor plugin would be another crash findable
# only by running the packaged binary. Ktor is a small fraction of this app's
# size next to Compose and skiko — not worth the whack-a-mole.
-keep class io.ktor.** { *; }

# ---------------------------------------------------------------------------
# ComposeNativeTray (§10.3)
# ---------------------------------------------------------------------------
# Loads per-platform native bridges by name and calls back from native code
# into JNI-registered methods, none of which the shrinker can trace.
-keep class com.kdroid.composetray.** { *; }
-dontwarn com.kdroid.composetray.**
