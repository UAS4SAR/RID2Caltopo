package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.video.AndroidClueRecord
import org.ncssar.rid2caltopo.video.AndroidClueStore
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class AwaitingMapFlightReviewTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val start = 1_790_553_873_000L

    @After fun reset() { AwaitingMapFlights.useFileForTesting(null) }

    /** Journal points use WaypointTrack's string format: [lon, lat, alt, timeMs]. */
    private fun point(time: Long, lat: Double, lon: Double) = JSONArray()
        .put(String.format(java.util.Locale.US, "%.6f", lon)).put(String.format(java.util.Locale.US, "%.6f", lat))
        .put("100").put(time.toString())

    private fun points(start: Long = this.start, seconds: Long = 754) = JSONArray()
        .put(point(start, 39.0, -121.0))
        .put(point(start + seconds * 500, 39.01, -120.99))
        .put(point(start + seconds * 1_000, 39.005, -120.995))

    private fun flight(id: String = "target", remote: String = "RID-1", label: String = "1SAR7Db150Brdg_0641", start: Long = this.start) =
        JSONObject().put("id", id).put("remote", remote).put("label", label).put("points", points(start)).put("finished", true)

    @Test fun insideTheBoundingBoxIsReportedAsWithinTheFlightArea() {
        val box = AwaitingMapBoundingBox.ofFlight(flight())
        assertEquals(AwaitingMapBoundingBox(39.0, 39.01, -121.0, -120.99), box)
        assertEquals(AwaitingMapFlightProximity.Inside, AwaitingMapFlightProximity.forFlight(flight(), 39.004, -120.996))
        assertEquals(AwaitingMapFlightProximity.Inside, AwaitingMapFlightProximity.forFlight(flight(), 39.0, -121.0))
        assertEquals("You are within this flight's area", AwaitingMapFlightText.location(AwaitingMapFlightProximity.Inside))
    }

    @Test fun edgeDistanceUsesTheNearestPointOnTheBox() {
        val proximity = AwaitingMapFlightProximity.forFlight(flight(), 39.02, -120.995) as AwaitingMapFlightProximity.Away
        val expected = RidGeometry.relativePosition(39.02, -120.995, 39.01, -120.995)!!
        assertEquals(expected.distanceMeters, proximity.distanceMeters, 0.001)
        assertEquals(1_111.95, proximity.distanceMeters, 1.0)
        assertEquals(180, proximity.bearingDegrees)
        assertEquals("S", proximity.cardinal)
        assertEquals("3650 ft from current location at bearing 180° S", AwaitingMapFlightText.location(proximity))
    }

    @Test fun cornerDistanceUsesTheNearestCornerNotTheCentroid() {
        val proximity = AwaitingMapFlightProximity.forFlight(flight(), 38.99, -121.01) as AwaitingMapFlightProximity.Away
        val corner = RidGeometry.relativePosition(38.99, -121.01, 39.0, -121.0)!!
        val centroid = RidGeometry.relativePosition(38.99, -121.01, 39.005, -120.995)!!
        assertEquals(corner.distanceMeters, proximity.distanceMeters, 0.001)
        assertEquals(corner.bearingDegrees.roundToInt(), proximity.bearingDegrees)
        assertEquals("NE", proximity.cardinal)
        assertTrue(proximity.distanceMeters < centroid.distanceMeters)
    }

    @Test fun geometryMatchesTheAppleFormula() {
        // 0.01 degrees of latitude with the Apple mean radius (6,371,008.8 m).
        assertEquals(1_111.95, RidGeometry.relativePosition(39.0, -121.0, 39.01, -121.0)!!.distanceMeters, 0.01)
        val radians = Math.toRadians(32.0)
        val southwest = RidGeometry.relativePosition(39.0, -121.0, 39 - 0.01 * cos(radians),
            -121 - 0.01 * sin(radians) / cos(Math.toRadians(39.0)))!!
        assertEquals(212, southwest.bearingDegrees.roundToInt())
        assertEquals(null, RidGeometry.relativePosition(Double.NaN, 0.0, 0.0, 0.0))
    }

    @Test fun sixteenPointCardinalBoundaries() {
        val cases = listOf(0.0 to "N", 11.24 to "N", 11.25 to "NNE", 47.0 to "NE", 90.0 to "E", 191.25 to "SSW",
            212.0 to "SSW", 225.0 to "SW", 337.5 to "NNW", 348.74 to "NNW", 348.75 to "N", 359.9 to "N",
            360.0 to "N", -22.5 to "NNW", Double.NaN to "N")
        cases.forEach { (bearing, name) -> assertEquals("$bearing", name, RidGeometry.cardinalDirection16(bearing)) }
    }

    @Test fun distanceFormattingSwitchesFromFeetToMilesAtOneMile() {
        assertEquals("420 ft", AwaitingMapFlightText.distance(128.016))
        assertEquals("0 ft", AwaitingMapFlightText.distance(1.4))
        assertEquals("430 ft", AwaitingMapFlightText.distance(129.6))
        assertEquals("5280 ft", AwaitingMapFlightText.distance(1_609.0))
        assertEquals("1.0 mi", AwaitingMapFlightText.distance(1_609.344))
        assertEquals("1.3 mi", AwaitingMapFlightText.distance(1_609.344 * 1.3))
        assertEquals("12.5 mi", AwaitingMapFlightText.distance(1_609.344 * 12.46))
    }

    @Test fun durationCluePhotoAndDiscardWording() {
        assertEquals("Duration 12m 34s", AwaitingMapFlightText.duration(754_000))
        assertEquals("Duration 0m 07s", AwaitingMapFlightText.duration(7_400))
        assertEquals("Duration 1h 02m 05s", AwaitingMapFlightText.duration(3_725_000))
        assertEquals("0 clue photos", AwaitingMapFlightText.cluePhotos(0))
        assertEquals("1 clue photo", AwaitingMapFlightText.cluePhotos(1))
        assertEquals("This permanently deletes the track from this device.", AwaitingMapFlightText.discardMessage(0))
        assertEquals("This permanently deletes the track and 3 clue photos from this device.", AwaitingMapFlightText.discardMessage(3))
        assertEquals("1 FLIGHT ON THIS DEVICE", AwaitingMapFlightText.flightCountHeader(1))
        assertEquals("3 FLIGHTS ON THIS DEVICE", AwaitingMapFlightText.flightCountHeader(3))
        assertEquals("Distances from your current location · GPS ±16 ft · 7:03 AM", AwaitingMapFlightText.locationSummary(4.9, "7:03 AM"))
        assertEquals("Distances from your current location · 7:03 AM", AwaitingMapFlightText.locationSummary(null, "7:03 AM"))
    }

    @Test fun clueWindowSpansFirstPointThroughThirtySecondsAfterLastPoint() {
        val f = flight()
        val first = AwaitingMapFlights.firstTime(f)
        val last = AwaitingMapFlights.lastTime(f)
        assertEquals(754_000L, last - first)
        assertTrue(AwaitingMapClueMatch.matches("1SAR7Db150Brdg", first, f))
        assertTrue(AwaitingMapClueMatch.matches("1sar7db150brdg", last, f))
        assertTrue(AwaitingMapClueMatch.matches("1SAR7Db150Brdg", last + 30_000, f))
        assertFalse(AwaitingMapClueMatch.matches("1SAR7Db150Brdg", last + 30_001, f))
        assertFalse(AwaitingMapClueMatch.matches("1SAR7Db150Brdg", first - 1, f))
        assertFalse(AwaitingMapClueMatch.matches("1sar7DjMn4Pr", first + 10, f))
        val next = flight(id = "next", start = last + 10_000)
        data class C(val d: String, val t: Long)
        val tail = C("1SAR7Db150Brdg", last + 20_000)
        val own = C("1SAR7Db150Brdg", first + 5_000)
        val lateTail = C("1SAR7Db150Brdg", last + 5_000)
        assertEquals(listOf(own, lateTail), AwaitingMapClueMatch.ownedClues(listOf(tail, own, lateTail), f, listOf(next), { it.d }, { it.t }))
        assertEquals(listOf(tail, own), AwaitingMapClueMatch.ownedClues(listOf(tail, own), f, emptyList(), { it.d }, { it.t }))
    }

    @Test fun archiveNamesMatchWaypointTrackArchiveWriter() {
        val f = flight()
        assertEquals(listOf(OperatorArchiveFilename.track("RID-1", start, zone), OperatorArchiveFilename.clueReport("RID-1", start, zone)),
            AwaitingMapFlightArchive.filenames(f, zone))
        // Falls back to the track label when no remote ID was recorded.
        assertEquals("1SAR7Db150Brdg_0641", AwaitingMapFlightArchive.aircraftId(flight(remote = "")))
        // 1_790_553_873_000 is 2026-09-27 17:04 PDT; the day after the end is also checked.
        assertEquals(listOf("tracks-27Sep2026", "tracks-28Sep2026"), AwaitingMapFlightArchive.candidateDayDirectories(f, zone))
    }

    @Test fun discardDeletesExactlyThatFlightsPhotosArchiveAndEntry() {
        val root = Files.createTempDirectory("awaiting-discard").toFile()
        try {
            AwaitingMapFlights.useFileForTesting(File(root, "awaiting-map-flights.json"))
            AwaitingMapFlights.record("target", "RID-1", "1SAR7Db150Brdg_0641", points(), false, "", "")
            AwaitingMapFlights.record("target", "RID-1", "1SAR7Db150Brdg_0641", points(), true, "", "")
            AwaitingMapFlights.record("other", "RID-2", "1sar7DjMn4Pr_0641", points(), false, "", "")
            val target = AwaitingMapFlights.pending().first { it.getString("id") == "target" }

            // Clue photos: two belong to the target, one to another drone, one is from earlier.
            val store = AndroidClueStore.forDirectory(File(root, "clues"))
            fun save(id: String, designator: String, at: Long) = AndroidClueRecord(id, "unassigned", 39.0, -121.0, 0.0, "Clue", "",
                at, designator, "$id.jpg", "$id-thumb.jpg", false).also { store.saveEncoded(it, byteArrayOf(1), byteArrayOf(2)) }
            save("a", "1SAR7Db150Brdg", start + 10_000)
            save("b", "1SAR7Db150Brdg", start + 754_000 + 25_000)
            save("c", "1sar7DjMn4Pr", start + 10_000)
            save("d", "1SAR7Db150Brdg", start - 60_000)
            val owned = store.awaitingFlightClues(target, AwaitingMapFlights.allEntries()).map { it.id }.sorted()
            assertEquals(listOf("a", "b"), owned)

            // Archive tree: the target's files plus neighbours that must survive.
            val archive = File(root, "archive")
            val day = File(archive, "tracks-27Sep2026").apply { mkdirs() }
            val targetNames = AwaitingMapFlightArchive.filenames(target, zone)
            val keepNames = listOf(OperatorArchiveFilename.track("RID-2", start, zone), OperatorArchiveFilename.track("RID-1", start - 3_600_000, zone),
                OperatorArchiveFilename.clueReport("RID-1", start - 3_600_000, zone), "reported.txt", "Log_x.txt")
            (targetNames + keepNames).forEach { File(day, it).writeText("x") }
            val files = AwaitingMapArchiveFiles { dir, name ->
                val file = File(File(archive, dir), name)
                when { !file.isFile -> ArchiveFileDeletion.NOT_FOUND; file.delete() -> ArchiveFileDeletion.DELETED; else -> ArchiveFileDeletion.FAILED }
            }

            val result = AwaitingMapFlightDiscarder.discard(target, owned, { store.delete(it) }, files, { AwaitingMapFlights.discard(it) }, zone)

            assertTrue(result.logSummary(target), result.succeeded)
            assertEquals(listOf("a", "b"), result.deletedClueIds)
            assertEquals(targetNames.map { "tracks-27Sep2026/$it" }, result.deletedArchiveFiles)
            assertEquals(listOf("other"), AwaitingMapFlights.allEntries().map { it.getString("id") })
            val remaining = AndroidClueStore.forDirectory(File(root, "clues"))
            assertEquals(listOf("c", "d"), remaining.recordsForMap("unassigned").map { it.id }.sorted())
            listOf("a", "b").forEach { assertFalse(File(root, "clues/$it.jpg").exists()) }
            listOf("c", "d").forEach { assertTrue(File(root, "clues/$it.jpg").exists()) }
            targetNames.forEach { assertFalse(it, File(day, it).exists()) }
            keepNames.forEach { assertTrue(it, File(day, it).exists()) }
            assertTrue(result.logSummary(target).contains("clues=2[a,b]"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun discardKeepsTheEntryWhenAFileCannotBeDeleted() {
        val root = Files.createTempDirectory("awaiting-discard-fail").toFile()
        try {
            AwaitingMapFlights.useFileForTesting(File(root, "awaiting-map-flights.json"))
            AwaitingMapFlights.record("target", "RID-1", "1SAR7Db150Brdg", points(), false, "", "")
            val target = AwaitingMapFlights.pending().single()
            val result = AwaitingMapFlightDiscarder.discard(target, listOf("missing"), { false },
                { _, _ -> ArchiveFileDeletion.FAILED }, { AwaitingMapFlights.discard(it) }, zone)
            assertFalse(result.succeeded)
            assertFalse(result.entryRemoved)
            assertNotNull(AwaitingMapFlights.pending().singleOrNull { it.getString("id") == "target" })
        } finally {
            root.deleteRecursively()
        }
    }
}
