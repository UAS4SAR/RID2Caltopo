package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Round 13: the iPad died after the live track had started. The waypoint journal
 * held 65 points and the interrupted snapshot held 62. Restart writes one 65-point
 * file, authorizes one Tracker upload, and updates shape e0381722 in place.
 */
class Round13RestartRecoveryTest {
    private val flightId = "e0381722-218d-5262-add1-4b3284a30694"
    private val otherShape = "b65e121d-9807-5db7-9470-b989c012699d"
    private val remote = "1581F8HGX255S00A0FZT"
    private lateinit var root: File
    private lateinit var awaitingFile: File
    private lateinit var ledgerFile: File
    private lateinit var interruptedFile: File
    private lateinit var clueFile: File

    @Before
    fun setUp() {
        root = File.createTempFile("round13-journals", null).also {
            assertTrue(it.delete())
            assertTrue(it.mkdirs())
        }
        awaitingFile = File.createTempFile("round13-awaiting", ".json").also { it.writeText("[]") }
        ledgerFile = File.createTempFile("round13-ledger", ".json").also { it.writeText("{}") }
        interruptedFile = File.createTempFile("round13-interrupted", ".json").also { it.delete() }
        clueFile = File(root, "clue-0EC6EDA2.txt").also { it.writeText("kept") }
        WaypointTrackJournal.setRootForTesting(root)
        WaypointTrackJournal.setRecoveredArchiveWriterForTesting(null)
        AwaitingMapFlights.useFileForTesting(awaitingFile)
        FlightPublicationLedger.useFileForTesting(ledgerFile)
        CaltopoInterruptedTrackJournal.setFileForTesting(interruptedFile)
    }

    @After
    fun tearDown() {
        WaypointTrackJournal.setRecoveredArchiveWriterForTesting(null)
        WaypointTrackJournal.setSavedFlightLookupForTesting(null)
        WaypointTrackJournal.setRootForTesting(null)
        AwaitingMapFlights.useFileForTesting(null)
        FlightPublicationLedger.useFileForTesting(null)
        CaltopoInterruptedTrackJournal.setFileForTesting(null)
        root.deleteRecursively()
        awaitingFile.delete()
        ledgerFile.delete()
        interruptedFile.delete()
    }

    @Test
    fun batteryDeathWritesOne65PointFileAndUpdatesTheSameShape() {
        plantJournal(verified = true)
        plantInterrupted(points = 62)
        val writes = mutableListOf<Pair<Int, Boolean>>()
        WaypointTrackJournal.setRecoveredArchiveWriterForTesting { flight, localOnly ->
            writes.add(flight.points.length() to localOnly)
            assertEquals("1SAR7", flight.context.recordedPilot)
            assertEquals(flightId, flight.id)
            true
        }

        WaypointTrackJournal.recoverUnfinished()

        assertEquals(listOf(65 to false), writes)
        assertFalse(File(root, "$flightId.ndjson").exists())
        assertEquals(65, interruptedPoints())
        assertTrue(clueFile.exists())

        val fixture = TestR2cRuntimeFactory.create("round13")
        CaltopoInterruptedTrackJournal.recover(
            "4J0LF02",
            fixture.runtime,
            listOf(shape(flightId, 62), shape(otherShape, 198)),
        ) { "archive-folder" }

        val edits = fixture.calTopoSessionGateway.snapshotOperations().filter { it.kind == "editObject" }
        assertEquals(1, edits.size)
        assertEquals("Shape:$flightId", edits[0].summary)
        assertEquals(65, edits[0].payload!!.getJSONObject("geometry").getJSONArray("coordinates").length())
        assertTrue(fixture.calTopoSessionGateway.snapshotOperations().none { it.summary.contains(otherShape) })
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())

        WaypointTrackJournal.recoverUnfinished()
        CaltopoInterruptedTrackJournal.recover("4J0LF02", fixture.runtime, emptyList()) { "archive-folder" }
        assertEquals(1, writes.size)
        assertEquals(1, fixture.calTopoSessionGateway.snapshotOperations().count { it.kind == "editObject" })
        assertTrue(clueFile.exists())
    }

    @Test
    fun failedFileWriteKeepsTheJournalAndTheShorterSnapshot() {
        plantJournal(verified = true)
        plantInterrupted(points = 62)
        WaypointTrackJournal.setRecoveredArchiveWriterForTesting { _, _ -> false }

        WaypointTrackJournal.recoverUnfinished()

        assertTrue(File(root, "$flightId.ndjson").exists())
        assertEquals(62, interruptedPoints())
        assertTrue(clueFile.exists())
    }

    @Test
    fun unverifiedMineIsHeldAndNotAuthorizedForUpload() {
        plantJournal(verified = false)
        plantInterrupted(points = 62)
        val writes = mutableListOf<Boolean>()
        WaypointTrackJournal.setRecoveredArchiveWriterForTesting { flight, localOnly ->
            writes.add(localOnly)
            assertEquals("1SAR7", flight.context.recordedPilot)
            assertFalse(flight.context.mayAutoPublish)
            true
        }

        WaypointTrackJournal.recoverUnfinished()

        assertEquals(listOf(true), writes)
        assertFalse(File(root, "$flightId.ndjson").exists())
        val awaiting = AwaitingMapFlights.allEntries().single()
        assertEquals("review", awaiting.getString("decision"))
        assertTrue(awaiting.getBoolean("ownershipReviewPending"))
        assertEquals("1SAR7", awaiting.getString("recordedPilot"))
    }

    /**
     * The iPad's awaiting-map and interrupted files were not kept. Those two records
     * are rebuilt from the committed journal: 61 points in the awaiting row, 62 in
     * the interrupted snapshot. Every coordinate comes from that file.
     */
    @Test
    fun round13IPadFilesReplayWrites65PointsOnceAndKeepsTheShapeAndClue() {
        val source = round13Journal()
        val journal = File(root, "$flightId.ndjson")
        source.copyTo(journal)
        val parsed = WaypointTrackJournal.readJournal(journal)
        assertEquals(65, parsed!!.points.length())
        assertEquals("1SAR7", parsed.context.recordedPilot)
        assertEquals(flightId, parsed.id)
        assertEquals(-121.132794, parsed.points.getJSONArray(0).getDouble(0), 0.0000005)

        val awaitingPoints = JSONArray()
        for (index in 0 until 61) awaitingPoints.put(JSONArray(parsed.points.getJSONArray(index).toString()))
        awaitingFile.writeText(JSONArray().put(JSONObject()
            .put("id", flightId)
            .put("remote", remote)
            .put("label", "1sar7DjMtrc4td")
            .put("points", awaitingPoints)
            .put("finished", false)
            .put("decision", "bound")
            .put("map", "4J0LF02")
            .put("team", "personal:BCL66J")
            .put("publicationStarted", true)
            .put("recordedPilot", "1SAR7")
            .put("claim", "verified")
            .put("ownershipContextKnown", true)
        ).toString())
        val interrupted = JSONArray()
        for (index in 0 until 62) interrupted.put(JSONArray(parsed.points.getJSONArray(index).toString()))
        assertTrue(CaltopoInterruptedTrackJournal.save(
            "4J0LF02", remote, flightId, "1sar7DjMtrc4td_082754Nov11", "", interrupted, parsed.startedAtMs,
        ))
        WaypointTrackJournal.setSavedFlightLookupForTesting(object : RecoveredFlightDedup.Lookup {
            override fun flights() = emptyList<RecoveredFlightDedup.SavedFlight>()
            override fun mergeInto(dayDirectory: String, fileName: String, journalPoints: JSONArray) = false
        })

        assertTrue(AwaitingMapFlights.publicationAlreadyStarted(flightId))
        assertTrue(AwaitingMapFlights.allEntries().single().getBoolean("finished"))
        assertTrue(AwaitingMapFlights.allEntries().single().getBoolean("publicationStarted"))

        val flightFile = File(root, "082754.json")
        var uploads = 0
        WaypointTrackJournal.setRecoveredArchiveWriterForTesting { flight, localOnly ->
            assertEquals(65, flight.points.length())
            assertEquals("1SAR7", flight.context.recordedPilot)
            assertEquals(flightId, flight.id)
            assertFalse(localOnly)
            assertTrue(flight.context.mayAutoPublish)
            val payload = JSONObject()
                .put("type", "FeatureCollection")
                .put("features", JSONArray().put(JSONObject()
                    .put("type", "Feature")
                    .put("properties", JSONObject().put("title", flight.label).put("owner", flight.context.recordedPilot))
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", flight.points))))
                .toString()
            java.io.FileOutputStream(flightFile).use { out ->
                out.write(payload.toByteArray())
                out.fd.sync()
            }
            val count = JSONObject(flightFile.readText())
                .getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates").length()
            if (count != 65) return@setRecoveredArchiveWriterForTesting false
            uploads += 1
            true
        }

        WaypointTrackJournal.recoverUnfinished()

        assertEquals(1, uploads)
        assertFalse(journal.exists())
        assertTrue(source.isFile)
        assertEquals("kept", clueFile.readText())
        assertEquals(65, JSONObject(flightFile.readText())
            .getJSONArray("features").getJSONObject(0)
            .getJSONObject("geometry").getJSONArray("coordinates").length())
        assertFalse(flightFile.readText().contains("drone_confirmed"))

        val shapeCoords = JSONArray()
        for (index in 0 until 62) shapeCoords.put(JSONArray(parsed.points.getJSONArray(index).toString()))
        val fixture = TestR2cRuntimeFactory.create("round13-replay")
        CaltopoInterruptedTrackJournal.recover(
            "4J0LF02",
            fixture.runtime,
            listOf(shape(flightId, shapeCoords), shape(otherShape, 198)),
        ) { "archive-folder" }

        val operations = fixture.calTopoSessionGateway.snapshotOperations()
        val edits = operations.filter { it.kind == "editObject" }
        assertEquals(1, edits.size)
        assertEquals("Shape:$flightId", edits[0].summary)
        assertEquals(65, edits[0].payload!!.getJSONObject("geometry").getJSONArray("coordinates").length())
        assertEquals(0, operations.count { it.kind == "deleteLiveTrack" })
        assertTrue(operations.none { it.summary.contains(otherShape) || it.summary.contains("drone_confirmed") })
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
        assertEquals("kept", clueFile.readText())

        WaypointTrackJournal.recoverUnfinished()
        CaltopoInterruptedTrackJournal.recover("4J0LF02", fixture.runtime, emptyList()) { "archive-folder" }
        assertEquals(1, uploads)
        assertEquals(1, fixture.calTopoSessionGateway.snapshotOperations().count { it.kind == "editObject" })
        assertEquals(0, fixture.calTopoSessionGateway.snapshotOperations().count { it.kind == "deleteLiveTrack" })
        assertEquals("kept", clueFile.readText())
    }

    private fun round13Journal(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/validation/2026-11-11-field-logs/round13/ipad/$flightId.ndjson")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("round13 journal not found from ${System.getProperty("user.dir")}")
    }

    private fun shape(id: String, coordinates: JSONArray): JSONObject =
        JSONObject()
            .put("id", id)
            .put("properties", JSONObject().put("class", "Shape"))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates))

    private fun plantJournal(verified: Boolean) {
        WaypointTrackJournal.ensureOpen(flightId, remote, "1sar7DjMtrc4td", "4J0LF02", "personal:BCL66J", 1_794_414_474_042L)
        repeat(65) { index ->
            WaypointTrackJournal.appendPoint(
                flightId, 39.15, -121.13, 520.0,
                1_794_414_474_042L + index * 1_000L,
                1_794_414_474_042L + index * 1_000L,
                "djiVideo", true,
            )
        }
        val claim = if (verified) FlightOwnershipContext.VERIFIED else FlightOwnershipContext.PENDING
        WaypointTrackJournal.noteContext(flightId, FlightOwnershipContext.capture(
            true, false, false, claim, "1SAR7", "NCSSAR", "DJI Matrice 4TD", "1sar7DjMtrc4td",
        ))
        WaypointTrackJournal.drainForTesting()
    }

    private fun plantInterrupted(points: Int) {
        val coordinates = JSONArray()
        repeat(points) { index ->
            coordinates.put(JSONArray().put(-121.13).put(39.15).put(520).put(1_794_414_474_042L + index * 1_000L))
        }
        assertTrue(CaltopoInterruptedTrackJournal.save(
            "4J0LF02", remote, flightId, "1sar7DjMtrc4td_082754Nov11", "", coordinates, 1_794_414_474_042L,
        ))
    }

    private fun interruptedPoints(): Int {
        val entries = CaltopoInterruptedTrackJournal.entriesForTesting()
        assertEquals(1, entries.length())
        return entries.getJSONObject(0).getJSONArray("points").length()
    }

    private fun shape(id: String, points: Int): JSONObject {
        val coordinates = JSONArray()
        repeat(points) { coordinates.put(JSONArray().put(-121.13).put(39.15).put(520)) }
        return JSONObject()
            .put("id", id)
            .put("properties", JSONObject().put("class", "Shape"))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates))
    }
}
