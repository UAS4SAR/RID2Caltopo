package org.ncssar.rid2caltopo.video

import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.PeerCoordinator
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry

internal fun localDeviceMarkerColor(): String {
    val state = R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.coordinationIndicatorState
    if (CaltopoMap.IsInitialDeviceMarkerPublishPending() &&
        state != PeerCoordinator.CoordinationIndicatorState.HEALTHY &&
        state != PeerCoordinator.CoordinationIndicatorState.IDLE) {
        return LOCAL_DEVICE_COLOR_STARTING
    }
    return when (state) {
        PeerCoordinator.CoordinationIndicatorState.HEALTHY -> LOCAL_DEVICE_COLOR_HEALTHY
        PeerCoordinator.CoordinationIndicatorState.IDLE -> LOCAL_DEVICE_COLOR_HEALTHY
        PeerCoordinator.CoordinationIndicatorState.DEGRADED -> LOCAL_DEVICE_COLOR_DEGRADED
        PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED -> LOCAL_DEVICE_COLOR_UNCONFIGURED
    }
}

internal fun localDeviceStatusLines(): List<String> {
    val coordinator = R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
    return buildList {
        add(coordinator.coordinationStatusText)
        addAll(coordinator.coordinationDiagnosticLines)
        org.ncssar.rid2caltopo.app.DeviceLocationMonitor.accuracyLine()?.let { add(it) }
    }
}
