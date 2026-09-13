package org.ncssar.rid2caltopo.video.surface

/** Only the explicit offline-preparation worker calls this decoder. */
internal object SurfaceNative {
    init { System.loadLibrary("aol_prepare") }
    external fun create(lat: Double, lon: Double, west: Double, south: Double, width: Int, height: Int): Long
    external fun free(grid: Long)
    external fun open(grid: Long, path: String): Int
    external fun step(grid: Long, batch: Int): Int
    external fun write(grid: Long, surface: String, ground: String): Int
    external fun error(grid: Long): String
    external fun crs(grid: Long): String
    external fun read(grid: Long): Long
    external fun count(grid: Long): Long
    external fun missing(grid: Long): Long
}
