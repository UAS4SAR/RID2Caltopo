package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredFlightDedupTest {
    private val serial = "1581F8HGX255S00A0FZT"
    private val savedName = "$serial-10Nov2026-170808-PST-0800.json"

    @Test
    fun sameFlightUnderAnotherNameIsRetiredWithoutASecondFile() {
        val saved = flight(longArrayOf(1_000, 2_000, 3_000))
        val decision = RecoveredFlightDedup.decide(serial.lowercase(), longArrayOf(1_000, 2_000, 3_000), listOf(saved))
        assertEquals(RecoveredFlightDedup.Kind.RETIRE, decision.kind)
        assertEquals(savedName, decision.fileName)
    }

    @Test
    fun journalPointsMissingFromTheSavedFileMergeIntoIt() {
        val saved = flight(longArrayOf(2_000, 3_000))
        val decision = RecoveredFlightDedup.decide(serial, longArrayOf(1_000, 2_000, 3_000), listOf(saved))
        assertEquals(RecoveredFlightDedup.Kind.MERGE, decision.kind)
        assertEquals(savedName, decision.fileName)
        assertEquals(1, decision.missingTimesMs.size)
        assertEquals(1_000L, decision.missingTimesMs[0])
    }

    @Test
    fun aLaterFlightOfTheSameDroneIsNotTheSameFile() {
        val saved = flight(longArrayOf(1_000, 2_000, 3_000))
        val decision = RecoveredFlightDedup.decide(serial, longArrayOf(20_000, 21_000, 22_000), listOf(saved))
        assertEquals(RecoveredFlightDedup.Kind.WRITE_NEW, decision.kind)
    }

    @Test
    fun anotherDroneDoesNotMatch() {
        val saved = flight(longArrayOf(1_000, 2_000, 3_000))
        val decision = RecoveredFlightDedup.decide("OTHERDRONE", longArrayOf(1_000, 2_000, 3_000), listOf(saved))
        assertEquals(RecoveredFlightDedup.Kind.WRITE_NEW, decision.kind)
    }

    @Test
    fun mergeAddsOnlyTheMissingPoint() {
        val original = geoJson(2_000, 3_000)
        val journal = JSONArray().put(point(1_000)).put(point(2_000)).put(point(3_000))
        val merged = String(RecoveredFlightDedup.mergeGeoJson(original.toByteArray(), journal)!!)
        assertTrue(merged.contains("\"1000\""))
        assertTrue(merged.contains("\"2000\""))
        assertTrue(merged.contains("\"3000\""))
        assertEquals(1, merged.split("\"2000\"").size - 1)
    }

    @Test
    fun round11JournalDoesNotBecomeASecondFile() {
        val root = java.io.File.createTempFile("journals-dedup", null).also {
            assertTrue(it.delete())
            assertTrue(it.mkdirs())
        }
        val ledger = java.io.File.createTempFile("ledger-dedup", ".json").also { it.writeText("{}") }
        val awaiting = java.io.File.createTempFile("awaiting-dedup", ".json").also { it.writeText("[]") }
        val saved = java.io.File.createTempFile("saved-170808", ".json")
        saved.writeText(geoJson(2_000, 3_000, 4_000))
        WaypointTrackJournal.setRootForTesting(root)
        AwaitingMapFlights.useFileForTesting(awaiting)
        FlightPublicationLedger.useFileForTesting(ledger)
        try {
            val flightId = "flight-170949"
            WaypointTrackJournal.ensureOpen(flightId, serial, "1sar7DjMtrc4td_170808Nov10", "", "team", 1_000L)
            WaypointTrackJournal.appendPoint(flightId, 39.15, -121.13, 521.0, 1_000L, 1_000L, "rid", true)
            WaypointTrackJournal.appendPoint(flightId, 39.15, -121.13, 521.0, 2_000L, 2_000L, "rid", true)
            WaypointTrackJournal.appendPoint(flightId, 39.15, -121.13, 521.0, 3_000L, 3_000L, "rid", true)
            WaypointTrackJournal.appendPoint(flightId, 39.15, -121.13, 521.0, 4_000L, 4_000L, "rid", true)
            WaypointTrackJournal.drainForTesting()
            WaypointTrackJournal.setSavedFlightLookupForTesting(object : RecoveredFlightDedup.Lookup {
                override fun flights() = listOf(flight(longArrayOf(2_000, 3_000, 4_000)))
                override fun mergeInto(dayDirectory: String, fileName: String, journalPoints: JSONArray): Boolean {
                    val merged = RecoveredFlightDedup.mergeGeoJson(saved.readBytes(), journalPoints)!!
                    saved.writeBytes(merged)
                    val text = saved.readText()
                    return listOf("1000", "2000", "3000", "4000").all { text.contains("\"$it\"") }
                }
            })

            WaypointTrackJournal.recoverUnfinished()

            assertFalse(java.io.File(root, "$flightId.ndjson").exists())
            assertTrue(AwaitingMapFlights.pending().isEmpty())
            val text = saved.readText()
            assertTrue(text.contains("\"1000\""))
            assertTrue(text.contains("\"4000\""))
            assertTrue(saved.exists())
        } finally {
            WaypointTrackJournal.setSavedFlightLookupForTesting(null)
            WaypointTrackJournal.setRootForTesting(null)
            AwaitingMapFlights.useFileForTesting(null)
            FlightPublicationLedger.useFileForTesting(null)
            root.deleteRecursively()
            ledger.delete()
            awaiting.delete()
            saved.delete()
        }
    }

    private fun flight(times: LongArray) =
        RecoveredFlightDedup.SavedFlight(serial, "tracks-10Nov2026", savedName, times)

    private fun point(time: Long) =
        JSONArray().put("-121.130000").put("39.150000").put("521").put(time.toString())

    private fun geoJson(vararg times: Long): String {
        val coordinates = JSONArray()
        times.forEach { coordinates.put(point(it)) }
        return JSONArray().let {
            "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"title\":\"saved\",\"r2c_prop\":{\"rid\":\"$serial\"}},\"geometry\":{\"type\":\"LineString\",\"coordinates\":$coordinates}}]}"
        }
    }
}
