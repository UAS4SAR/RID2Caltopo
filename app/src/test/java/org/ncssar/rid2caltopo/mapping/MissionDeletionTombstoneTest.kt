package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class MissionDeletionTombstoneTest {
    @get:Rule val folder = TemporaryFolder()

    private fun mission(photos: Int = 1): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        val dir = File(folder.root, "mapping-missions/$id").apply { mkdirs() }
        File(dir, "mission.json").writeText(JSONObject()
            .put("missionId", id).put("title", "Trial").put("createdAt", "2026-10-12T00:00:00Z")
            .put("assignmentId", "a1").put("incidentId", "inc").put("profile", "daylight-rgb").toString())
        File(dir, "inventory").mkdirs(); File(dir, "originals").mkdirs()
        repeat(photos) { index ->
            val digest = "%064x".format(index + 1)
            File(dir, "originals/$digest.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(dir, "inventory/$digest.json").writeText(JSONObject().put("sha256", digest).put("name", "DJI_$index.jpg")
                .put("complete", true).put("sizeBytes", 3).toString())
        }
        return id to dir
    }

    private class IndexContext(private val root: File) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext() = this
        override fun getExternalFilesDirs(type: String?) = emptyArray<File>()
        override fun getSharedPreferences(name: String, mode: Int) =
            object : android.content.SharedPreferences by emptyPrefs() {}
        private fun emptyPrefs(): android.content.SharedPreferences {
            val editor = object : android.content.SharedPreferences.Editor {
                override fun putString(k: String?, v: String?) = this
                override fun putStringSet(k: String?, v: MutableSet<String>?) = this
                override fun putInt(k: String?, v: Int) = this
                override fun putLong(k: String?, v: Long) = this
                override fun putFloat(k: String?, v: Float) = this
                override fun putBoolean(k: String?, v: Boolean) = this
                override fun remove(k: String?) = this
                override fun clear() = this
                override fun commit() = true
                override fun apply() {}
            }
            return object : android.content.SharedPreferences {
                override fun getAll() = emptyMap<String, Any>()
                override fun getString(key: String?, defValue: String?) = defValue
                override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
                override fun getInt(key: String?, defValue: Int) = defValue
                override fun getLong(key: String?, defValue: Long) = defValue
                override fun getFloat(key: String?, defValue: Float) = defValue
                override fun getBoolean(key: String?, defValue: Boolean) = defValue
                override fun contains(key: String?) = false
                override fun edit() = editor
                override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
                override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            }
        }
    }

    @Test fun rememberIsIdempotent() {
        val id = UUID.randomUUID().toString()
        synchronized(MissionDeletionTombstone.lock) {
            assertTrue(MissionDeletionTombstone.rememberLocked(folder.root, id))
            assertFalse(MissionDeletionTombstone.rememberLocked(folder.root, id))
        }
        assertEquals(setOf(id), MissionDeletionTombstone.load(folder.root))
    }

    @Test fun deleteRecordsTombstoneAndStaleRefreshHidesMission() {
        val context = IndexContext(folder.root)
        val (id, dir) = mission()
        MappingLibraryIndex.refresh(context, null)
        assertTrue(MappingLibraryIndex.load(folder.root)!!.missions.containsKey(id))
        MappingFlightDeletion.recordFullDeletion(folder.root, id)
        assertTrue(MissionDeletionTombstone.contains(folder.root, id))
        assertFalse(MappingLibraryIndex.load(folder.root)!!.missions.containsKey(id))
        assertTrue(dir.isDirectory)
        MappingLibraryListing.areaBusyProbe = { true }
        try {
            val stale = MappingLibraryIndex.refresh(context, null)
            assertTrue(stale.stale)
            assertTrue(stale.entries.none { it.flight?.id == id })
            assertFalse(stale.index.missions.containsKey(id))
        } finally {
            MappingLibraryListing.areaBusyProbe = null
        }
        assertTrue(dir.deleteRecursively())
        val after = MappingLibraryIndex.refresh(context, null)
        assertTrue(after.entries.none { it.flight?.id == id })
    }

    @Test fun originalsFailureStopsBeforeRecordRemoval() {
        val id = UUID.randomUUID().toString()
        val dir = File(folder.root, "mapping-missions/$id").apply { mkdirs() }
        File(dir, "mission.json").writeText(JSONObject().put("missionId", id).toString())
        val originals = File(dir, "originals").apply { mkdirs() }
        val photo = File(originals, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg")
        photo.writeBytes(byteArrayOf(1))
        assertTrue(originals.setWritable(false))
        try {
            val failure = MappingFlightDeletion.removeOriginalsFirst(folder.root, emptyList(), id, managed = false)
            assertNotNull(failure)
            assertTrue(photo.exists())
            assertTrue(File(dir, "mission.json").exists())
            assertFalse(MissionDeletionTombstone.contains(folder.root, id))
        } finally {
            originals.setWritable(true)
        }
    }

    @Test fun selfHealPrunesGhostStampWithoutOriginalsAndKeepsMissionWithOriginals() {
        val context = IndexContext(folder.root)
        val (alive, aliveDir) = mission(photos = 1)
        MappingLibraryIndex.refresh(context, null)
        val ghost = UUID.randomUUID().toString()
        MissionVerifyStamps.write(folder.root, MissionVerifyStamps.load(folder.root) + mapOf(
            alive to MappingLibraryIndex.MissionFingerprint.of(aliveDir),
            ghost to MappingLibraryIndex.MissionFingerprint.of(aliveDir),
        ))
        val refreshed = MappingLibraryIndex.refresh(context, null)
        assertTrue(refreshed.index.missions.containsKey(alive))
        assertFalse(refreshed.index.missions.containsKey(ghost))
        assertFalse(MissionVerifyStamps.load(folder.root).containsKey(ghost))
        assertTrue(MissionProjectionGuard.hasOriginals(aliveDir))
        // Alive mission with originals must never be pruned while its folder remains.
        assertTrue(MappingLibraryIndex.missionHasOriginalsAnywhere(folder.root, emptyList(), alive))
        assertTrue(MissionVerifyStamps.load(folder.root).containsKey(alive) ||
            refreshed.index.missions.containsKey(alive))
    }

    @Test fun tombstonedIdNeverReaddedAfterFolderGone() {
        val context = IndexContext(folder.root)
        val (id, dir) = mission()
        MappingLibraryIndex.refresh(context, null)
        MappingFlightDeletion.recordFullDeletion(folder.root, id)
        assertTrue(dir.deleteRecursively())
        val refreshed = MappingLibraryIndex.refresh(context, null)
        assertFalse(refreshed.index.missions.containsKey(id))
        assertTrue(MissionDeletionTombstone.contains(folder.root, id))
    }
}
