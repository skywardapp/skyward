package dev.fritze.skyward.core.testing

/**
 * §17.3's checked-in HTTP fixtures, read from
 * `core/src/commonTest/resources/fixtures/`.
 *
 * They live in `commonTest`'s resources (where §17.3 puts them) but are read
 * from `desktopTest`, which is the source set that can reach a classloader --
 * see `docs/adr/0009-fixture-files-and-jvm-only-golden-tests.md` for why the
 * golden tests that consume them are JVM-only while the parsers they exercise
 * stay in `commonMain`.
 *
 * Regenerate any of these with the scripts in `tools/fixtures/`; never edit
 * one by hand, or the next refresh silently reverts the edit and the fixture
 * stops describing anything real.
 */
internal object Fixtures {

    fun text(name: String): String {
        val path = "fixtures/$name"
        val stream = checkNotNull(Fixtures::class.java.classLoader.getResourceAsStream(path)) {
            "missing fixture resource: $path (regenerate it with tools/fixtures/)"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * A fixture CSV as one map per row, keyed by the header. Blank lines and
     * `#` comments are skipped so a fixture can carry its own provenance note
     * at the top.
     */
    fun csv(name: String): List<Map<String, String>> {
        val lines = text(name).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        val header = lines.first().split(',')
        return lines.drop(1).map { line ->
            val cols = line.split(',')
            header.indices.associate { i -> header[i] to (cols.getOrNull(i)?.trim() ?: "") }
        }
    }
}
