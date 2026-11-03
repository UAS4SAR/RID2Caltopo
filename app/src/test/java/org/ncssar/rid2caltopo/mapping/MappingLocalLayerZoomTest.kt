package org.ncssar.rid2caltopo.mapping

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.video.DeviceGpsRecenterPlan
import org.ncssar.rid2caltopo.video.MapCameraRequests
import org.ncssar.rid2caltopo.video.planDeviceGpsRecenter
import org.ncssar.rid2caltopo.video.shouldApplyPendingDeviceGpsRecenter

class MappingLocalLayerZoomTest {
    @get:Rule val folder = TemporaryFolder()

    private class PreferencesContext(private val files: File) : ContextWrapper(null) {
        val values = mutableMapOf<String, Any>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    if (args[1] == null) values.remove(args[0] as String) else values[args[0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    values.remove(args[0] as String)
                    proxy
                }
                "apply" -> null
                else -> error("Unexpected preference edit ${method.name}")
            }
        } as SharedPreferences.Editor
        private val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference read ${method.name}")
            }
        } as SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int) = preferences
        override fun getFilesDir(): File = files
    }

    private fun withTiles(context: PreferencesContext, layer: MappingLayer) {
        val dir = File(context.filesDir, "mapping-layers/${layer.id}").apply { mkdirs() }
        File(dir, "layer.json").writeText(layer.json().toString())
        File(dir, "18/1").mkdirs()
        File(dir, "18/1/2.png").writeBytes(byteArrayOf(1))
    }

    private fun layer(incident: String = "other-incident") = MappingLayer(
        "a817d2b0-bc06-4080-9f15-6fd4cd7e0c92",
        incident,
        "Local map",
        "http://127.0.0.1/tiles/{Z}/{X}/{Y}.png",
        10,
        20,
        bounds = listOf(-120.5, 38.5, -120.4, 38.6),
    )

    @Before
    fun resetTokens() {
        MappingWorkspaceState.clearLocalPreviewZoom()
        MapCameraRequests.resetForTests()
    }

    @Test
    fun coldStartWithPersistedOtherIncidentLayerDoesNotZoom() {
        // Persisted selection alone: no one-shot token → never zoom, even with usable bounds.
        val plan = planMappingLocalLayerZoom(
            zoomToken = null,
            lastConsumedToken = null,
            hasUsableBounds = true,
            mapReady = true,
        )
        assertFalse(plan.shouldZoom)
        assertFalse(plan.consumeToken)
    }

    @Test
    fun view2DZoomsExactlyOnceAndRecompositionDoesNotRezoom() {
        val context = PreferencesContext(folder.root)
        val selected = layer("incident-a")
        context.values["incident-a"] = "[${selected.json()}]"
        withTiles(context, selected)

        assertNull(MappingWorkspaceState.localPreviewZoomToken.value)
        assertTrue(MappingLayer.selectLocal(context, selected))
        val token = MappingWorkspaceState.localPreviewZoomToken.value
        assertNotNull(token)

        val first = planMappingLocalLayerZoom(
            zoomToken = token,
            lastConsumedToken = null,
            hasUsableBounds = true,
            mapReady = true,
        )
        assertTrue(first.shouldZoom)
        assertTrue(first.consumeToken)

        val again = planMappingLocalLayerZoom(
            zoomToken = token,
            lastConsumedToken = token,
            hasUsableBounds = true,
            mapReady = true,
        )
        assertFalse(again.shouldZoom)
        assertFalse(again.consumeToken)

        // After MapPane clears the one-shot token, later loads still do not zoom.
        MappingWorkspaceState.clearLocalPreviewZoom()
        val coldReload = planMappingLocalLayerZoom(
            zoomToken = MappingWorkspaceState.localPreviewZoomToken.value,
            lastConsumedToken = token,
            hasUsableBounds = true,
            mapReady = true,
        )
        assertFalse(coldReload.shouldZoom)
    }

    @Test
    fun otherIncidentBoundsDoNotZoomWithoutExplicitSelectToken() {
        val plan = planMappingLocalLayerZoom(
            zoomToken = null,
            lastConsumedToken = null,
            hasUsableBounds = true,
            mapReady = true,
        )
        assertFalse(plan.shouldZoom)

        val explicit = planMappingLocalLayerZoom(
            zoomToken = 7L,
            lastConsumedToken = null,
            hasUsableBounds = true,
            mapReady = true,
        )
        assertTrue(explicit.shouldZoom)
    }

    @Test
    fun waitsForMapReadyBeforeConsumingZoomToken() {
        val waiting = planMappingLocalLayerZoom(
            zoomToken = 3L,
            lastConsumedToken = null,
            hasUsableBounds = true,
            mapReady = false,
        )
        assertFalse(waiting.shouldZoom)
        assertFalse(waiting.consumeToken)
    }

    @Test
    fun returnToDeviceGpsRecentersNowOrPendingNextFix() {
        assertEquals(
            DeviceGpsRecenterPlan(centerNow = true, pendingUntilFix = false, consumeRequest = true),
            planDeviceGpsRecenter(requestToken = 1L, lastConsumedToken = 0L, hasDeviceFix = true),
        )
        assertEquals(
            DeviceGpsRecenterPlan(centerNow = false, pendingUntilFix = true, consumeRequest = true),
            planDeviceGpsRecenter(requestToken = 2L, lastConsumedToken = 1L, hasDeviceFix = false),
        )
        assertFalse(shouldApplyPendingDeviceGpsRecenter(pending = true, hasDeviceFix = false))
        assertTrue(shouldApplyPendingDeviceGpsRecenter(pending = true, hasDeviceFix = true))
        assertEquals(
            DeviceGpsRecenterPlan(centerNow = false, pendingUntilFix = false, consumeRequest = false),
            planDeviceGpsRecenter(requestToken = 2L, lastConsumedToken = 2L, hasDeviceFix = true),
        )
    }
}
