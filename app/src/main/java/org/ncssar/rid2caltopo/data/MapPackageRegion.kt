package org.ncssar.rid2caltopo.data

import kotlin.math.*

/** Tile overlap, independent of screen zoom and offline preparation history. */
internal data class MapPackageRegion(val north: Double, val east: Double, val south: Double, val west: Double) {
    fun overlaps(other: MapPackageRegion): Boolean {
        if (north <= other.south || south >= other.north) return false
        fun segments(w: Double, e: Double) = if (w <= e) listOf(w to e) else listOf(w to 180.0, -180.0 to e)
        return segments(west, east).any { a -> segments(other.west, other.east).any { b -> a.first < b.second && a.second > b.first } }
    }

    fun includesTile(z: Int, x: Int, y: Int): Boolean {
        if (z !in 0..29) return false
        val n = 2.0.pow(z)
        if (x < 0 || y < 0 || x >= n || y >= n) return false
        fun latitude(row: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * row / n))))
        return overlaps(MapPackageRegion(latitude(y), (x + 1) / n * 360 - 180, latitude(y + 1), x / n * 360 - 180))
    }

    companion object {
        fun demBounds(name: String): MapPackageRegion? {
            if (!name.endsWith(".tif", true) && !name.endsWith(".tiff", true)) return null
            val piece = Regex("R2C_(?:S1M|1M)_(-?[0-9]+)_(-?[0-9]+)_(-?[0-9]+)_(-?[0-9]+)_.*", RegexOption.IGNORE_CASE).matchEntire(name)
            if (piece != null) {
                val b = piece.groupValues.drop(1).map { it.toDouble() / 100_000 }
                return MapPackageRegion(b[1], b[3], b[0], b[2])
            }
            val tile = Regex("([ns])([0-9]{2})([ew])([0-9]{3})", RegexOption.IGNORE_CASE).find(name) ?: return null
            val north = tile.groupValues[2].toDouble() * if (tile.groupValues[1].equals("n", true)) 1 else -1
            val west = tile.groupValues[4].toDouble() * if (tile.groupValues[3].equals("e", true)) 1 else -1
            return MapPackageRegion(north, west + 1, north - 1, west)
        }
    }
}
