package org.ncssar.rid2caltopo.video

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.video.mapcache.MapTileSourceRegistry
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class OfflinePrepPreferencesTest {
    private class PrefContext : ContextWrapper(null) {
        val values = ConcurrentHashMap<String, Any>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { values[args[0] as String] = args[1] as Boolean; proxy }
                "putInt" -> { values[args[0] as String] = args[1] as Int; proxy }
                "putString" -> { if (args[1] == null) values.remove(args[0] as String) else values[args[0] as String] = args[1]; proxy }
                "remove" -> { values.remove(args[0] as String); proxy }
                "apply", "commit" -> true
                else -> proxy
            }
        } as SharedPreferences.Editor
        private val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args[0] as String] as? Boolean ?: args[1] as Boolean
                "getInt" -> values[args[0] as String] as? Int ?: args[1] as Int
                "getString" -> values[args[0] as String] as? String ?: args[1]
                "edit" -> editor
                "contains" -> values.containsKey(args[0])
                else -> null
            }
        } as SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int) = preferences
        override fun getApplicationContext() = this
    }

    @Test fun persistAndLoadClampsZoomRanges() {
        val loaded = AndroidMapOfflinePrepCoordinator::class.java.getDeclaredField("preferencesLoaded")
        loaded.isAccessible = true
        (loaded.get(null) as AtomicBoolean).set(false)

        val context = PrefContext()
        AndroidMapOfflinePrepCoordinator.includeUsgsScannedTopo.value = true
        AndroidMapOfflinePrepCoordinator.includeUsfsTopo.value = true
        AndroidMapOfflinePrepCoordinator.includeContours.value = false
        AndroidMapOfflinePrepCoordinator.layerSelectionInitialized.value = true
        AndroidMapOfflinePrepCoordinator.usgsScannedTopoMinZoom.value = -5
        AndroidMapOfflinePrepCoordinator.usgsScannedTopoMaxZoom.value = 99
        AndroidMapOfflinePrepCoordinator.usfsTopoMinZoom.value = 12
        AndroidMapOfflinePrepCoordinator.usfsTopoMaxZoom.value = 14
        AndroidMapOfflinePrepCoordinator.persistSourceSelection(context)

        (loaded.get(null) as AtomicBoolean).set(false)
        AndroidMapOfflinePrepCoordinator.includeUsgsScannedTopo.value = false
        AndroidMapOfflinePrepCoordinator.usgsScannedTopoMinZoom.value = 12
        AndroidMapOfflinePrepCoordinator.usgsScannedTopoMaxZoom.value = 16
        AndroidMapOfflinePrepCoordinator.ensurePreferencesLoaded(context)

        assertTrue(AndroidMapOfflinePrepCoordinator.includeUsgsScannedTopo.value)
        assertTrue(AndroidMapOfflinePrepCoordinator.includeUsfsTopo.value)
        assertFalse(AndroidMapOfflinePrepCoordinator.includeContours.value)
        val usgs = MapTileSourceRegistry.clampZoomRange(
            "USGS-ScannedTopo",
            AndroidMapOfflinePrepCoordinator.usgsScannedTopoMinZoom.value,
            AndroidMapOfflinePrepCoordinator.usgsScannedTopoMaxZoom.value
        )
        assertEquals(usgs.first, AndroidMapOfflinePrepCoordinator.usgsScannedTopoMinZoom.value)
        assertEquals(usgs.last, AndroidMapOfflinePrepCoordinator.usgsScannedTopoMaxZoom.value)
        assertTrue(usgs.first <= usgs.last)
    }
}
