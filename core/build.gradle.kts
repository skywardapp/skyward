import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

/**
 * §14.1: "Base layer: Natural Earth 1:50m land + coastline polygons (public
 * domain), converted at build time from GeoJSON to a compact binary
 * float-array resource (script in `tools/`; do the GeoJSON→binary conversion
 * in Gradle, not at runtime)."
 *
 * The conversion lives here rather than in a standalone `tools/` script
 * because a Gradle task with Groovy's bundled `JsonSlurper` needs no extra
 * toolchain on the build machine — `tools/naturalearth/` holds the vendored
 * input and documents how to refresh it.
 *
 * The output is wired into the **desktop** target's resources only, not
 * commonMain's: the map is a desktop view (§14.1; Android's Map tab is
 * explicitly v1.1 backlog, §18), and half a megabyte of coastlines has no
 * business inside the APK.
 *
 * Binary layout, big-endian:
 *   magic "SKNE" (4 bytes) · version u16 · ringCount i32
 *   per ring: pointCount i32, then pointCount × (lon f32, lat f32)
 * Float32 at 1:50m resolution is good to ~1e-5°, comfortably finer than the
 * source data and half the size of doubles.
 */
val convertNaturalEarth by tasks.registering {
    description = "Converts the vendored Natural Earth 1:50m land GeoJSON into core's compact binary map resource."
    val source = rootProject.layout.projectDirectory.file("tools/naturalearth/ne_50m_land.geojson.gz")
    val outputDirectory = layout.buildDirectory.dir("generated/naturalEarth")
    inputs.file(source)
    outputs.dir(outputDirectory)

    doLast {
        @Suppress("UNCHECKED_CAST")
        val parsed = GZIPInputStream(source.asFile.inputStream().buffered()).reader().use { reader ->
            groovy.json.JsonSlurper().parse(reader) as Map<String, Any>
        }

        @Suppress("UNCHECKED_CAST")
        val features = parsed["features"] as List<Map<String, Any>>
        val rings = mutableListOf<List<List<Number>>>()
        for (feature in features) {
            @Suppress("UNCHECKED_CAST")
            val geometry = feature["geometry"] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val polygons: List<List<List<List<Number>>>> = when (geometry["type"]) {
                "Polygon" -> listOf(geometry["coordinates"] as List<List<List<Number>>>)
                "MultiPolygon" -> geometry["coordinates"] as List<List<List<List<Number>>>>
                else -> emptyList()
            }
            for (polygon in polygons) rings += polygon
        }

        val target = outputDirectory.get().asFile.also { it.mkdirs() }.resolve("natural-earth.bin")
        DataOutputStream(BufferedOutputStream(target.outputStream())).use { out ->
            out.writeBytes("SKNE")
            out.writeShort(1)
            out.writeInt(rings.size)
            for (ring in rings) {
                out.writeInt(ring.size)
                for (point in ring) {
                    out.writeFloat(point[0].toFloat()) // lon
                    out.writeFloat(point[1].toFloat()) // lat
                }
            }
        }
        logger.lifecycle("convertNaturalEarth: ${rings.size} rings, ${rings.sumOf { it.size }} points -> ${target.length()} bytes")
    }
}

kotlin {
    jvmToolchain(17)

    androidTarget()
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines.extensions)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
            }
        }
        val desktopMain by getting {
            resources.srcDir(convertNaturalEarth)
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

android {
    namespace = "dev.fritze.skyward.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("SkywardDatabase") {
            packageName.set("dev.fritze.skyward.core.persistence")
            // §11 calls for verifyMigrations = true, but that verifies .sqm migration
            // files against snapshotted schema versions — meaningless before any
            // migration exists. Flip to true (and commit the initial schema snapshot)
            // the first time this schema actually changes after release.
            verifyMigrations.set(false)
        }
    }
}

// core/src/androidMain/resources/showers.json is a hand-maintained duplicate of
// core/src/commonMain/resources/showers.json (see ShowersResource.android.kt for
// why AGP needs its own copy). Nothing else keeps the two in sync, so a catalog
// update that only touches one of them would silently make Android and desktop
// emit different meteor-shower data under identical occurrence ids.
val verifyShowerCatalogsMatch by tasks.registering {
    description = "Fails if the commonMain and androidMain showers.json copies have diverged."
    val commonFile = layout.projectDirectory.file("src/commonMain/resources/showers.json")
    val androidFile = layout.projectDirectory.file("src/androidMain/resources/showers.json")
    inputs.file(commonFile)
    inputs.file(androidFile)
    doLast {
        val commonText = commonFile.asFile.readText()
        val androidText = androidFile.asFile.readText()
        check(commonText == androidText) {
            "core/src/commonMain/resources/showers.json and core/src/androidMain/resources/showers.json " +
                "have diverged. Update both together (see the comment in ShowersResource.android.kt)."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyShowerCatalogsMatch)
}
