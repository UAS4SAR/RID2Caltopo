package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ncssar.rid2caltopo.storage.OperationalStorage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plain JVM has no main Looper, so [OperationalStorage.worker] returns early and misses
 * UI-thread storage bugs. These tests install the worker-assert seam and invoke Don't Publish /
 * discard / local-archive paths on a designated "UI" thread, asserting no storage worker call
 * happens on that thread.
 */
class MainThreadPublicationStorageTest {
    private lateinit var ledgerFile: File
    private lateinit var awaitingFile: File
    private val uiThread = AtomicBoolean(false)
    private val storageCallsOnUi = AtomicInteger(0)

    @Before
    fun setUp() {
        ledgerFile = File.createTempFile("main-ledger", ".json").also { it.writeText("{}") }
        awaitingFile = File.createTempFile("main-awaiting", ".json").also { it.writeText("[]") }
        FlightPublicationLedger.useFileForTesting(ledgerFile)
        AwaitingMapFlights.useFileForTesting(awaitingFile)
        storageCallsOnUi.set(0)
        OperationalStorage.installWorkerAssertForTesting {
            if (uiThread.get()) storageCallsOnUi.incrementAndGet()
        }
    }

    @After
    fun tearDown() {
        OperationalStorage.installWorkerAssertForTesting(null)
        FlightPublicationLedger.useFileForTesting(null)
        AwaitingMapFlights.useFileForTesting(null)
        ledgerFile.delete()
        awaitingFile.delete()
    }

    private fun onUiThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        val error = AtomicBoolean(false)
        Thread({
            uiThread.set(true)
            try {
                block()
            } catch (_: Throwable) {
                error.set(true)
            } finally {
                uiThread.set(false)
                done.countDown()
            }
        }, "test-ui-thread").also { it.isDaemon = true; it.start() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertFalse(error.get())
    }

    @Test
    fun dontPublishRememberOnUiDoesNotHitOperationalStorageWorker() {
        onUiThread {
            FlightPublicationLedger.remember("flight-main", FlightPublicationLedger.Disposition.DECLINED)
            assertTrue(FlightPublicationLedger.blocksPublication("flight-main"))
        }
        assertFalse(
            "Don't Publish must not call OperationalStorage.worker on the UI thread",
            storageCallsOnUi.get() > 0,
        )
    }

    @Test
    fun discardOnUiDoesNotHitOperationalStorageWorker() {
        onUiThread {
            AwaitingMapFlights.discard("missing-flight")
            assertTrue(FlightPublicationLedger.blocksPublication("missing-flight"))
        }
        assertFalse(
            "Discard must not call OperationalStorage.worker on the UI thread",
            storageCallsOnUi.get() > 0,
        )
    }

    @Test
    fun plainFileReplaceLocalOnUiDoesNotHitWorker() {
        val target = File.createTempFile("main-replace", ".json")
        try {
            onUiThread {
                PlainFileSafeWriter.replaceLocal(target, """{"ok":true}""".toByteArray()) { it.isNotEmpty() }
            }
            assertFalse(
                "replaceLocal must not invoke OperationalStorage.worker",
                storageCallsOnUi.get() > 0,
            )
        } finally {
            target.delete()
        }
    }

    @Test
    fun unknownConfirmationOnUiDoesNotHitWorker() {
        onUiThread {
            runCatching { CaltopoClient.ResetPersistedClientState() }
            CaltopoClient.SaveDroneSpecUnknownConfirmation("MAINTHREADRID1")
        }
        assertFalse(
            "SaveDroneSpecUnknownConfirmation must not call OperationalStorage.worker on the UI thread",
            storageCallsOnUi.get() > 0,
        )
    }

    @Test
    fun localArchivedRememberOnUiDoesNotHitWorker() {
        onUiThread {
            FlightPublicationLedger.remember("flight-local", FlightPublicationLedger.Disposition.LOCAL_ARCHIVED)
            assertTrue(FlightPublicationLedger.blocksPublication("flight-local"))
        }
        assertFalse(storageCallsOnUi.get() > 0)
    }

    @Test
    fun confirmDispositionOnUiDoesNotHitWorker() {
        onUiThread {
            FlightPublicationLedger.remember("flight-confirm", FlightPublicationLedger.Disposition.PUBLISHED)
            assertTrue(FlightPublicationLedger.blocksPublication("flight-confirm"))
        }
        assertFalse(storageCallsOnUi.get() > 0)
    }
}
