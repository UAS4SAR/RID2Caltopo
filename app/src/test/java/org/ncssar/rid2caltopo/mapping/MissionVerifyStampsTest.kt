package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class MissionVerifyStampsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun roundTripAndSkipUnchanged() {
        val filesDir = folder.root
        val id = UUID.randomUUID().toString()
        val mission = folder.newFolder("mapping-missions", id)
        folder.newFolder("mapping-missions", id, "inventory")
        File(mission, "mission.json").writeText("{}")
        val fp = MappingLibraryIndex.MissionFingerprint.of(mission)
        MissionVerifyStamps.write(filesDir, mapOf(id to fp))
        assertTrue(MissionVerifyStamps.unchanged(mission, MissionVerifyStamps.load(filesDir)))
        val plan = MissionVerifyStamps.plan(listOf(mission), MissionVerifyStamps.load(filesDir))
        assertEquals(1, plan.skip.size)
        assertTrue(plan.verify.isEmpty())
        assertEquals("Checking mapping storage (1/1)…", plan.message(1))
        File(mission, "mission.json").writeText("{\"title\":\"x\"}")
        assertFalse(MissionVerifyStamps.unchanged(mission, MissionVerifyStamps.load(filesDir)))
    }

    @Test fun unchangedSkipsInventoryListingWhenDirectoryStampMatches() {
        val filesDir = folder.root
        val id = UUID.randomUUID().toString()
        val mission = folder.newFolder("mapping-missions", id)
        val inventory = folder.newFolder("mapping-missions", id, "inventory")
        File(mission, "mission.json").writeText("{}")
        File(inventory, "a.json").writeText("{\"a\":1}")
        val fp = MappingLibraryIndex.MissionFingerprint.of(mission)
        MissionVerifyStamps.write(filesDir, mapOf(id to fp))
        val missionStamp = mission.lastModified()
        val inventoryStamp = inventory.lastModified()
        File(inventory, "b.json").writeText("{\"b\":2}")
        mission.setLastModified(missionStamp)
        inventory.setLastModified(inventoryStamp)
        assertTrue(MissionVerifyStamps.unchanged(mission, MissionVerifyStamps.load(filesDir)))
        assertEquals(1, MissionVerifyStamps.plan(listOf(mission), MissionVerifyStamps.load(filesDir)).skip.size)
    }

    @Test fun skipOriginalsWalkRequiresMatchingPositiveStamp() {
        assertFalse(MissionVerifyStamps.skipOriginalsWalk(null, 10L))
        assertFalse(MissionVerifyStamps.skipOriginalsWalk(10L, 11L))
        assertFalse(MissionVerifyStamps.skipOriginalsWalk(0L, 0L))
        assertTrue(MissionVerifyStamps.skipOriginalsWalk(10L, 10L))
    }
}
