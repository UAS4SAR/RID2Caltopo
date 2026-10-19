package org.ncssar.rid2caltopo.video

import androidx.compose.runtime.mutableStateOf
import org.osmdroid.util.BoundingBox

/** osmdroid owns gesture motion. Compose only needs to know when measured bounds exist. */
internal class MapPaneViewportSnapshot {
    private var latest:BoundingBox?=null
    val available=mutableStateOf(false)
    var bounds:BoundingBox?
        get()=latest
        set(value){latest=value;available.value=value!=null}
}
