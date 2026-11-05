package org.ncssar.rid2caltopo.video.mapcache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TileCacheIndexStampTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun stampLivesBesideTheDurableIndexAndMatchesCountAndBytes() {
        val files = folder.root
        val database = TileCacheIndexStamp.databaseFile(files)
        val stamp = TileCacheIndexStamp.stampFile(files)
        assertTrue(database.path.contains("map-cache-index"))
        assertFalse(database.path.contains("temporary-operational"))
        assertEquals(database.parent, stamp.parent)
        assertFalse(TileCacheIndexStamp.matches(null, TileCacheIndexStamp.Value(1, 2)))
        assertTrue(TileCacheIndexStamp.matches(TileCacheIndexStamp.Value(12, 34), TileCacheIndexStamp.Value(12, 34)))
        assertFalse(TileCacheIndexStamp.matches(TileCacheIndexStamp.Value(12, 34), TileCacheIndexStamp.Value(13, 34)))
    }

    @Test fun updateMergesNamespacesWithoutDroppingThePreviousOne() {
        val stamp = File(folder.root, "map-cache-index/saf_cache_index.stamp")
        TileCacheIndexStamp.update(stamp, "imagery", TileCacheIndexStamp.Value(10, 100))
        TileCacheIndexStamp.update(stamp, "terrain", TileCacheIndexStamp.Value(2, 50))
        val read = TileCacheIndexStamp.read(stamp)
        assertEquals(TileCacheIndexStamp.Value(10, 100), read["imagery"])
        assertEquals(TileCacheIndexStamp.Value(2, 50), read["terrain"])
        assertNull(read["missing"])
    }
}
