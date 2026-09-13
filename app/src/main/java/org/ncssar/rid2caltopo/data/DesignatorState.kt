package org.ncssar.rid2caltopo.data

import DroneSpecState

sealed class DesignatorState {
    data class Green(
        val droneSpecState: DroneSpecState
    ) : DesignatorState()

    data class Yellow(
        val candidates: Map<String,DroneSpecState>,
        val embeddedTelemetry: Boolean = false
    ) : DesignatorState()

    object Red : DesignatorState()
}
