package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ManagedVideoRecordingCatalogThreadingTest {
    @Test fun uiInventoryLookupDoesNotWaitForCatalogDiskLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            // Catalog scans hold this monitor while reading files and metadata.
            workers.submit {
                synchronized(ManagedVideoSessionRecordingCatalog) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val lookup = workers.submit<Boolean> {
                ManagedVideoSessionRecordingCatalog.cachedSnapshot("qualification").isEmpty()
            }
            assertTrue(lookup.get(500, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
