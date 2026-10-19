package org.ncssar.rid2caltopo.video

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.concurrent.atomic.AtomicInteger

/** A real osmdroid viewport with the production HUD/snapshot; no network, device auth or flight. */
@RunWith(AndroidJUnit4::class)
class MapGestureRenderingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun gestureUpdatesOnlyTheHudAndKeepsLatestBoundsForOfflineSelection() {
        val snapshot=MapPaneViewportSnapshot()
        val scale=mutableStateOf<MapScaleBarState?>(null)
        val parentCompositions=AtomicInteger()
        val mapUpdates=AtomicInteger()
        lateinit var map:MapView
        compose.runOnUiThread{compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        compose.setContent {
            // Same readiness subscription as SplitMapPane. Coordinates are read on demand.
            snapshot.available.value
            SideEffect{parentCompositions.incrementAndGet()}
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(modifier=Modifier.fillMaxSize(),factory={ context->
                        MapView(context).apply {
                            map=this;setUseDataConnection(false)
                            controller.setCenter(GeoPoint(39.0,-121.0));controller.setZoom(19.0)
                            addMapListener(object:MapListener {
                                private fun changed():Boolean {
                                    snapshot.bounds=boundingBox
                                    scale.value=mapScaleBarState(1.0/Math.pow(2.0,zoomLevelDouble-19),180.0)
                                    return false
                                }
                                override fun onScroll(event:ScrollEvent?)=changed()
                                override fun onZoom(event:ZoomEvent?)=changed()
                            })
                        }
                    },update={mapUpdates.incrementAndGet()})
                    MapScaleBarHud(scale)
                }
            }
        }
        compose.runOnIdle {
            snapshot.bounds=map.boundingBox
            scale.value=MapScaleBarState("50 ft",100.0)
        }
        compose.waitForIdle()
        val beforeParents=parentCompositions.get();val beforeMapUpdates=mapUpdates.get()
        val firstBounds=snapshot.bounds
        try {
            for(i in 1..12) {
                compose.runOnIdle {
                    map.controller.setZoom(19.0+i*.02)
                    map.controller.setCenter(GeoPoint(39.0+i*.00001,-121.0+i*.00001))
                }
                compose.waitForIdle()
            }
            compose.runOnIdle {
                assertEquals("A scale change must not recompose the map panel",beforeParents,parentCompositions.get())
                assertEquals("Gestures must not rebuild map overlays",beforeMapUpdates,mapUpdates.get())
                assertNotEquals(firstBounds,snapshot.bounds)
                assertEquals(map.boundingBox.lonWest,snapshot.bounds!!.lonWest,0.0)
                assertEquals(map.boundingBox.latNorth,snapshot.bounds!!.latNorth,0.0)
                assertTrue(snapshot.available.value);assertNotNull(scale.value)
            }
        } finally { compose.runOnIdle{map.onDetach()} }
    }
}
