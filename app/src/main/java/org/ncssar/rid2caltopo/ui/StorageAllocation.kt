package org.ncssar.rid2caltopo.ui

/** Limits are allowances, not disk reservations. Used bytes already reduce filesystem free space. */
internal data class StorageAllocation(
    val freeBytes: Long?, val cacheUsed: Long, val cacheMaximum: Long,
    val flightUsed: Long, val flightMaximum: Long
) {
    val remainingCache: Long get() = (cacheMaximum - cacheUsed).coerceAtLeast(0)
    val remainingFlight: Long get() = (flightMaximum - flightUsed).coerceAtLeast(0)
    val freeAfterAllowances: Long? get() = freeBytes?.let { it - remainingCache - remainingFlight }
}
