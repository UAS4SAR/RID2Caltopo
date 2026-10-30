package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightStorageTransientCheckTest {
    @Test fun projectionChangedAndMissingFileRacesAreTransient() {
        assertTrue(FlightStorage.isTransientStorageCheckFailure(
            IllegalStateException("Existing operational projection changed during preparation; originals were retained.")))
        assertTrue(FlightStorage.isTransientStorageCheckFailure(
            IllegalStateException("Failed to determine if clues/x.jpg is child of archive: java.io.FileNotFoundException: Missing file for clues/x.jpg")))
        assertTrue(FlightStorage.isTransientStorageCheckFailure(
            IllegalStateException("Archive file is unavailable: application-logs/operational/clues/x.jpg")))
        assertFalse(FlightStorage.isTransientStorageCheckFailure(
            IllegalStateException("Archive Dir is unavailable; reconnect its storage.")))
        assertFalse(FlightStorage.isTransientStorageCheckFailure(
            IllegalStateException("Choose or reconnect Archive Dir to use retained operational data.")))
    }
}
