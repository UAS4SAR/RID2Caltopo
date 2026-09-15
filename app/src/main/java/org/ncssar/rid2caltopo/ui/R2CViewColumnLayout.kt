package org.ncssar.rid2caltopo.ui

internal object R2CViewColumnLayout {
    const val publishStatusColumnWidthDp = 28
    const val trackLabelColumnWidthDp = 200
    const val remoteIdColumnWidthDp = 240
    const val transportCountColumnWidthDp = 80
    const val transportSignalColumnWidthDp = 40
    const val r2cWaypointColumnWidthDp = 80
    const val seiColumnWidthDp = 80
    const val totalColumnWidthDp = 80
    const val flightDurationColumnWidthDp = 125
    const val r2cRttColumnWidthDp = 125

    val transportColumnWidthsDp = listOf(
        transportCountColumnWidthDp,
        transportSignalColumnWidthDp,
        transportCountColumnWidthDp,
        transportSignalColumnWidthDp,
        transportCountColumnWidthDp,
        transportSignalColumnWidthDp,
        transportCountColumnWidthDp,
        transportSignalColumnWidthDp
    )
    val waypointsReceivedHeaderWidthDp =
        transportColumnWidthsDp.sum() + r2cWaypointColumnWidthDp + seiColumnWidthDp + totalColumnWidthDp
    val headerColumnWidthsDp =
        listOf(publishStatusColumnWidthDp, trackLabelColumnWidthDp, remoteIdColumnWidthDp) +
            transportColumnWidthsDp +
            listOf(r2cWaypointColumnWidthDp, seiColumnWidthDp, totalColumnWidthDp, flightDurationColumnWidthDp, r2cRttColumnWidthDp)
    val droneItemColumnWidthsDp = headerColumnWidthsDp
}
