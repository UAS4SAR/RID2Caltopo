package org.ncssar.rid2caltopo.video

import org.osmdroid.views.overlay.Polyline

/** No MapView/View access on the worker. InfoWindow is attached later on Main. */
internal class WorkerPreparedPolyline(density: Float) : Polyline() {
    init { mDensity = density }
}
