package org.ncssar.rid2caltopo.video

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.video.mapcache.CaltopoIconCacheService
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache

class CacheConstructionTest {
    @Test fun screenCacheConstructionDoesNotWaitForMaintenanceLock() {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(System.getProperty("java.io.tmpdir"))
        }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val maintenance = threads.submit {
                synchronized(UnifiedMapCache.lock) {
                    held.countDown()
                    release.await()
                }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val screenConstruction = threads.submit {
                DemElevationService(context)
                CaltopoIconCacheService(context)
                TileDiskCacheWriter(context)
            }
            // A blocked factory would time out here while the maintenance lock is held.
            screenConstruction.get(5, TimeUnit.SECONDS)
            release.countDown()
            maintenance.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }
}
