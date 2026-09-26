package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.ncssar.rid2caltopo.app.R2CActivity
import java.lang.reflect.Field
import java.lang.reflect.Method

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
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    private fun startingTrack(): CaltopoLiveTrack {
        val drone = CtDroneSpec("RID-TIMEOUT")
        setDroneTrackLabel(drone, "RID-TIMEOUT_120000Sep25")
        return CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L).also {
            it.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
            it.setLocalOwner(true)
        }
    }

    private fun reply(id: String? = null) = CaltopoOp(null).apply {
        responseCode = if (id == null) 0 else 200
        if (id != null) responseJson = org.json.JSONObject().put("id", id)
        setOperationIsDone(id != null)
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
        val secondId = gateway.snapshotOperations().last { it.kind == "startLiveTrack" }.payload!!.getString("id")
        org.junit.Assert.assertNotEquals(firstId, secondId)
        gateway.startCallbacks[1].accept(reply(secondId))
        track.finishTrack("second segment ended")
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
        val ids = gateway.snapshotOperations().filter { it.kind == "startLiveTrack" }.map { it.payload!!.getString("id") }
        assertEquals(listOf(id, id), ids)
        assertEquals(0, gateway.countOperations("addLiveTrackPoint"))
        gateway.startCallbacks[1].accept(reply(id))
        assertEquals(1, gateway.countOperations("addLiveTrackPoint"))
        assertEquals(39.3, gateway.snapshotOperations().last { it.kind == "addLiveTrackPoint" }.payload!!.getDouble("lat"), 0.000001)
        repeat(4) { gateway.pointCallbacks[0].accept(reply()) }
        org.junit.Assert.assertTrue(track.isActive)
        track.publishDirect(39.4, -121.4, 503L, 4_000L)
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

    private fun forceLiveTrackId(liveTrack: CaltopoLiveTrack, liveTrackId: String) {
        val liveTrackIdField = CaltopoLiveTrack::class.java.getDeclaredField("liveTrackId").apply {
            isAccessible = true
        }
        liveTrackIdField.set(liveTrack, liveTrackId)
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
