package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FlightPublicationLedgerTest {
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("flight-publication-ledger", ".json").also { it.writeText("{}") }
        FlightPublicationLedger.useFileForTesting(file)
    }

    @After
    fun tearDown() {
        FlightPublicationLedger.useFileForTesting(null)
        file.delete()
    }

    @Test
    fun declinedBlocksPublicationAndAwaitingMap() {
        FlightPublicationLedger.remember("flight-a", FlightPublicationLedger.Disposition.DECLINED)
        assertEquals(
            FlightPublicationLedger.Disposition.DECLINED,
            FlightPublicationLedger.disposition("flight-a"),
        )
        assertTrue(FlightPublicationLedger.blocksPublication("flight-a"))
        assertTrue(FlightPublicationLedger.blocksAwaitingMapPromotion("flight-a"))
        assertTrue(FlightPublicationLedger.isDeclinedOrSuppressed("flight-a"))
    }

    @Test
    fun suppressedBlocksAwaitingMap() {
        FlightPublicationLedger.remember("flight-b", FlightPublicationLedger.Disposition.SUPPRESSED)
        assertTrue(FlightPublicationLedger.blocksAwaitingMapPromotion("flight-b"))
        assertFalse(FlightPublicationLedger.blocksPublication("unknown"))
    }

    @Test
    fun publishedWinsOverSuppressed() {
        FlightPublicationLedger.remember("flight-c", FlightPublicationLedger.Disposition.SUPPRESSED)
        FlightPublicationLedger.remember("flight-c", FlightPublicationLedger.Disposition.PUBLISHED)
        assertEquals(
            FlightPublicationLedger.Disposition.PUBLISHED,
            FlightPublicationLedger.disposition("flight-c"),
        )
        FlightPublicationLedger.remember("flight-c", FlightPublicationLedger.Disposition.DECLINED)
        assertEquals(
            FlightPublicationLedger.Disposition.PUBLISHED,
            FlightPublicationLedger.disposition("flight-c"),
        )
    }

    @Test
    fun survivesReload() {
        FlightPublicationLedger.remember("flight-d", FlightPublicationLedger.Disposition.LOCAL_ARCHIVED)
        FlightPublicationLedger.useFileForTesting(file)
        assertEquals(
            FlightPublicationLedger.Disposition.LOCAL_ARCHIVED,
            FlightPublicationLedger.disposition("flight-d"),
        )
    }
}
