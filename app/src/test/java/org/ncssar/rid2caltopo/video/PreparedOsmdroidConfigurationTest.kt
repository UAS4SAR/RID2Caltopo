package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class PreparedOsmdroidConfigurationTest {
    @Test fun mapConstructionPathGettersNeverProbeOrCreateDirectories() {
        val path = object : File("/unavailable/no-map-cache") {
            override fun exists(): Boolean = error("Unexpected filesystem probe")
            override fun mkdirs(): Boolean = error("Unexpected filesystem creation")
        }
        val configuration = PreparedOsmdroidConfiguration(path, path)
        repeat(100) {
            assertSame(path, configuration.osmdroidBasePath)
            assertSame(path, configuration.osmdroidTileCache)
            assertSame(path, configuration.getOsmdroidBasePath(null))
            assertSame(path, configuration.getOsmdroidTileCache(null))
        }
    }
}
