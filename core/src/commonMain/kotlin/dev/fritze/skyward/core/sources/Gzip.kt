package dev.fritze.skyward.core.sources

/**
 * §7.3.1/§11: the OVATION nowcast grid (~65 kB raw) is persisted gzipped in
 * `source_state`. `commonMain` has no `java.util.zip` (same
 * androidTarget-vs-desktop-jvm-target split as [loadShowersJsonText]), hence
 * expect/actual.
 */
expect fun gzipCompress(data: ByteArray): ByteArray
expect fun gzipDecompress(data: ByteArray): ByteArray
