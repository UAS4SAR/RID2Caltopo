package org.ncssar.rid2caltopo.mapping

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PreferenceTypeSafetyTest {
    private class ThrowingPrefs(
        private val onRemove: () -> Unit = {},
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? =
            throw ClassCastException("boolean for $key")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw ClassCastException("int for $key")
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?): SharedPreferences.Editor {
                onRemove()
                return this
            }
            override fun clear() = this
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    @Test
    fun stringClearsBadTypeAndReturnsDefault() {
        val removed = AtomicBoolean(false)
        val prefs = ThrowingPrefs { removed.set(true) }
        assertNull(PreferenceTypeSafety.string(prefs, "selectedLocalLayer"))
        assertEquals("[]", PreferenceTypeSafety.string(prefs, "incident", "[]"))
        assertTrue(removed.get())
    }

    @Test
    fun stringSetClearsBadTypeAndReturnsEmpty() {
        val removed = AtomicBoolean(false)
        val prefs = ThrowingPrefs { removed.set(true) }
        assertTrue(PreferenceTypeSafety.stringSet(prefs, "hidden:x").isEmpty())
        assertTrue(removed.get())
    }

    @Test
    fun booleanAndIntClearBadTypeAndReturnDefault() {
        val removed = AtomicBoolean(false)
        val prefs = ThrowingPrefs { removed.set(true) }
        // ThrowingPrefs already throws ClassCast from getString/getStringSet; override via a proxy for bool/int.
        val typed = object : SharedPreferences by prefs {
            override fun getBoolean(key: String?, defValue: Boolean): Boolean =
                throw ClassCastException("string for $key")
            override fun getInt(key: String?, defValue: Int): Int =
                throw ClassCastException("string for $key")
            override fun edit(): SharedPreferences.Editor = prefs.edit()
        }
        assertEquals(true, PreferenceTypeSafety.boolean(typed, "offline_prep_usgs", true))
        assertEquals(12, PreferenceTypeSafety.int(typed, "offline_prep_usgs_min", 12))
        assertTrue(removed.get())
    }
}
