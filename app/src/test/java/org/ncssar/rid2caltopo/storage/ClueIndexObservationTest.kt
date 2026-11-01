package org.ncssar.rid2caltopo.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClueIndexObservationTest {
    @Test
    fun idleStorageAlwaysPublishes() {
        assertTrue(ClueIndexObservation.shouldPublish(previousCount = 6, newCount = 0, prepareInProgress = false))
        assertTrue(ClueIndexObservation.shouldPublish(previousCount = 0, newCount = 6, prepareInProgress = false))
    }

    @Test
    fun prepareKeepsLastGoodWhenReadGoesEmpty() {
        assertFalse(ClueIndexObservation.shouldPublish(previousCount = 6, newCount = 0, prepareInProgress = true))
    }

    @Test
    fun prepareKeepsLastGoodWhenReadGoesPartial() {
        assertFalse(ClueIndexObservation.shouldPublish(previousCount = 6, newCount = 3, prepareInProgress = true))
    }

    @Test
    fun prepareAllowsEqualOrRicherReads() {
        assertTrue(ClueIndexObservation.shouldPublish(previousCount = 6, newCount = 6, prepareInProgress = true))
        assertTrue(ClueIndexObservation.shouldPublish(previousCount = 6, newCount = 7, prepareInProgress = true))
    }

    @Test
    fun prepareAllowsFirstPopulationFromEmpty() {
        assertTrue(ClueIndexObservation.shouldPublish(previousCount = 0, newCount = 0, prepareInProgress = true))
        assertTrue(ClueIndexObservation.shouldPublish(previousCount = 0, newCount = 4, prepareInProgress = true))
    }
}
