package org.ncssar.rid2caltopo.ui

internal data class StorageLimits(val bytes: Long, val days: Long)
internal fun parseStorageLimits(size: String, days: String): StorageLimits? {
    val gb = size.trim().toDoubleOrNull() ?: return null
    val age = days.trim().toLongOrNull() ?: return null
    if (!gb.isFinite() || gb !in 0.1..1000.0 || age !in 1..3650) return null
    return StorageLimits((gb * 1e9).toLong(), age)
}
