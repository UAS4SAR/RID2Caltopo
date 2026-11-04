package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.video.AndroidClueRecord

class FlightKmzTest {
    private val t0 = 1_790_553_873_000L
    private val track = listOf(FlightKmzPoint(39.1, -121.1, 100.0), FlightKmzPoint(39.2, -121.2, null))

    @Test fun flightKmzIsWrittenWithoutClues() {
        val kmz = FlightKmz.archive("1SAR7_0641", track, emptyList())
        assertTrue(FlightKmz.isValidArchive(kmz))
        val entries = FlightKmz.entries(kmz)
        assertEquals(listOf("doc.kml"), entries.keys.toList())
        val kml = String(entries.getValue("doc.kml"))
        assertTrue(kml.contains("<LineString><tessellate>1</tessellate><coordinates>"))
        assertTrue(kml.contains("-121.100000,39.100000,100.0"))
        assertTrue(kml.contains("-121.200000,39.200000,0.0"))
        assertFalse(kml.contains("<TimeStamp>"))
        // A single-point flight is still a valid KMZ (a Point instead of a line).
        val single = String(FlightKmz.entries(FlightKmz.archive("one", track.take(1), emptyList())).getValue("doc.kml"))
        assertTrue(single.contains("<Point><coordinates>-121.100000,39.100000,100.0</coordinates></Point>"))
        assertFalse(FlightKmz.isValidArchive("torn".toByteArray()))
        assertFalse(FlightKmz.isValidArchive(kmz.copyOf(kmz.size / 2)))
    }

    @Test fun flightKmzIncludesLocalMarkersAndBindingData() {
        val binding = ClueBinder.bind("RID-1", "flight-1", t0, "stream-pts", t0 + 400,
            listOf(ClueBindingPoint(t0 - 1_234, t0 - 900, 39.15, -121.13, 100.0, "rid", true)), null, false)
        val published = FlightKmzClue("Jacket <red>", ClueBindingText.publishedDescription("Seen & photographed", binding), t0,
            39.151, -121.131, 98.5, byteArrayOf(1, 2, 3), localOnly = false, binding = binding)
        val marker = FlightKmzClue("Local marker", "", t0 + 5_000, 39.152, -121.132, null, null, localOnly = true, binding = null)
        val kmz = FlightKmz.archive("Flight", track, listOf(published, marker))
        val entries = FlightKmz.entries(kmz)
        assertEquals(listOf("doc.kml", "files/clue_0.jpg"), entries.keys.toList())
        val kml = String(entries.getValue("doc.kml"))
        assertTrue(kml.contains("<name>Jacket &lt;red&gt;</name>"))
        assertTrue(kml.contains("<TimeStamp><when>2026-09-28T00:04:33.000Z</when></TimeStamp>"))
        assertTrue(kml.contains("Offset: -1.234 s (waypoint minus capture, drone clock)"))
        assertTrue(kml.contains("<img src=\"files/clue_0.jpg\"/>"))
        assertTrue(kml.contains("<Data name=\"r2c_binding_offset_s\"><value>-1.234</value></Data>"))
        assertTrue(kml.contains("<Data name=\"r2c_waypoint_time\"><value>2026-09-28T00:04:31.766Z</value></Data>"))
        assertTrue(kml.contains("<Data name=\"r2c_flight_id\"><value>flight-1</value></Data>"))
        assertTrue(kml.contains("<Data name=\"r2c_local_only\"><value>true</value></Data>"))
        assertTrue(kml.contains("-121.132000,39.152000,0.0"))
    }

    private fun geoJson(points: JSONArray, properties: JSONObject = JSONObject()) = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray().put(JSONObject().put("type", "Feature")
            .put("properties", properties.put("title", "1SAR7_0641").put("r2c_prop", JSONObject().put("rid", "RID-1")))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", points))))
        .toString().toByteArray()

    @Test fun archiveDecodeReadsOldAndNewPointFormats() {
        val old = geoJson(JSONArray()
            .put(JSONArray().put("-121.100000").put("39.100000").put("100").put("${t0}"))
            .put(JSONArray().put("-121.200000").put("39.200000").put("-1000").put("${t0 + 1_000}")))
        val contents = FlightArchiveRebuild.decode(old)!!
        assertEquals("1SAR7", contents.designator)
        assertEquals("RID-1", contents.remoteId)
        assertEquals(listOf(t0, t0 + 1_000), contents.points.map { it.timeMs })
        assertNull(contents.points[0].receivedAtMs)
        assertNull(contents.points[1].altitudeMeters)
        val legacy = geoJson(JSONArray()
            .put(JSONArray().put(-121.1).put(39.1).put(100).put(t0))
            .put(JSONArray().put(-121.2).put(39.2).put(101).put(t0 + 1_000)),
            JSONObject().put("r2c_point_received_ms", JSONArray().put(t0 + 150).put(JSONObject.NULL))
                .put("r2c_point_drone_clock", JSONArray().put(true).put(false)).put("r2c_flight_id", "flight-9"))
        val decodedLegacy = FlightArchiveRebuild.decode(legacy)!!
        assertEquals(t0 + 150, decodedLegacy.points[0].receivedAtMs)
        assertNull(decodedLegacy.points[1].receivedAtMs)
        assertFalse(decodedLegacy.points[1].droneClock)
        val compact = geoJson(JSONArray()
            .put(JSONArray().put(-121.1).put(39.1).put(100).put(t0))
            .put(JSONArray().put(-121.2).put(39.2).put(101).put(t0 + 1_000)),
            JSONObject().put("r2c_point_recv_base_ms", t0 + 150)
                .put("r2c_point_recv_off_ms", JSONArray().put(0).put(JSONObject.NULL))
                .put("r2c_point_no_drone_clock", JSONArray().put(1)).put("r2c_flight_id", "flight-9"))
        val decodedCompact = FlightArchiveRebuild.decode(compact)!!
        assertEquals(decodedLegacy.points.map { it.receivedAtMs }, decodedCompact.points.map { it.receivedAtMs })
        assertEquals(decodedLegacy.points.map { it.droneClock }, decodedCompact.points.map { it.droneClock })
        assertEquals("flight-9", FlightArchiveRebuild.flightId(legacy))
        assertNull(FlightArchiveRebuild.flightId(old))
        assertNull(FlightArchiveRebuild.decode("{}".toByteArray()))
        assertEquals("RID-1_x.kmz", FlightArchiveRebuild.kmzName("RID-1_x.json"))
    }

    private fun record(id: String, at: Long, binding: ClueBinding? = null, designator: String = "1SAR7") = AndroidClueRecord(
        id = id, mapKey = "unassigned", lat = 39.15, lng = -121.13, alt = 100.0, title = "Clue $id", description = "d",
        createdAtMs = at, sourceDesignator = designator, imageFilename = "$id.jpg", thumbnailFilename = "$id-t.jpg",
        publishToCaltopo = id != "local", binding = binding)

    @Test fun archivedFlightOwnershipAndBindingFallback() {
        fun contents(start: Long, count: Int) = FlightArchiveContents("1SAR7_x", "RID-1",
            (0 until count).map { ClueBindingPoint(start + it * 1_000L, null, 39.0 + it * 0.001, -121.0, 100.0, "track", true) })
        val first = contents(t0, 60)            // t0 .. t0+59 s
        val second = contents(t0 + 75_000, 60)  // starts 16 s after the first ends
        val archives = mapOf("day/a.json" to first, "day/b.json" to second)
        val clues = listOf(
            record("lead", t0 - 20_000),                // leading window of the first flight
            record("gap", t0 + 70_000),                 // 11 s after first, 5 s before second: nearest point wins
            record("tail", t0 + 63_000),                // 4 s after first, 12 s before second
            record("local", t0 + 10_000),               // local markers are included
            record("other", t0 + 10_000, designator = "OTHER"),
            record("bound", t0 + 70_000, binding = ClueBinding("RID-1", "day/a.json", t0, t0 + 70_000, "stream-pts", t0 + 70_000)),
        )
        assertEquals(listOf("lead", "local", "tail", "bound"),
            FlightArchiveRebuild.ownedClues("day/a.json", archives, clues).map { it.id })
        assertEquals(listOf("gap"), FlightArchiveRebuild.ownedClues("day/b.json", archives, clues).map { it.id })
        // A clue saved without a usable binding binds to the archive's nearest stored point.
        val fallback = FlightArchiveRebuild.bindingFallback(record("lead", t0 + 3_400), first)
        val binding = assertNotNullAndGet(fallback.binding)
        assertEquals(-400L, binding.offsetMs)
        assertFalse(binding.originIsWaypoint)
        // An existing binding with a nearest point is never replaced.
        val kept = record("k", t0, binding = binding)
        assertEquals(kept, FlightArchiveRebuild.bindingFallback(kept, second))
        val kmzClue = FlightArchiveRebuild.kmzClue(clues[3], null)
        assertTrue(kmzClue.localOnly)
    }

    @Test fun kmzRewriteKeepsMigratedFlightFolderClueWhenMemoryIncomplete() {
        val archives = mapOf(
            "flight-a" to FlightArchiveContents(
                "1SAR7_x", "RID-1",
                (0 until 7).map { ClueBindingPoint(t0 + it * 10_000L, null, 39.0 + it * 0.001, -121.0, 100.0, "track", true) },
            ),
        )
        val folder = record("migrated", t0 + 20_000, binding = ClueBinding(
            "RID-1", "flight-a", t0, t0 + 20_000, "stream-pts", t0 + 20_000,
        )).copy(imageFilename = "flight-a/migrated.jpg", thumbnailFilename = "flight-a/migrated-t.jpg")
        val owned = FlightArchiveRebuild.cluesForRewrite(
            "flight-a", archives, memoryClues = emptyList(), flightFolderClues = listOf(folder), tombstonedIds = emptySet(),
        )
        assertEquals(listOf("migrated"), owned.map { it.id })
        assertTrue(owned.single().imageFilename.startsWith("flight-a/"))
    }

    @Test fun kmzRewriteEmptiesWhenClueTombstonedAfterDelete() {
        val archives = mapOf(
            "flight-a" to FlightArchiveContents(
                "1SAR7_x", "RID-1",
                listOf(ClueBindingPoint(t0, null, 39.0, -121.0, 100.0, "track", true)),
            ),
        )
        val folder = record("gone", t0, binding = ClueBinding(
            "RID-1", "flight-a", t0, t0, "stream-pts", t0,
        )).copy(imageFilename = "flight-a/gone.jpg", thumbnailFilename = "flight-a/gone-t.jpg")
        val owned = FlightArchiveRebuild.cluesForRewrite(
            "flight-a", archives, memoryClues = listOf(folder), flightFolderClues = listOf(folder),
            tombstonedIds = setOf("gone"),
        )
        assertTrue(owned.isEmpty())
    }

    @Test fun kmzIncludesUnpublishedAndLocalOnlyCluesEvenWhenPhotoMissingFromAllRecords() {
        // allRecords no longer drops photo-missing rows; ownership + KMZ still include
        // unpublished / localOnly clues the same way FlightArchiveStore does.
        val archives = mapOf(
            "day/a.json" to FlightArchiveContents(
                "1SAR7_x",
                "RID-1",
                listOf(ClueBindingPoint(t0, null, 39.0, -121.0, 100.0, "track", true)),
            ),
        )
        val unpublished = record("pending", t0).copy(uploadState = "pending", publishToCaltopo = true)
        val localOnly = record("local", t0 + 1_000).copy(publishToCaltopo = false, uploadState = "local")
        val owned = FlightArchiveRebuild.ownedClues("day/a.json", archives, listOf(unpublished, localOnly))
        assertEquals(listOf("pending", "local"), owned.map { it.id })
        val kmz = FlightKmz.archive(
            "Flight",
            listOf(FlightKmzPoint(39.0, -121.0, 100.0)),
            owned.map { FlightArchiveRebuild.kmzClue(it, jpeg = null) },
        )
        val kml = String(FlightKmz.entries(kmz).getValue("doc.kml"))
        assertTrue(kml.contains("Clue pending"))
        assertTrue(kml.contains("Clue local"))
        assertTrue(kml.contains("<Data name=\"r2c_local_only\"><value>true</value></Data>"))
    }

    private fun <T> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }
}
