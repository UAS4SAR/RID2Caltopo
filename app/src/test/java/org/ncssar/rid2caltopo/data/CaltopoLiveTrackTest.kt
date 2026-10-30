package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ncssar.rid2caltopo.app.R2CActivity
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaltopoLiveTrackTest {
    private lateinit var journalFile: java.io.File
    private lateinit var mapNodeField: Field
    private var previousMapNode: Any? = null
    private var previousRuntime: Any? = null


    private lateinit var fixture: TestR2cRuntimeFactory.Fixture
    private lateinit var mapStatusField: Field
    private lateinit var folderIdField: Field
    private lateinit var archiveFolderIdField: Field
    private lateinit var liveTracksByIdField: Field
    private lateinit var originalMapStatus: CaltopoMap.MapStatusListener.mapStatus
    private var originalFolderId: String? = null
    private var originalArchiveFolderId: String? = null

    @Test
    fun archiveDescription_containsOnlyCapturedVideoLink() {
        val drone = CtDroneSpec("RID-ARCHIVE")
        drone.owner = "1SAR7"
        drone.org = "NCSSAR"
        drone.model = "M30T"

        assertEquals(
            "https://r2c-tracker.com/s/QHkyEQ",
            CaltopoLiveTrack.buildArchiveDescription(
                drone,
                "https://r2c-tracker.com/s/QHkyEQ",
            ),
        )
        assertEquals("", CaltopoLiveTrack.buildArchiveDescription(drone))
    }

    @Before
    fun setUp() {
        journalFile = java.io.File.createTempFile("livetrack-start-", ".json").apply { delete() }
        CaltopoInterruptedTrackJournal.setFileForTesting(journalFile)
        mapNodeField = CaltopoMap::class.java.getDeclaredField("MapNode").apply { isAccessible = true }
        previousMapNode = mapNodeField.get(null)
        mapNodeField.set(null, CaltopoNode.MapNode("map-test", "Test", 0L))
        fixture = TestR2cRuntimeFactory.create("live-track-test")
        fixture.setAsDefaultRuntime()
        CaltopoMap::class.java.getDeclaredField("CurrentRuntime").apply {
            isAccessible = true
            previousRuntime = get(null)
            set(null, fixture.runtime)
        }

        mapStatusField = CaltopoMap::class.java.getDeclaredField("MapStatus").apply { isAccessible = true }
        folderIdField = CaltopoMap::class.java.getDeclaredField("FolderId").apply { isAccessible = true }
        archiveFolderIdField = CaltopoMap::class.java.getDeclaredField("ArchiveFolderId").apply { isAccessible = true }
        liveTracksByIdField = CaltopoMap::class.java.getDeclaredField("LiveTracksById").apply { isAccessible = true }

        originalMapStatus = mapStatusField.get(null) as CaltopoMap.MapStatusListener.mapStatus
        originalFolderId = folderIdField.get(null) as String?
        originalArchiveFolderId = archiveFolderIdField.get(null) as String?

        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        folderIdField.set(null, "folder-test")
        archiveFolderIdField.set(null, "archive-folder-test")
        clearLocalTrackListeners()
    }

    @After
    fun tearDown() {
        CaltopoRecoveryWork.startWorker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
        CaltopoRecoveryWork.archiveWorker.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
        mapNodeField.set(null, previousMapNode)
        CaltopoMap::class.java.getDeclaredField("CurrentRuntime").apply { isAccessible = true; set(null, previousRuntime) }
        CaltopoInterruptedTrackJournal.setFileForTesting(null)
        journalFile.delete()
        clearLocalTrackListeners()
        mapStatusField.set(null, originalMapStatus)
        folderIdField.set(null, originalFolderId)
        archiveFolderIdField.set(null, originalArchiveFolderId)
        @Suppress("UNCHECKED_CAST")
        (liveTracksByIdField.get(null) as MutableMap<String, *>).clear()
        CaltopoMap::class.java.getDeclaredField("liveTracks").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(null) as MutableList<*>).clear()
        }
        CaltopoLiveTrack::class.java.getDeclaredField("LiveTrackByRemoteId").apply {
            isAccessible = true
            (get(null) as MutableMap<*, *>).clear()
        }
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    private fun drainRecovery() {
        repeat(3) {
            CaltopoRecoveryWork.startWorker.submit {}.get(3, TimeUnit.SECONDS)
            CaltopoRecoveryWork.archiveWorker.submit {}.get(3, TimeUnit.SECONDS)
        }
    }

    private fun startingTrack(): CaltopoLiveTrack {
        val drone = CtDroneSpec("RID-TIMEOUT")
        setDroneTrackLabel(drone, "RID-TIMEOUT_120000Sep25")
        return CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L).also {
            it.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
            it.setLocalOwner(true)
            drainRecovery()
        }
    }

    @Test fun ownerGrantedWhileMapAlreadyUp_startsLiveTrackExactlyOnce() {
        val drone = CtDroneSpec("RID-OWNER-UP")
        setDroneTrackLabel(drone, "RID-OWNER-UP_120100Sep25")
        val track = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        // Map is already up before ownership; constructor captured mapStatus=up.
        track.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        drainRecovery()
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("startLiveTrack"))

        track.setLocalOwner(true)
        drainRecovery()
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("startLiveTrack"))

        // Idempotent: a second grant and map-up must not POST again while start is in flight / started.
        track.setLocalOwner(true)
        track.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        drainRecovery()
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("startLiveTrack"))
        assertTrue(track.publishingLocally())
    }

    @Test fun mapUpThreeSecondsBeforeOwner_startsDespiteEmptyMapReviewIntent() {
        val awaiting = java.io.File.createTempFile("awaiting-map-before-owner-", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(awaiting)
            // Flight started before map connect — durable intent has empty map / review.
            AwaitingMapFlights.record(
                "flight-pre-map",
                "RIDMAPFIRST",
                "RIDMAPFIRST_120300Sep25",
                org.json.JSONArray().put(org.json.JSONArray().put(-121.1).put(39.1).put(500)),
                false,
                "",
                "team-a",
            )
            assertEquals("", AwaitingMapFlights.publicationId("RIDMAPFIRST", "map-test", "team-a"))

            val drone = CtDroneSpec("RID-MAP-FIRST")
            assertEquals("RIDMAPFIRST", drone.remoteId)
            setDroneTrackLabel(drone, "RIDMAPFIRST_120300Sep25")
            val track = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
            // Exact A5 ordering: map up, then owner=true three seconds later.
            track.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
            drainRecovery()
            assertEquals(0, fixture.calTopoSessionGateway.countOperations("startLiveTrack"))

            Thread.sleep(3_000L)
            track.setLocalOwner(true)
            drainRecovery()
            assertEquals(1, fixture.calTopoSessionGateway.countOperations("startLiveTrack"))
            val start = fixture.calTopoSessionGateway.snapshotOperations().first { it.kind == "startLiveTrack" }
            assertEquals("flight-pre-map", start.payload!!.getString("id"))
            assertTrue(track.publishingLocally())
        } finally {
            AwaitingMapFlights.useFileForTesting(null)
            awaiting.delete()
        }
    }

    @Test fun reservedPublicationIntentWhileMapUp_stillStartsLiveTrack() {
        val awaiting = java.io.File.createTempFile("awaiting-live-", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(awaiting)
            // CtDroneSpec keeps A-Z0-9 only; reserve must use the same normalized remote id.
            AwaitingMapFlights.reservePublication("RIDRESERVED", "flight-reserved-1")
            val drone = CtDroneSpec("RID-RESERVED")
            assertEquals("RIDRESERVED", drone.remoteId)
            setDroneTrackLabel(drone, "RIDRESERVED_120200Sep25")
            val track = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
            track.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
            track.setLocalOwner(true)
            drainRecovery()
            assertEquals(1, fixture.calTopoSessionGateway.countOperations("startLiveTrack"))
            val start = fixture.calTopoSessionGateway.snapshotOperations().first { it.kind == "startLiveTrack" }
            assertEquals("flight-reserved-1", start.payload!!.getString("id"))
        } finally {
            AwaitingMapFlights.useFileForTesting(null)
            awaiting.delete()
        }
    }

    private fun reply(id: String? = null) = CaltopoOp(null).apply {
        responseCode = if (id == null) 0 else 200
        if (id != null) responseJson = org.json.JSONObject().put("id", id)
        setOperationIsDone(id != null)
    }

    @Test fun delayedOwnershipGrantCannotUndoALaterRevocation() {
        val track = startingTrack()
        val request = CaltopoLiveTrack::class.java.getDeclaredField("ownershipRequest").apply { isAccessible = true }
            .get(track) as java.util.concurrent.atomic.AtomicReference<*>
        val oldGrant = request.get()
        track.setLocalOwner(false)
        CaltopoLiveTrack::class.java.getDeclaredMethod("setLocalOwnerOnWorker", oldGrant.javaClass)
            .apply { isAccessible = true }.invoke(track, oldGrant)
        assertFalse(track.publishingLocally())
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("startLiveTrack"))
    }

    @Test fun disconnectFinishArchivesToTheDepartingMapAndFolder() {
        val track = startingTrack()
        val gateway = fixture.calTopoSessionGateway
        val id = gateway.snapshotOperations().first { it.kind == "startLiveTrack" }.payload!!.getString("id")
        mapNodeField.set(null, CaltopoNode.MapNode("new-map", "Next", 0L))
        archiveFolderIdField.set(null, null)
        // Captured destination must be used even after MapNode/folder fields change.
        track.finishOwnedPublicationForDisconnect(4_000L, "map-test", "archive-folder-test")
        val shape = gateway.snapshotOperations().first { it.kind == "editObject" && it.summary == "Shape:$id" }.payload!!
        assertEquals("archive-folder-test", shape.getJSONObject("properties").getString("folderId"))
        assertEquals("map-test", gateway.recoveryMaps.last())
        assertFalse(gateway.recoveryMaps.contains("new-map"))
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
    }

    @Test fun delayedCheckpointCannotOverwriteANewerFinalSnapshot() {
        val track = startingTrack()
        val snapshot = CaltopoLiveTrack::class.java.getDeclaredMethod("journalSnapshot", String::class.java)
            .apply { isAccessible = true }
        val old = snapshot.invoke(track, "old")
        track.publishDirect(39.2, -121.2, 510, 2_000)
        val final = snapshot.invoke(track, "final")
        val save = CaltopoLiveTrack::class.java.getDeclaredMethod("saveJournalSnapshot", final.javaClass)
            .apply { isAccessible = true }
        save.invoke(track, final)
        save.invoke(track, old)
        val entry = CaltopoInterruptedTrackJournal.entriesForTesting().getJSONObject(0)
        assertEquals("final", entry.getString("description"))
        assertEquals(2, entry.getJSONArray("points").length())
    }

    @Test fun endedTrackRestartsOnceBuffersUntilOwnershipAndArchivesAllReturningPoints() {
        val track = startingTrack()
        val gateway = fixture.calTopoSessionGateway
        val peer = fixture.peerCoordinator as FakePeerCoordinator
        val firstId = gateway.snapshotOperations().first { it.kind == "startLiveTrack" }.payload!!.getString("id")
        gateway.startCallbacks[0].accept(reply(firstId))
        track.finishTrack("telemetry idle after Wi-Fi loss")
        assertFalse(track.isActive)

        val drone = CaltopoLiveTrack::class.java.getDeclaredField("droneSpec").apply { isAccessible = true }.get(track) as CtDroneSpec
        setDroneTrackLabel(drone, "RID-TIMEOUT_120100Sep25")
        repeat(4) { index ->
            // Exercise both the client's active branch and a redundant restart call.
            if (index == 0 || index == 2) track.startNewTrack(39.2 + index * .001, -121.2, 501.0, 2_000L + index)
            else track.publishDirect(39.2 + index * .001, -121.2, 501L, 2_000L + index)
            org.junit.Assert.assertTrue(track.isActive)
        }
        assertEquals(2, peer.countEvents("onLiveTrackCreated"))
        assertEquals(1, gateway.countOperations("startLiveTrack"))
        assertEquals(4, track.queuedPointCount)

        track.setLocalOwner(true)
        drainRecovery()
        val secondId = gateway.snapshotOperations().last { it.kind == "startLiveTrack" }.payload!!.getString("id")
        org.junit.Assert.assertNotEquals(firstId, secondId)
        gateway.startCallbacks[1].accept(reply(secondId))
        track.finishTrack("second segment ended")
        drainRecovery()
        val shape = gateway.snapshotOperations().first { it.kind == "editObject" && it.summary == "Shape:$secondId" }.payload!!
        assertEquals(4, shape.getJSONObject("geometry").getJSONArray("coordinates").length())
    }

    @Test fun pendingStartBuffersPointsAndTimeoutReusesPersistedIdentity() {
        val track = startingTrack()
        val gateway = fixture.calTopoSessionGateway
        val id = gateway.snapshotOperations().first { it.kind == "startLiveTrack" }.payload!!.getString("id")
        org.junit.Assert.assertTrue(journalFile.readText().contains(id))
        track.startNewTrack(39.2, -121.2, 501.0, 2_000L)
        assertEquals(1, gateway.countOperations("startLiveTrack"))
        assertEquals(0, gateway.countOperations("addLiveTrackPoint"))
        gateway.startCallbacks[0].accept(reply())
        setPrivateField(track, "nextStartAttemptAtMs", 0L)
        track.publishDirect(39.3, -121.3, 502L, 3_000L)
        drainRecovery()
        val ids = gateway.snapshotOperations().filter { it.kind == "startLiveTrack" }.map { it.payload!!.getString("id") }
        assertEquals(listOf(id, id), ids)
        assertEquals(0, gateway.countOperations("addLiveTrackPoint"))
        gateway.startCallbacks[1].accept(reply(id))
        drainRecovery()
        assertEquals(1, gateway.countOperations("addLiveTrackPoint"))
        assertEquals(39.3, gateway.snapshotOperations().last { it.kind == "addLiveTrackPoint" }.payload!!.getDouble("lat"), 0.000001)
        repeat(4) { gateway.pointCallbacks[0].accept(reply()) }
        org.junit.Assert.assertTrue(track.isActive)
        track.publishDirect(39.4, -121.4, 503L, 4_000L)
        drainRecovery()
        assertEquals(2, gateway.countOperations("startLiveTrack"))
    }

    @Test fun lostStartResponseStillArchivesEveryStoredPointUnderSameId() {
        val track = startingTrack()
        val gateway = fixture.calTopoSessionGateway
        val id = gateway.snapshotOperations().first { it.kind == "startLiveTrack" }.payload!!.getString("id")
        track.publishDirect(39.2, -121.2, 501L, 2_000L)
        gateway.startCallbacks[0].accept(reply())
        track.finishTrack("flight ended")
        val shape = gateway.snapshotOperations().first { it.kind == "editObject" && it.summary == "Shape:$id" }.payload!!
        assertEquals(2, shape.getJSONObject("geometry").getJSONArray("coordinates").length())
        assertEquals(id, shape.getString("id"))
        gateway.startCallbacks[0].accept(reply(id))
        assertEquals(0, gateway.countOperations("addLiveTrackPoint"))
    }

    @Test fun delayedFinishKeepsSegmentTitleAfterDroneResetAndNextFlight() {
        val track = startingTrack()
        val gateway = fixture.calTopoSessionGateway
        val id = gateway.snapshotOperations().first { it.kind == "startLiveTrack" }.payload!!.getString("id")
        gateway.startCallbacks[0].accept(reply(id))
        val drone = track.droneSpec
        val originalLabel = drone.trackLabel()
        track.publishDirect(39.2, -121.2, 510, 2_000)
        // Main clears flight state immediately after dispatching finish. The
        // worker may also run after the next flight has already changed it.
        drone.reset()
        setDroneTrackLabel(drone, "NEXT-FLIGHT_130000Oct28")
        gateway.holdRecoveryEdits = true
        CaltopoLiveTrack::class.java.getDeclaredMethod("finishTrackOnWorker", String::class.java,
            String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(track, "flight ended", "map-test", "archive-folder-test")
        val shape = gateway.snapshotOperations().first { it.kind == "editObject" && it.summary == "Shape:$id" }.payload!!
        assertEquals(originalLabel, shape.getJSONObject("properties").getString("title"))
        assertEquals(2, shape.getJSONObject("geometry").getJSONArray("coordinates").length())
        assertEquals(originalLabel, CaltopoInterruptedTrackJournal.entriesForTesting().getJSONObject(0).getString("label"))
        assertEquals("NEXT-FLIGHT_130000Oct28", drone.trackLabel())
    }

    @Test fun renamedSegmentKeepsItsTitleWhenDroneResetsBeforeArchive() {
        val track = startingTrack()
        val drone = track.droneSpec
        val renamed = "RENAMED_120000Oct28"
        setDroneTrackLabel(drone, renamed)
        track.renameTrack()
        drone.reset()
        track.finishTrack("flight ended")
        val shape = fixture.calTopoSessionGateway.snapshotOperations().first {
            it.kind == "editObject" && it.summary.startsWith("Shape:")
        }.payload!!
        assertEquals(renamed, shape.getJSONObject("properties").getString("title"))
    }

    @Test fun disconnectThenFlightEndsOfflineThenReconnectPublishesArchiveOnce() {
        val drone = CtDroneSpec("RID-OFFLINE-END")
        setDroneTrackLabel(drone, "1sar7DjMn4Pr_110642Oct30")
        drone.setOwner("1sar7")
        drone.setCurrentFlightConfirmed(true)
        val track = CaltopoLiveTrack(drone, 39.153, -121.133, 527.0, 1_000L)
        track.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        track.setLocalOwner(true)
        forceLiveTrackId(track, "487badd1-10d0-4c05-b41f-dc62b2fd2c06")
        track.publishDirect(39.154, -121.134, 528L, 2_000L)
        drainRecovery()

        // Mid-flight disconnect: durable journal only; no Shape/LiveTrack network archive.
        track.shutdown(4_000L)
        drainRecovery()
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(1, CaltopoInterruptedTrackJournal.entriesForTesting().length())
        assertEquals(
            "487badd1-10d0-4c05-b41f-dc62b2fd2c06",
            CaltopoInterruptedTrackJournal.entriesForTesting().getJSONObject(0).getString("liveTrackId"),
        )
        assertFalse(track.isActive)

        // Flight ends while the map is still down.
        var ended = 0
        val listener = CaltopoLiveTrack.LocalTrackFinishedListener { remote, _, _ ->
            if (remote == drone.remoteId) ended++
        }
        CaltopoLiveTrack.AddLocalTrackFinishedListener(listener)
        try {
            track.finishTrack("RID/peer idle while map down")
            drainRecovery()
            assertEquals(1, ended)
        } finally {
            CaltopoLiveTrack.RemoveLocalTrackFinishedListener(listener)
        }
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(1, CaltopoInterruptedTrackJournal.entriesForTesting().length())

        // Reconnect recovers exactly once: Shape publish + LiveTrack cleanup.
        CaltopoInterruptedTrackJournal.recover("map-test", "archive-folder-test", fixture.runtime)
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        val shape = fixture.calTopoSessionGateway.snapshotOperations()
            .first { it.kind == "editObject" && it.summary == "Shape:487badd1-10d0-4c05-b41f-dc62b2fd2c06" }
            .payload!!
        assertEquals("Shape", shape.getJSONObject("properties").getString("class"))
        assertTrue(shape.getJSONObject("geometry").getJSONArray("coordinates").length() >= 1)
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())

        // A second recovery must not publish or delete again.
        CaltopoInterruptedTrackJournal.recover("map-test", "archive-folder-test", fixture.runtime)
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
    }

    @Test fun disconnectWithOwnedActiveLiveTrackPublishesArchiveOnceThenMapDown() {
        val drone = CtDroneSpec("RID-DISCONNECT-FINISH")
        setDroneTrackLabel(drone, "1sar7Finish_111500Oct30")
        drone.setOwner("1sar7")
        drone.setCurrentFlightConfirmed(true)
        val track = ownedActiveTrack(drone, "finish-once-live-track")

        val done = CountDownLatch(1)
        CaltopoMap.DisconnectAfterFinishingOwned(CaltopoMap.DISCONNECT_FINISH_TIMEOUT_MS) {
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        drainRecovery()

        assertEquals(1, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        val shape = fixture.calTopoSessionGateway.snapshotOperations()
            .first { it.kind == "editObject" && it.summary == "Shape:finish-once-live-track" }
            .payload!!
        assertEquals("Shape", shape.getJSONObject("properties").getString("class"))
        assertEquals(
            "journal after disconnect: ${CaltopoInterruptedTrackJournal.entriesForTesting()}",
            0,
            CaltopoInterruptedTrackJournal.entriesForTesting().length(),
        )
        assertFalse(track.isActive)
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.down, CaltopoMap.GetMapStatus())
        // Aircraft may still be flying; disconnect must not clear physical flight state.
        assertTrue(drone.isActive)
        assertTrue(drone.isCurrentFlightConfirmed)
    }

    @Test fun hasOwnedActivePublicationIsLockFreeWhileTrackMonitorHeld() {
        val drone = CtDroneSpec("RID-LOCKFREE-CHECK")
        setDroneTrackLabel(drone, "1sar7LockFree_111500Oct30")
        drone.setOwner("1sar7")
        drone.setCurrentFlightConfirmed(true)
        val track = ownedActiveTrack(drone, "lockfree-check-live-track")
        assertTrue(track.hasOwnedActivePublication())

        val hold = CountDownLatch(1)
        val holding = CountDownLatch(1)
        Thread({
            synchronized(track) {
                holding.countDown()
                hold.await(5, TimeUnit.SECONDS)
            }
        }, "hold-livetrack-monitor").start()
        assertTrue(holding.await(2, TimeUnit.SECONDS))

        val startedAtNs = System.nanoTime()
        val owned = track.hasOwnedActivePublication()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs)
        hold.countDown()

        assertTrue(owned)
        assertTrue(
            "hasOwnedActivePublication blocked on track monitor for ${elapsedMs}ms",
            elapsedMs < 100L,
        )
        assertTrue(CaltopoMap.hasOwnedActivePublications())
    }

    @Test fun disconnectWhileFinishInFlightCompletesWithoutWaitingOnArchive() {
        val drone = CtDroneSpec("RID-DISCONNECT-INFLIGHT")
        setDroneTrackLabel(drone, "1sar7InFlight_111500Oct30")
        drone.setOwner("1sar7")
        drone.setCurrentFlightConfirmed(true)
        val track = ownedActiveTrack(drone, "disconnect-inflight-live-track")
        fixture.calTopoSessionGateway.holdRecoveryEdits = true

        track.finishTrack("finish still archiving")
        val cleared = CountDownLatch(1)
        Thread({
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline) {
                if (!track.hasOwnedActivePublication()) {
                    cleared.countDown()
                    return@Thread
                }
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "wait-finish-cleared").start()
        assertTrue(
            "finishTrack did not clear owned publication promptly",
            cleared.await(3, TimeUnit.SECONDS),
        )

        val checkStartedAtNs = System.nanoTime()
        val stillOwned = CaltopoMap.hasOwnedActivePublications()
        val checkElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - checkStartedAtNs)
        assertFalse(stillOwned)
        assertTrue(
            "hasOwnedActivePublications blocked during in-flight finish for ${checkElapsedMs}ms",
            checkElapsedMs < 100L,
        )

        val done = CountDownLatch(1)
        val disconnectStartedAtMs = System.currentTimeMillis()
        CaltopoMap.DisconnectAfterFinishingOwned(CaltopoMap.DISCONNECT_FINISH_TIMEOUT_MS) {
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(
            "disconnect waited on in-flight archive: ${System.currentTimeMillis() - disconnectStartedAtMs}ms",
            System.currentTimeMillis() - disconnectStartedAtMs < 3_000L,
        )
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.down, CaltopoMap.GetMapStatus())

        while (fixture.calTopoSessionGateway.recoveryEditCallbacks.isNotEmpty()) {
            fixture.calTopoSessionGateway.completeRecoveryEdit(true)
        }
        drainRecovery()
    }

    @Test fun disconnectWhenCalTopoUnreachableJournalsOfflineFallback() {
        val drone = CtDroneSpec("RID-DISCONNECT-OFFLINE")
        setDroneTrackLabel(drone, "1sar7Offline_111500Oct30")
        drone.setOwner("1sar7")
        drone.setCurrentFlightConfirmed(true)
        val track = ownedActiveTrack(drone, "offline-finish-live-track")

        fixture.calTopoSessionGateway.failRecoveryEdits = true
        val done = CountDownLatch(1)
        CaltopoMap.DisconnectAfterFinishingOwned(CaltopoMap.DISCONNECT_FINISH_TIMEOUT_MS) {
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        drainRecovery()

        assertEquals(0, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(1, CaltopoInterruptedTrackJournal.entriesForTesting().length())
        assertEquals(
            "offline-finish-live-track",
            CaltopoInterruptedTrackJournal.entriesForTesting().getJSONObject(0).getString("liveTrackId"),
        )
        assertFalse(track.isActive)
        assertEquals(CaltopoMap.MapStatusListener.mapStatus.down, CaltopoMap.GetMapStatus())
    }

    @Test fun mapShutdownPreservesPilotConsentAndPhysicalFlight() {
        val drone = CtDroneSpec("RID-CONTINUING")
        val label = "1SAR7m3_120000Apr28"
        setDroneTrackLabel(drone, label)
        drone.setOwner("1SAR7")
        drone.setCurrentFlightConfirmed(true)
        drone.setFlightReadinessJson("{\"pilot\":{\"callsign\":\"1SAR7\"}}")
        val readiness = drone.flightReadinessJson
        val track = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        track.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        track.setLocalOwner(true)
        forceLiveTrackId(track, "live-continuing")
        track.shutdown(0)
        assertEquals(label, drone.trackLabel())
        org.junit.Assert.assertTrue(drone.isActive)
        org.junit.Assert.assertTrue(drone.isCurrentFlightConfirmed)
        assertEquals(readiness, drone.flightReadinessJson)
        assertEquals("1SAR7", drone.owner)
        track.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.connecting, null, null)
        assertEquals(readiness, drone.flightReadinessJson)
        // Genuine end must still notify confirmation cleanup after publication stopped.
        var ended = 0
        val listener = CaltopoLiveTrack.LocalTrackFinishedListener { remote, _, _ ->
            if (remote == drone.remoteId) ended++
        }
        CaltopoLiveTrack.AddLocalTrackFinishedListener(listener)
        try {
            track.finishTrack("physical flight ended")
            assertEquals(1, ended)
        } finally { CaltopoLiveTrack.RemoveLocalTrackFinishedListener(listener) }
        // The owner of the physical flight then clears its per-flight state.
        drone.reset()
        assertFalse(drone.isActive)
        assertFalse(drone.isCurrentFlightConfirmed)
        assertEquals("{}", drone.flightReadinessJson)
    }

    @Test
    fun shutdownAfterOwnershipLoss_deletesOrphanedLiveTrack() {
        val drone = CtDroneSpec("RID-ORPHAN")
        setDroneTrackLabel(drone, "RID-ORPHAN_120000Apr28")
        val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)

        forceLiveTrackId(liveTrack, "live-RIDORPHAN-test")

        liveTrack.setLocalOwner(false)
        liveTrack.shutdown(0L)

        val operations = fixture.calTopoSessionGateway.snapshotOperations()
        assertEquals(operations.toString(), 1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("addLine"))
    }

    @Test
    fun startLiveTrackCallbackAfterOwnershipLoss_retainsIdForShutdownCleanup() {
        val drone = CtDroneSpec("RID-CALLBACK")
        setDroneTrackLabel(drone, "RID-CALLBACK_120500Apr28")
        val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)

        setPrivateField(liveTrack, "localOwner", false)
        val callback = CaltopoOp(null).apply {
            responseCode = 200
            response = "fake"
            responseJson = org.json.JSONObject().put("id", "live-callback-test")
            setOperationIsDone(true)
        }

        startLiveTrackCompleteMethod().invoke(liveTrack, callback)
        liveTrack.shutdown(0L)

        val operations = fixture.calTopoSessionGateway.snapshotOperations()
        assertEquals(operations.toString(), 1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("addLine"))
    }

    @Test
    fun finishTrackAfterPeerOwnership_notifiesCoordinatorDroneLost() {
        val drone = CtDroneSpec("RID-PEER-OWNED")
        setDroneTrackLabel(drone, "RID-PEER-OWNED_121000Apr28")
        val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        liveTrack.setLocalOwner(false)

        liveTrack.finishTrack("test finished")

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertEquals(1, peerCoordinator.countEvents("onDroneLost"))
        assertEquals(drone.remoteId, peerCoordinator.latestEventOfKind("onDroneLost")?.summary)
    }

    @Test
    fun shutdownAfterPeerOwnershipWithoutLiveTrackId_notifiesCoordinatorDroneLost() {
        val drone = CtDroneSpec("RID-NO-LIVE-ID")
        setDroneTrackLabel(drone, "RID-NO-LIVE-ID_121500Apr28")
        val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        liveTrack.setLocalOwner(false)

        liveTrack.shutdown(0L)

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertEquals(1, peerCoordinator.countEvents("onDroneLost"))
        assertEquals(drone.remoteId, peerCoordinator.latestEventOfKind("onDroneLost")?.summary)
    }

    @Test
    fun finishTrackWithoutLiveTrackId_clearsBufferedPointsBeforeReuse() {
        val drone = CtDroneSpec("RID-BUFFER")
        setDroneTrackLabel(drone, "RID-BUFFER_122000Apr28")
        val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        liveTrack.publishDirect(39.2, -121.2, 501L, 2_000L)

        liveTrack.setLocalOwner(false)
        liveTrack.finishTrack("test segment finished without live track id")

        assertEquals(0, queuedPointCount(liveTrack))

        setDroneTrackLabel(drone, "RID-BUFFER_122500Apr28")
        liveTrack.startNewTrack(39.3, -121.3, 502.0, 3_000L)

        assertEquals(1, queuedPointCount(liveTrack))
    }

    @Test
    fun notifyLocalTrackPoint_allowsListenerRegistrationDuringCallback() {
        val drone = CtDroneSpec("RID-LISTENER")
        setDroneTrackLabel(drone, "RID-LISTENER_123000Apr28")
        val calls = mutableListOf<String>()

        val lateListener = CaltopoLiveTrack.LocalTrackListener { _, _, _, _, _, _ ->
            calls.add("late")
        }
        val firstListener = CaltopoLiveTrack.LocalTrackListener { _, _, _, _, _, _ ->
            calls.add("first")
            CaltopoLiveTrack.AddLocalTrackListener(lateListener)
        }
        val secondListener = CaltopoLiveTrack.LocalTrackListener { _, _, _, _, _, _ ->
            calls.add("second")
        }

        CaltopoLiveTrack.AddLocalTrackListener(firstListener)
        CaltopoLiveTrack.AddLocalTrackListener(secondListener)

        CaltopoLiveTrack.NotifyLocalTrackPoint(drone, 39.1, -121.1, 500.0, 1_000L)

        assertEquals(listOf("first", "second"), calls)
        assertFalse(calls.contains("late"))
    }

    @Test
    fun liveVideoForMappedDrone_addsTabletExternalUrlToPositionReport() {
        val oldTrackerUrl = CaltopoClient.GetTrackerCoordinationUrlPfx()
        val oldDeviceName = R2CActivity.MyDeviceName
        try {
            CaltopoClient.SetTrackerUrlPfx("https://r2c-tracker.com/ncssar")
            R2CActivity.MyDeviceName = "Kjt A5 Pro"
            val peer = fixture.peerCoordinator as FakePeerCoordinator
            peer.updateManagedVideoStreams(
                "Training",
                listOf(ManagedVideoStreamAdvertisement(
                    "00000000-0000-0000-0000-000000000001",
                    "NCS1m3",
                    1920,
                    1080,
                    30.0,
                    4_000_000,
                    "h264"
                ))
            )
            val drone = CtDroneSpec("RID-VIDEO")
            setDroneTrackLabel(drone, "NCS1m3_123500Apr28")
            val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
            liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)

            liveTrack.setLocalOwner(true)
            drainRecovery()
            val callback = CaltopoOp(null).apply {
                responseCode = 200
                response = "fake"
                responseJson = org.json.JSONObject().put("id", fixture.calTopoSessionGateway.snapshotOperations().first { it.kind == "startLiveTrack" }.payload!!.getString("id"))
                setOperationIsDone(true)
            }
            startLiveTrackCompleteMethod().invoke(liveTrack, callback)

            val point = fixture.calTopoSessionGateway.snapshotOperations()
                .first { it.kind == "addLiveTrackPoint" }
            assertEquals(
                "https://r2c-tracker.com/t/Bz2DZg",
                point.payload?.getString("cameraExternalUrl")
            )
            assertFalse(point.payload?.has("cameraThumbnailUrl") == true)
        } finally {
            CaltopoClient.SetTrackerUrlPfx(oldTrackerUrl)
            R2CActivity.MyDeviceName = oldDeviceName
        }
    }

    @Test
    fun newThumbnailRevision_republishesCurrentPointOnlyOnce() {
        val oldTrackerUrl = CaltopoClient.GetTrackerCoordinationUrlPfx()
        val oldDeviceName = R2CActivity.MyDeviceName
        try {
            CaltopoClient.SetTrackerUrlPfx("https://r2c-tracker.com/ncssar")
            R2CActivity.MyDeviceName = "Kjt A5 Pro"
            val peer = fixture.peerCoordinator as FakePeerCoordinator
            val drone = CtDroneSpec("RID-THUMBNAIL")
            setDroneTrackLabel(drone, "NCS1m3_124000Apr28")
            val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
            liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
            liveTrack.setLocalOwner(true)
            drainRecovery()
            forceLiveTrackId(liveTrack, "live-thumbnail-test")
            fixture.calTopoSessionGateway.clear()

            peer.updateManagedVideoStreams(
                "Training",
                listOf(ManagedVideoStreamAdvertisement(
                    "00000000-0000-0000-0000-000000000001",
                    "NCS1m3",
                    1920,
                    1080,
                    30.0,
                    4_000_000,
                    "h264",
                    "live",
                    null,
                    0L,
                    "frame-42",
                    "jpeg",
                )),
            )

            CaltopoLiveTrack.RefreshActiveVideoCameraMetadata()
            CaltopoLiveTrack.RefreshActiveVideoCameraMetadata()

            val updates = fixture.calTopoSessionGateway.snapshotOperations()
                .filter { it.kind == "addLiveTrackPoint" }
            assertEquals(updates.toString(), 1, updates.size)
            assertEquals(39.1, updates.single().payload?.getDouble("lat"))
            assertEquals(
                "https://r2c-tracker.com/r2c-thumbnail/Bz2DZg/00000000-0000-0000-0000-000000000001.jpg?timestamp=frame-42",
                updates.single().payload?.getString("cameraThumbnailUrl"),
            )
        } finally {
            CaltopoClient.SetTrackerUrlPfx(oldTrackerUrl)
            R2CActivity.MyDeviceName = oldDeviceName
        }
    }

    private fun setDroneTrackLabel(drone: CtDroneSpec, trackLabel: String) {
        val mappedId = trackLabel.substringBefore('_')
        CtDroneSpec::class.java.getDeclaredField("mappedId").apply {
            isAccessible = true
            set(drone, mappedId)
        }
        CtDroneSpec::class.java.getDeclaredField("trackLabel").apply {
            isAccessible = true
            set(drone, trackLabel)
        }
    }

    /** Owned active publication without going through async startLiveTrack (avoids dual journal ids). */
    private fun ownedActiveTrack(drone: CtDroneSpec, liveTrackId: String): CaltopoLiveTrack {
        val track = CaltopoLiveTrack(drone, 39.153, -121.133, 527.0, 1_000L)
        track.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        setPrivateField(track, "localOwner", true)
        forceLiveTrackId(track, liveTrackId)
        setPrivateField(track, "startInFlight", false)
        track.publishDirect(39.154, -121.134, 528L, 2_000L)
        drainRecovery()
        journalFile.writeText("""{"entries":[]}""")
        return track
    }

    private fun forceLiveTrackId(liveTrack: CaltopoLiveTrack, liveTrackId: String) {
        setPrivateField(liveTrack, "liveTrackId", liveTrackId)
        setPrivateField(liveTrack, "requestedLiveTrackId", liveTrackId)
        setPrivateField(liveTrack, "publicationMapId", CaltopoMap.GetMapId())
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun queuedPointCount(liveTrack: CaltopoLiveTrack): Int {
        val field = CaltopoLiveTrack::class.java.getDeclaredField("linePoints").apply {
            isAccessible = true
        }
        val points = field.get(liveTrack) as Collection<*>
        return points.size
    }

    private fun clearLocalTrackListeners() {
        listOf("LocalTrackListeners", "LocalTrackFinishedListeners").forEach { fieldName ->
            CaltopoLiveTrack::class.java.getDeclaredField(fieldName).apply {
                isAccessible = true
                (get(null) as MutableCollection<*>).clear()
            }
        }
    }

    private fun startLiveTrackCompleteMethod(): Method =
        CaltopoLiveTrack::class.java.getDeclaredMethod("startLiveTrackComplete", CaltopoOp::class.java).apply {
            isAccessible = true
        }
}
