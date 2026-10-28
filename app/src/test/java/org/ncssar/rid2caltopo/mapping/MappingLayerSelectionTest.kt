package org.ncssar.rid2caltopo.mapping

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class MappingLayerSelectionTest {
    private class PreferencesContext : ContextWrapper(null) {
        val values=mutableMapOf<String,Any>()
        private val editor=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when(method.name) {
                "putString" -> { if(args[1]==null)values.remove(args[0]) else values[args[0] as String]=args[1];proxy }
                "apply" -> null
                else -> error("Unexpected preference edit ${method.name}")
            }
        } as SharedPreferences.Editor
        private val preferences=Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when(method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference read ${method.name}")
            }
        } as SharedPreferences
        override fun getSharedPreferences(name:String, mode:Int)=preferences
        override fun getFilesDir():File=error("Resolving selection must not scan imagery storage")
    }

    @Test fun selectedMapRemainsSelectedWithoutProbingItsImageryDirectory() {
        val context=PreferencesContext()
        val layer=MappingLayer("a817d2b0-bc06-4080-9f15-6fd4cd7e0c92","incident","Local map",
            "http://127.0.0.1/tiles/{Z}/{X}/{Y}.png",10,20)
        context.values["incident"]="[${layer.json()}]"
        context.values["selectedLocalLayer"]=layer.id
        assertEquals(layer,MappingLayer.selectedLocal(context))
        // Explicitly hiding a preview still removes it. Storage availability never changes it.
        MappingLayer.selectLocal(context,null)
        assertNull(MappingLayer.selectedLocal(context))
    }

    @Test fun staleSelectionDoesNotSubstituteSomeOtherSavedMap() {
        val context=PreferencesContext()
        context.values["incident"]="[]"
        context.values["selectedLocalLayer"]="a817d2b0-bc06-4080-9f15-6fd4cd7e0c92"
        assertNull(MappingLayer.selectedLocal(context))
        assertEquals("a817d2b0-bc06-4080-9f15-6fd4cd7e0c92",context.values["selectedLocalLayer"])
    }
}
