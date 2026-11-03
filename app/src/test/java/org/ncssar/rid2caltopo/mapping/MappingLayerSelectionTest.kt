package org.ncssar.rid2caltopo.mapping

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MappingLayerSelectionTest {
    @get:Rule val folder = TemporaryFolder()

    private class PreferencesContext(private val files: File) : ContextWrapper(null) {
        val values = mutableMapOf<String, Any>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { if (args[1] == null) values.remove(args[0] as String) else values[args[0] as String] = args[1]; proxy }
                "remove" -> { values.remove(args[0] as String); proxy }
                "apply" -> null
                else -> error("Unexpected preference edit ${method.name}")
            }
        } as SharedPreferences.Editor
        private val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
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

    @Test fun selectedMapRemainsSelectedWhenRegisteredWithTiles() {
        val context = PreferencesContext(folder.root)
        val layer = MappingLayer("a817d2b0-bc06-4080-9f15-6fd4cd7e0c92", "incident", "Local map",
            "http://127.0.0.1/tiles/{Z}/{X}/{Y}.png", 10, 20)
        context.values["incident"] = "[${layer.json()}]"
        context.values["selectedLocalLayer"] = layer.id
        withTiles(context, layer)
        assertEquals(layer, MappingLayer.selectedLocal(context))
        assertTrue(MappingLayer.selectLocal(context, null))
        assertNull(MappingLayer.selectedLocal(context))
    }

    @Test fun selectLocalRefusesUnregisteredOrMissingTiles() {
        val context = PreferencesContext(folder.root)
        val layer = MappingLayer("a817d2b0-bc06-4080-9f15-6fd4cd7e0c92", "incident", "Local map",
            "http://127.0.0.1/tiles/{Z}/{X}/{Y}.png", 10, 20)
        assertFalse(MappingLayer.selectLocal(context, layer))
        context.values["incident"] = "[${layer.json()}]"
        assertFalse(MappingLayer.selectLocal(context, layer))
        withTiles(context, layer)
        assertTrue(MappingLayer.selectLocal(context, layer))
        assertEquals(layer.id, context.values["selectedLocalLayer"])
    }

    @Test fun staleSelectionClearsWhenUnregistered() {
        val context = PreferencesContext(folder.root)
        context.values["incident"] = "[]"
        context.values["selectedLocalLayer"] = "a817d2b0-bc06-4080-9f15-6fd4cd7e0c92"
        assertNull(MappingLayer.selectedLocal(context))
        assertNull(context.values["selectedLocalLayer"])
    }

    @Test fun staleSelectionClearsWhenTilesMissing() {
        val context = PreferencesContext(folder.root)
        val layer = MappingLayer("a817d2b0-bc06-4080-9f15-6fd4cd7e0c92", "incident", "Local map",
            "http://127.0.0.1/tiles/{Z}/{X}/{Y}.png", 10, 20)
        context.values["incident"] = "[${layer.json()}]"
        context.values["selectedLocalLayer"] = layer.id
        File(context.filesDir, "mapping-layers/${layer.id}").mkdirs()
        File(context.filesDir, "mapping-layers/${layer.id}/layer.json").writeText(layer.json().toString())
        assertNull(MappingLayer.selectedLocal(context))
        assertNull(context.values["selectedLocalLayer"])
    }
}
