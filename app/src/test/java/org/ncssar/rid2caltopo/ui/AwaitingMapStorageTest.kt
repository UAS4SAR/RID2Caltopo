package org.ncssar.rid2caltopo.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AwaitingMapStorageTest {
    @Test fun archiveRecoveryFailureReturnsToThePanelWithoutCrashingItsCoroutine() = runBlocking {
        val error = IllegalStateException("Archive recovery requires its verified staging file")
        val result = awaitingMapStorage<List<String>> { throw error }
        assertSame(error, result.exceptionOrNull())
    }

    @Test fun storageLoadLeavesTheCallingThreadBeforeWorkStarts() = runBlocking {
        val caller = Thread.currentThread()
        val result = awaitingMapStorage {
            assertNotSame(caller, Thread.currentThread())
            listOf("retained flight")
        }
        assertEquals(listOf("retained flight"), result.getOrThrow())
    }

    @Test fun cancellingThePanelDoesNotBecomeAnArchiveError() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                awaitingMapStorage<Unit> { throw CancellationException("panel closed") }
            }
        }
    }
}
