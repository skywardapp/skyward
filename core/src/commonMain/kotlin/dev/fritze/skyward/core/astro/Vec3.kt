package dev.fritze.skyward.core.astro

import kotlin.math.sqrt

/** A plain Cartesian vector, AU — deliberately independent of Astronomy Engine's [io.github.cosinekitty.astronomy.Vector] (§7.4.2), which also carries a timestamp we don't need here. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    fun length() = sqrt(x * x + y * y + z * z)
}
