package dev.fritze.skyward.core.model

import kotlinx.serialization.Serializable

/** WGS84 point. [lonDeg] in `[-180, 180)`. */
@Serializable
data class GeoPoint(val latDeg: Double, val lonDeg: Double)
