package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DEM altitude must work without SplitMapPane, and must not wait unbounded when the
 * GeoTIFF catalog has not been scanned yet.
 */
class DemCatalogIndependenceTest {
    private fun stubContext(): Context {
        val root = File(System.getProperty("java.io.tmpdir"), "dem-catalog-independence-${System.nanoTime()}")
        root.mkdirs()
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
    }

    @Test
    fun demSourceWorksWithoutSplitMapPane() {
        val source = GeoTiffDemSource(stubContext())
        // Same warm path DemElevationService.warmCatalogAndIndex uses — no MapPane.
        source.warmCatalog()
        assertTrue(source.isCatalogReady())
        // Empty archive → miss, but the call must complete without SplitMapPane.
        assertNull(source.sample(39.154, -121.132))
    }

    @Test
    fun sampleWhileCatalogNotReadyReturnsImmediately() {
        val source = GeoTiffDemSource(stubContext())
        assertFalse(source.isCatalogReady())
        val started = System.nanoTime()
        val sample = source.sample(39.154, -121.132)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertNull(sample)
        assertTrue(
            "catalog-not-ready sample must return immediately, elapsedMs=$elapsedMs",
            elapsedMs < 500L,
        )
    }
}
