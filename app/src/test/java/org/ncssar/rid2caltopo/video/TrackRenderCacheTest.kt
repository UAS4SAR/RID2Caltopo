package org.ncssar.rid2caltopo.video

import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.*
import org.junit.Test

class TrackRenderCacheTest {
    @Test fun reusesGeometryButUpdatesForAppendReplacementAndExpiry() {
        val cache = TrackRenderCache<Any>()
        val points = mutableStateListOf(LocalTrackPoint("A", 39.0, -121.0, 100.0, 1, 1))
        var updates = 0
        fun render() = cache.getOrUpdate("A", points.toList(), { Any() }) { _, _ -> updates++ }
        val first = render()
        assertSame(first, render())
        assertEquals(1, updates)
        points.add(points[0].copy(timestampMsec = 2))
        assertSame(first, render())
        points[0] = points[0].copy(lat = 40.0)
        assertSame(first, render())
        points.removeAt(0)
        assertSame(first, render())
        assertEquals(4, updates)
        cache.retainKeys(emptySet())
        assertNotSame(first, render())
        assertEquals(5, updates)
    }
}
