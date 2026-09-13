package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.ncssar.rid2caltopo.BuildConfig
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class TrackerPeerCoordinatorTest {

    private class FakeTransport : TrackerCoordinationTransport {
        private var transportCallback: TrackerCoordinationTransport.Callback? = null
        val sentMessages = CopyOnWriteArrayList<String>()
        var connected = false
        var connectCount = 0
        var disconnectCount = 0
        var autoOpen = true
        var rejectNextSend = false
        var rejectNextMessageType: String? = null

        override fun setCallback(callback: TrackerCoordinationTransport.Callback?) {
            this.transportCallback = callback
        }

        override fun connect(websocketUrl: String, apiKey: String?) {
            connectCount++
            if (autoOpen) {
                open()
            }
        }

        override fun disconnect() {
            disconnectCount++
            connected = false
        }

        override fun isConnected(): Boolean = connected

        override fun send(text: String): Boolean {
            rejectNextMessageType?.let { type ->
                if (JSONObject(text).optString("type") == type) {
                    rejectNextMessageType = null
                    return false
                }
            }
            if (rejectNextSend) {
                rejectNextSend = false
                return false
            }
            sentMessages += text
            return true
        }

        fun open() {
            connected = true
            transportCallback?.onOpen()
        }

        fun fail(responseCode: Int, responseMessage: String) {
            connected = false
            transportCallback?.onFailure(RuntimeException("HTTP $responseCode"), responseCode, responseMessage)
        }

        fun close(code: Int, reason: String) {
            connected = false
            transportCallback?.onClosed(code, reason)
        }

        fun receive(text: String) {
            transportCallback?.onMessage(text)
        }
    }

    private class FakeClock(var nowMs: Long = 1_000L) : TrackerPeerCoordinator.TimeSource {
        override fun now(): Long = nowMs

        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    private class FakeLiveTrack(private val remoteId: String) : LiveTrackOwnerDelegate {
        var localOwnerFlag = false
        var peerWaypointCount = 0

        override fun getRemoteId(): String = remoteId

        override fun setLocalOwner(isOwner: Boolean) {
            localOwnerFlag = isOwner
        }

        override fun onPeerWaypoint(
            sourceZoneId: String,
            lat: Double,
            lng: Double,
            altitudeMeters: Double,
            timestampMsec: Long,
            telemetry: CtDroneSpec.PositionTelemetry?
        ) {
            peerWaypointCount++
        }
    }

    private lateinit var transport: FakeTransport
    private lateinit var coordinator: TrackerPeerCoordinator
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        transport = FakeTransport()
        clock = FakeClock()
        TrackerPeerCoordinator.setTransportFactoryForTesting { transport }
        TrackerPeerCoordinator.setTrackerConfigForTesting("https://tracker.example.org", "tracker-token")
        TrackerPeerCoordinator.setHandoffDelayMsForTesting(0L)
        TrackerPeerCoordinator.setTimeSourceForTesting(clock)
        AppUpdateAdvisory.resetForTesting()
        coordinator = TrackerPeerCoordinator.getInstance()
        CaltopoClient.ResetPersistedClientState()
    }

    @After
    fun tearDown() {
        org.ncssar.rid2caltopo.app.R2CActivity.MyDeviceName = "<unknown>"
        CaltopoClient.ResetPersistedClientState()
        AppUpdateAdvisory.resetForTesting()
        TrackerPeerCoordinator.resetForTesting()
    }

    private fun confirmLocalDrone(remoteId: String = "DRONE1") {
        coordinator.onDroneConfirmed(remoteId, "NCSSAR", "DJI Mini 4 Pro", "1sar7", "1sar7DjMn4Pr")
    }

    @Test
    fun start_connectsTransport() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        assertTrue(transport.connected)
    }

    @Test
    fun hello_includesAppVersionCode() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        val hello = JSONObject(transport.sentMessages.first())

        assertEquals("hello", hello.optString("type"))
        assertEquals("android", hello.optString("appPlatform"))
        assertTrue(hello.optString("deviceModel").isNotBlank())
        assertEquals(BuildConfig.VERSION_CODE, hello.optInt("appVersionCode"))
        assertEquals(BuildConfig.VERSION_NAME, hello.optString("appVersion"))
        assertEquals(
            BuildConfig.TRACKER_FUNCTIONALITY_RELEASE,
            hello.optInt("trackerFunctionalityRelease")
        )
    }

    @Test
    fun reauthenticationRequired_preservesIndependentCredentialsAndStopsTracker() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "credential", "secret"))
        CaltopoClient.SetHomeTrackerCredentials(
            "https://r2c-tracker.com/ncssar",
            "r2c_dev_managed-secret"
        )
        val credentialsBefore = CaltopoClient.GetCaltopoCredentials()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        transport.receive(JSONObject()
            .put("type", "reauthentication_required")
            .put("clearManagedConfiguration", false)
            .put(
                "reauthenticationUrl",
                "https://r2c-tracker.com/ncssar/device-reauthenticate?token=signed"
            )
            .toString())

        assertEquals("r2c_dev_managed-secret", CaltopoClient.GetHomeTrackerApiKey())
        val credentialsAfter = CaltopoClient.GetCaltopoCredentials()
        assertEquals(credentialsBefore.teamId, credentialsAfter.teamId)
        assertEquals(credentialsBefore.credentialId, credentialsAfter.credentialId)
        assertEquals(credentialsBefore.credentialSecret, credentialsAfter.credentialSecret)
        assertTrue(transport.disconnectCount > 0)
    }

    @Test
    fun reauthenticationRequired_clearsTrackerManagedCaltopoButKeepsTrackerToken() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        CaltopoClient.SetTrackerManagedCaltopoCredentials(
            CaltopoCredentials("team", "credential", "secret")
        )
        CaltopoClient.SetHomeTrackerCredentials(
            "https://r2c-tracker.com/ncssar",
            "r2c_dev_managed-secret"
        )
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        transport.receive(JSONObject()
            .put("type", "reauthentication_required")
            .put("clearManagedConfiguration", false)
            .put(
                "reauthenticationUrl",
                "https://r2c-tracker.com/ncssar/device-reauthenticate?token=signed"
            )
            .toString())

        assertEquals("r2c_dev_managed-secret", CaltopoClient.GetHomeTrackerApiKey())
        val credentialsAfter = CaltopoClient.GetCaltopoCredentials()
        assertTrue(credentialsAfter.teamId.isNullOrEmpty())
        assertTrue(credentialsAfter.credentialId.isNullOrEmpty())
        assertTrue(credentialsAfter.credentialSecret.isNullOrEmpty())
        assertTrue(transport.disconnectCount > 0)
    }

    @Test
    fun organizationConfigRequest_returnsNarrowSnapshotWithoutTrackerCredential() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "credential", "secret"))
        CaltopoClient.SetTrackerApiKey("r2c_dev_source-device-secret")
        CaltopoClient.SetTrackerUrlPfx("https://r2c-tracker.com/ncssar")
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        transport.receive(JSONObject()
            .put("type", "organization_config_snapshot_request")
            .put("requestId", "proposal-1")
            .toString())

        val response = transport.sentMessages.map(::JSONObject)
            .last { it.optString("type") == "organization_config_snapshot_response" }
        val snapshot = response.getJSONObject("config")
        assertEquals("proposal-1", response.getString("requestId"))
        assertEquals(1, snapshot.getInt("configSchemaVersion"))
        assertFalse(snapshot.has("organization"))
        assertFalse(snapshot.has("organizationSettings"))
        assertFalse(snapshot.toString().contains("r2c_dev_source-device-secret"))
    }

    @Test
    fun managedVideoPresence_includesTabletTimeZone() {
        org.ncssar.rid2caltopo.app.R2CActivity.MyDeviceName = "Ken's S25 Ultra"
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.updateManagedVideoStreams(
            "Training",
            listOf(
                ManagedVideoStreamAdvertisement(
                    "00000000-0000-0000-0000-000000000001",
                    "1sar7DjMn4Pr",
                    1920,
                    1080,
                    30.0,
                    4_000_000,
                    "h264",
                )
            ),
        )

        val presence = transport.sentMessages
            .map(::JSONObject)
            .last { it.optString("type") == "video_stream_advertisement" }
        assertEquals(
            java.time.ZoneId.systemDefault().id,
            presence.optString("timeZone"),
        )
        assertEquals("Ken's S25 Ultra", presence.optString("deviceName"))
    }

    @Test
    fun managedVideoPresence_sendsEmptySnapshotToClearTracker() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.updateManagedVideoStreams("Training", emptyList())

        val presence = transport.sentMessages
            .map(::JSONObject)
            .last { it.optString("type") == "video_stream_advertisement" }
        assertEquals(0, presence.getJSONArray("streams").length())
    }

    @Test
    fun thumbnailPreviewLease_expiresWithoutExplicitTeardown() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        transport.receive(
            JSONObject()
                .put("type", "video_thumbnail_preview")
                .put("ttlSec", 25)
                .toString()
        )

        assertTrue(coordinator.shouldRefreshManagedVideoThumbnails())
        clock.advanceBy(25_001L)
        assertFalse(coordinator.shouldRefreshManagedVideoThumbnails())
    }

    @Test
    fun helloAckWithNewerRecommendedVersion_triggersUpdateAdvisory() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.handleHelloAckForTesting(BuildConfig.VERSION_CODE + 1, "https://example.org/r2c.apk")

        val state = AppUpdateAdvisory.state.value
        assertEquals(BuildConfig.VERSION_CODE + 1, state.recommendedVersionCode)
        assertEquals("https://example.org/r2c.apk", state.updateUrl)
        assertTrue(state.updateRequired)

        AppUpdateAdvisory.dismissForSession()
        assertFalse(AppUpdateAdvisory.state.value.updateRequired)

        coordinator.handleHelloAckForTesting(BuildConfig.VERSION_CODE + 1, "https://example.org/r2c.apk")
        assertFalse(AppUpdateAdvisory.state.value.updateRequired)
    }

    @Test
    fun helloAckWithCurrentRecommendedVersion_doesNotTriggerUpdateAdvisory() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.handleHelloAckForTesting(BuildConfig.VERSION_CODE, null)

        val state = AppUpdateAdvisory.state.value
        assertEquals(BuildConfig.VERSION_CODE, state.recommendedVersionCode)
        assertFalse(state.updateRequired)
    }

    @Test
    fun managedVideoRequest_isDeliveredToConsentListener() {
        var received: VideoStreamViewRequest? = null
        var deliveryCount = 0
        coordinator.setVideoStreamRequestListener {
            received = it
            deliveryCount += 1
        }
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.updateManagedVideoStreams(
            "Training",
            listOf(
                ManagedVideoStreamAdvertisement(
                    "stream-1", "NCS1m3", 1920, 1080, 30.0, 4_000_000, "h264"
                )
            ),
        )

        transport.receive(
            JSONObject()
                .put("type", "video_stream_request")
                .put("requestId", "request-1")
                .put("requesterEmail", "command@ncssar.example")
                .put("streamSessionId", "stream-1")
                .put("incidentName", "Training")
                .put("droneDesignator", "NCS1m3")
                .put("sourceWidth", 1920)
                .put("sourceHeight", 1080)
                .put("sourceFps", 30.0)
                .put("sourceBitrateBps", 4_000_000)
                .put("sourceCodec", "h264")
                .put("expiresAt", "2026-07-30T18:10:00Z")
                .put("consentRequired", true)
                .toString()
        )

        assertEquals("request-1", received?.requestId)
        assertEquals("command@ncssar.example", received?.requesterEmail)
        assertEquals("Training", received?.incidentName)
        assertEquals("NCS1m3", received?.droneDesignator)
        assertEquals(1920, received?.sourceWidth)
        assertEquals(4_000_000L, received?.sourceBitrateBps)
        assertTrue(received?.consentRequired == true)

        transport.receive(
            JSONObject()
                .put("type", "video_stream_request")
                .put("requestId", "request-1")
                .put("requesterEmail", "command@ncssar.example")
                .put("streamSessionId", "stream-1")
                .put("incidentName", "Training")
                .put("droneDesignator", "NCS1m3")
                .put("expiresAt", "2026-07-30T18:10:00Z")
                .put("consentRequired", true)
                .toString()
        )
        assertEquals(1, deliveryCount)
    }

    @Test
    fun recordingDownloadRequest_preservesRemoteControlConsentPolicy() {
        var received: RecordingDownloadRequest? = null
        coordinator.setVideoStreamRequestListener(object : PeerCoordinator.VideoStreamRequestListener {
            override fun onVideoStreamRequest(request: VideoStreamViewRequest) = Unit
            override fun onRecordingDownloadRequest(request: RecordingDownloadRequest) {
                received = request
            }
        })
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        transport.receive(
            JSONObject()
                .put("type", "recording_download_request")
                .put("requestId", "download-1")
                .put("requesterEmail", "command@ncssar.example")
                .put("streamSessionId", "recording-1")
                .put("droneDesignator", "NCS1m3")
                .put("uploadPath", "/recording-downloads/download-1/content")
                .put("expiresAt", "2026-07-30T18:10:00Z")
                .put("consentRequired", false)
                .toString()
        )

        assertEquals("download-1", received?.requestId)
        assertEquals("/recording-downloads/download-1/content", received?.uploadPath)
        assertEquals("2026-07-30T18:10:00Z", received?.expiresAt)
        assertFalse(received?.consentRequired ?: true)
    }

    @Test
    fun recordingUploadOrigin_convertsWebSocketSchemesToHttp() {
        val method = TrackerPeerCoordinator::class.java.getDeclaredMethod(
            "trackerHttpOrigin",
            String::class.java,
        ).apply { isAccessible = true }

        assertEquals("https://r2c-tracker.com", method.invoke(null, "wss://r2c-tracker.com/ws/r2c"))
        assertEquals("http://localhost:8080", method.invoke(null, "ws://localhost:8080/ws/r2c"))
    }

    @Test
    fun approvedRecordingUploadDoesNotWaitForWebSocketAcknowledgement() {
        val coordinatorSource = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/data/TrackerPeerCoordinator.java"
        )
        val methodStart = coordinatorSource.indexOf(
            "public void uploadRecordingDownload(@NonNull RecordingDownloadRequest request)"
        )
        val methodEnd = coordinatorSource.indexOf(
            "private void uploadRecordingDownloadNow",
            startIndex = methodStart,
        )
        assertTrue(methodStart >= 0 && methodEnd > methodStart)
        val method = coordinatorSource.substring(methodStart, methodEnd)
        assertTrue(method.contains("uploadRecordingDownloadNow(request);"))
        assertFalse(method.contains("return;"))

        val activitySource = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt"
        )
        val approvalStart = activitySource.indexOf("pendingRecordingDownloadRequest?.let")
        val approvalEnd = activitySource.indexOf("dismissButton", startIndex = approvalStart)
        val approval = activitySource.substring(approvalStart, approvalEnd)
        assertTrue(
            approval.indexOf(".uploadRecordingDownload(request)") <
                approval.indexOf(".respondToRecordingDownloadRequest(request.requestId, true)")
        )
    }

    @Test
    fun defaultCoordinatorForwardsRecordingTransferOperations() {
        val source = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/data/DefaultPeerCoordinator.java"
        )
        assertTrue(source.contains(
            "activeCoordinator.respondToRecordingDownloadRequest(requestId, approved);"
        ))
        assertTrue(source.contains(
            "activeCoordinator.uploadRecordingDownload(request);"
        ))
    }

    private fun projectSource(relativePath: String): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(workingDirectory, relativePath),
            File(workingDirectory.parentFile ?: workingDirectory, relativePath),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: fail("Unable to locate source file $relativePath").let { "" }
    }

    @Test
    fun managedVideoRequest_withoutMatchingSource_reportsNoSuchStream() {
        var deliveryCount = 0
        coordinator.setVideoStreamRequestListener { deliveryCount += 1 }
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.updateManagedVideoStreams("Training", emptyList())

        transport.receive(
            JSONObject()
                .put("type", "video_stream_request")
                .put("requestId", "stale-request")
                .put("requesterEmail", "command@ncssar.example")
                .put("streamSessionId", "ended-stream")
                .put("incidentName", "Training")
                .put("droneDesignator", "NCS1m3")
                .toString()
        )

        assertEquals(0, deliveryCount)
        val error = transport.sentMessages
            .map(::JSONObject)
            .last { it.optString("type") == "video_stream_unavailable" }
        assertEquals("stale-request", error.optString("requestId"))
        assertEquals("ended-stream", error.optString("streamSessionId"))
        assertEquals("e_nosuch_stream", error.optString("errorCode"))
    }

    @Test
    fun start_allowsEmptyMapString() {
        coordinator.start("", "zone-alpha", "Alpha", null)

        assertEquals(1, transport.connectCount)
        assertTrue(transport.connected)
    }

    @Test
    fun diagnosticsReportPendingTrackerAck() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(7L, clock.nowMs)

        clock.advanceBy(2_000L)

        assertEquals("Tracker verified", coordinator.coordinationStatusText)
        val diagnosticLines = coordinator.coordinationDiagnosticLines
        assertTrue(diagnosticLines.any { it == "Hello ack 2 sec ago" })
        assertTrue(
            diagnosticLines.toString(),
            diagnosticLines.any { it == "Waiting for heartbeat ack seq 7 2 sec" }
        )
    }

    @Test
    fun ownerAssignedToLocalZone_waitsForLocalSaveBeforePublishing() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)

        assertTrue(coordinator.isLocalOwner("DRONE1"))
        assertFalse(track.localOwnerFlag)
        assertFalse(coordinator.isLocalAlertEligible("DRONE1"))

        confirmLocalDrone()

        assertTrue(track.localOwnerFlag)
        assertTrue(coordinator.isLocalAlertEligible("DRONE1"))
    }

    @Test
    fun localSaveBeforeLiveTrackCreated_publishesWhenTrackStarts() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        confirmLocalDrone("DRONE1")

        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        assertTrue(coordinator.isLocalOwner("DRONE1"))
        assertTrue(track.localOwnerFlag)
        assertTrue(coordinator.isLocalAlertEligible("DRONE1"))
    }

    @Test
    fun localArchiveOnlyDrone_neverRequestsTrackerOwnership() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1").apply { setLocalArchiveOnly(true) }
        val track = FakeLiveTrack("DRONE1")

        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.onWaypointReceived(drone, 39.1, -121.2, 120.0, 50.0, 2000L, null)

        assertFalse(track.localOwnerFlag)
        val messages = transport.sentMessages.map { JSONObject(it) }
        assertTrue(messages.none { it.optString("type") == "first_sighting" && it.optString("remoteId") == "DRONE1" })
        assertEquals("DRONE1", coordinator.getLastWaypointRemoteIdForTesting())
        assertFalse(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun rejectedDroneConfirmationSend_isRetriedAfterHeartbeatAck() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.sentMessages.clear()
        transport.rejectNextMessageType = "drone_confirmed"

        coordinator.onDroneConfirmed(
            "RID-1",
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1sar7",
            "1sar7DjMn4Pr"
        )

        assertTrue(transport.sentMessages.none { JSONObject(it).optString("type") == "drone_confirmed" })

        coordinator.markHeartbeatSentForTesting(1L, clock.now())
        coordinator.handleHeartbeatAckForTesting(1L, 0L)

        val confirmations = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "drone_confirmed" }
        assertEquals(1, confirmations.size)
        assertEquals("RID-1", confirmations.single().optString("remoteId"))
    }

    @Test
    fun standalonePeerConfirmationDoesNotConfirmLocalFlight() {
        coordinator.start("", "zone-alpha", "Alpha", null)
        transport.receive(JSONObject().put("type", "drone_confirmed")
            .put("remoteId", "DRONE9").put("confirmedByGuid", "zone-bravo")
            .put("mappedId", "MA12Autel").put("org", "MA-SAR")
            .put("model", "Autel Evo Max").put("ownerName", "MA12").toString())
        assertFalse(CaltopoClient.IsCurrentPeerDroneConfirmed("DRONE9"))
        assertFalse(CaltopoClient.IsSessionDroneConfirmed("DRONE9"))
    }

    @Test
    fun incomingDroneConfirmedAppliesSessionOnlyDroneSpec() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        transport.receive(
            JSONObject()
                .put("type", "drone_confirmed")
                .put("remoteId", "DRONE9")
                .put("confirmedByGuid", "zone-bravo")
                .put("mappedId", "MA12Autel")
                .put("trackLabel", "MA12Autel")
                .put("org", "MA-SAR")
                .put("model", "Autel Evo Max")
                .put("ownerName", "MA12")
                .toString()
        )

        val drone = CaltopoClient.GetDroneSpec("DRONE9")!!
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed("DRONE9"))
        assertEquals("MA12Autel", drone.mappedId)
        assertEquals("MA-SAR", drone.org)
        assertEquals("Autel Evo Max", drone.model)
        assertEquals("MA12", drone.owner)
        assertEquals(0, CaltopoClient.GetRidmapCount())
    }

    @Test
    fun idleParkDisconnectsAndPositionUpdateDoesNotWake() {
        coordinator.setStandaloneStandbyEligible(true)
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.parkIfIdleForTesting()

        assertEquals(PeerCoordinator.CoordinationIndicatorState.IDLE, coordinator.coordinationIndicatorState)
        assertFalse(transport.connected)
        val idleMessages = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "idle" }
        assertEquals(1, idleMessages.size)
        assertEquals("standalone_standby", idleMessages.single().optString("reason"))
        val connectCountAfterPark = transport.connectCount

        coordinator.updateMyPosition(39.2, -121.2)

        assertEquals(connectCountAfterPark, transport.connectCount)
        assertEquals("Tracker link standby", coordinator.coordinationStatusText)
    }

    @Test
    fun heartbeatAckWhileIdleEligible_doesNotResetIdleParkDeadline() {
        coordinator.setStandaloneStandbyEligible(true)
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        TrackerPeerCoordinator.setIdleParkDelayMsForTesting(200L)
        coordinator.setStandaloneStandbyEligible(false)
        coordinator.setStandaloneStandbyEligible(true)
        assertTrue(transport.connected)

        Thread.sleep(120L)
        coordinator.markHeartbeatSentForTesting(1L, clock.now())
        coordinator.handleHeartbeatAckForTesting(1L, 0L)
        Thread.sleep(120L)

        assertFalse(transport.connected)
        assertEquals(PeerCoordinator.CoordinationIndicatorState.IDLE, coordinator.coordinationIndicatorState)
    }

    @Test
    fun recordedVideoTrackerInteractionPreventsIdleParkUntilRequestEnds() {
        coordinator.setVideoStreamRequestListener { }
        coordinator.setStandaloneStandbyEligible(true)
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.updateManagedVideoStreams(
            "Training",
            listOf(
                ManagedVideoStreamAdvertisement(
                    "recording-1",
                    "NCS1m3",
                    1280,
                    720,
                    30.0,
                    2_000_000,
                    "h264",
                    "recording",
                    "2026-09-05T12:00:00Z",
                    30_000L,
                    "revision-1",
                    null,
                )
            ),
        )
        TrackerPeerCoordinator.setIdleParkDelayMsForTesting(200L)
        coordinator.setStandaloneStandbyEligible(false)
        coordinator.setStandaloneStandbyEligible(true)

        Thread.sleep(120L)
        transport.receive(
            JSONObject()
                .put("type", "video_stream_request")
                .put("requestId", "playback-1")
                .put("requesterEmail", "command@uas4sar.com")
                .put("streamSessionId", "recording-1")
                .put("incidentName", "Training")
                .put("droneDesignator", "NCS1m3")
                .toString()
        )
        Thread.sleep(120L)

        assertTrue(transport.connected)

        transport.receive(
            JSONObject()
                .put("type", "video_stream_request_cancelled")
                .put("requestId", "playback-1")
                .toString()
        )
        Thread.sleep(240L)

        assertFalse(transport.connected)
        assertEquals(PeerCoordinator.CoordinationIndicatorState.IDLE, coordinator.coordinationIndicatorState)
    }

    @Test
    fun activeMapSession_doesNotEnterStandaloneStandby() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        TrackerPeerCoordinator.setIdleParkDelayMsForTesting(50L)

        Thread.sleep(100L)

        assertTrue(transport.connected)
        assertEquals(PeerCoordinator.CoordinationIndicatorState.HEALTHY, coordinator.coordinationIndicatorState)
    }

    @Test
    fun firstSightingWakesParkedCoordinatorAndSendsClaim() {
        coordinator.setStandaloneStandbyEligible(true)
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.parkIfIdleForTesting()
        transport.sentMessages.clear()

        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        assertTrue(transport.connected)
        assertEquals(2, transport.connectCount)
        val firstSightings = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "first_sighting" }
        assertEquals(1, firstSightings.size)
        assertEquals("DRONE1", firstSightings.single().optString("remoteId"))
    }

    @Test
    fun firstSightingOmitsNonFiniteCoordinatesAndDistance() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.sentMessages.clear()

        val drone = CtDroneSpec("DRONE1").apply {
            lastLat = Double.NaN
            lastLng = Double.POSITIVE_INFINITY
            lastAlt = Double.NaN
        }
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, Double.NaN, 1234L)

        val firstSighting = transport.sentMessages
            .map { JSONObject(it) }
            .single { it.optString("type") == "first_sighting" }
        assertEquals("DRONE1", firstSighting.optString("remoteId"))
        assertFalse(firstSighting.has("distanceFromZoneM"))
        assertFalse(firstSighting.has("lat"))
        assertFalse(firstSighting.has("lng"))
        assertFalse(firstSighting.has("altM"))
    }

    @Test
    fun sightingOmitsNonFiniteCoordinatesDistanceAndTelemetry() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 4L)
        transport.sentMessages.clear()

        coordinator.onWaypointReceived(
            drone,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NaN,
            Double.NaN,
            2_000L,
            CtDroneSpec.PositionTelemetry(Double.NaN, 12.5, Double.POSITIVE_INFINITY)
        )

        val sighting = transport.sentMessages
            .map { JSONObject(it) }
            .single { it.optString("type") == "sighting" }
        assertEquals("DRONE1", sighting.optString("remoteId"))
        assertFalse(sighting.has("distanceFromZoneM"))
        assertFalse(sighting.has("lat"))
        assertFalse(sighting.has("lng"))
        assertFalse(sighting.has("altM"))
        val telemetry = sighting.getJSONObject("telemetry")
        assertEquals(12.5, telemetry.getDouble("groundSpeedKnots"), 0.0)
        assertFalse(telemetry.has("verticalRateFpm"))
        assertFalse(telemetry.has("headingDeg"))
    }

    @Test
    fun droneConfirmedWakesParkedCoordinatorAndFlushesSaveEvent() {
        coordinator.setStandaloneStandbyEligible(true)
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.parkIfIdleForTesting()
        transport.sentMessages.clear()

        coordinator.onDroneConfirmed("RID-1", "NCSSAR", "Mavic", "Pilot", "1SAR7DJ")

        assertTrue(transport.connected)
        assertEquals(2, transport.connectCount)
        val confirmations = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "drone_confirmed" }
        assertEquals(1, confirmations.size)
        assertEquals("RID-1", confirmations.single().optString("remoteId"))
    }

    @Test
    fun relaySightingForOwnedDrone_isForwardedToLiveTrack() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        confirmLocalDrone()
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)

        coordinator.handleRelaySightingForTesting(
            "DRONE1",
            "zone-bravo",
            39.1,
            -121.2,
            123.4,
            9876L,
            null
        )

        assertEquals(1, track.peerWaypointCount)
        assertFalse(track.localOwnerFlag.not())
    }

    @Test
    fun nonOwnerSightingsAreThrottledToThreeSecondsPerDrone() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 4L)
        transport.sentMessages.clear()

        coordinator.onWaypointReceived(drone, 39.1, -121.1, 120.0, 50.0, 2_000L, null)
        clock.advanceBy(1_000L)
        coordinator.onWaypointReceived(drone, 39.2, -121.2, 121.0, 55.0, 3_000L, null)
        clock.advanceBy(2_000L)
        coordinator.onWaypointReceived(drone, 39.3, -121.3, 122.0, 60.0, 4_000L, null)

        val sightings = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "sighting" }
        assertEquals(2, sightings.size)
        assertEquals(2_000L, sightings[0].optLong("droneTs"))
        assertEquals(4_000L, sightings[1].optLong("droneTs"))
    }

    @Test
    fun ridTrafficPositionsUseSeparateOneSecondShadowChannel() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        coordinator.onLiveTrackCreated(FakeLiveTrack("DRONE1"), drone, 50.0, 1234L)
        transport.sentMessages.clear()

        coordinator.onWaypointReceived(drone, 39.1, -121.1, 120.0, 50.0, 2_000L, null)
        clock.advanceBy(500L)
        coordinator.onWaypointReceived(drone, 39.2, -121.2, 121.0, 50.0, 2_500L, null)
        clock.advanceBy(500L)
        coordinator.onWaypointReceived(drone, 39.3, -121.3, 122.0, 50.0, 3_000L, null)

        val traffic = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "traffic_position" }
        assertEquals(2, traffic.size)
        assertEquals("rid", traffic[0].optString("source"))
        assertEquals(1L, traffic[0].optLong("seq"))
        assertEquals(2L, traffic[1].optLong("seq"))
        assertEquals(3_000L, traffic[1].optLong("sampleTs"))
        assertTrue(traffic[0].optString("sourceEpoch").isNotEmpty())
    }

    @Test
    fun trackerTrafficScheduleAdjustsShadowSendInterval() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        coordinator.onLiveTrackCreated(FakeLiveTrack("DRONE1"), drone, 50.0, 1234L)
        transport.sentMessages.clear()

        coordinator.onWaypointReceived(drone, 39.1, -121.1, 120.0, 50.0, 2_000L, null)
        val firstTraffic = transport.sentMessages
            .map { JSONObject(it) }
            .first { it.optString("type") == "traffic_position" }
        transport.receive(JSONObject().apply {
            put("type", "traffic_schedule")
            put("remoteId", "DRONE1")
            put("source", "rid")
            put("sourceEpoch", firstTraffic.getString("sourceEpoch"))
            put("seq", 1)
            put("shadowIntervalMs", 4_000)
        }.toString())

        clock.advanceBy(1_000L)
        coordinator.onWaypointReceived(drone, 39.2, -121.2, 121.0, 50.0, 3_000L, null)
        clock.advanceBy(3_000L)
        coordinator.onWaypointReceived(drone, 39.3, -121.3, 122.0, 50.0, 6_000L, null)

        val traffic = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "traffic_position" }
        assertEquals(2, traffic.size)
        assertEquals(6_000L, traffic[1].optLong("sampleTs"))
    }

    @Test
    fun ownerTelemetrySuppressesBackupHeartbeatUntilLivenessWindowAges() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.stopBackgroundTimersForTesting()
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        confirmLocalDrone()
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)
        coordinator.markHeartbeatSentForTesting(1L, clock.now())
        coordinator.handleHeartbeatAckForTesting(1L, 0L)
        transport.sentMessages.clear()

        coordinator.onWaypointReceived(drone, 39.1, -121.1, 120.0, 50.0, 2_000L, null)
        coordinator.sendHeartbeatForTesting()

        var heartbeats = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "heartbeat" }
        assertEquals(0, heartbeats.size)

        clock.advanceBy(31_000L)
        coordinator.onWaypointReceived(drone, 39.2, -121.2, 121.0, 55.0, 3_000L, null)
        coordinator.sendHeartbeatForTesting()

        heartbeats = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "heartbeat" }
        assertEquals(0, heartbeats.size)

        clock.advanceBy(15_000L)
        coordinator.onWaypointReceived(drone, 39.3, -121.3, 122.0, 60.0, 4_000L, null)
        coordinator.sendHeartbeatForTesting()

        heartbeats = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "heartbeat" }
        assertEquals(1, heartbeats.size)
    }

    @Test
    fun peerOwnedDroneDoesNotSuppressTrackerLivenessHeartbeat() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.stopBackgroundTimersForTesting()
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 4L)
        coordinator.markHeartbeatSentForTesting(1L, clock.now())
        coordinator.handleHeartbeatAckForTesting(1L, 0L)
        transport.sentMessages.clear()

        coordinator.sendHeartbeatForTesting()

        val heartbeats = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "heartbeat" }
        assertEquals(1, heartbeats.size)
        assertFalse(track.localOwnerFlag)
    }

    @Test
    fun selfRelayedSighting_isIgnored() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        confirmLocalDrone()
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)

        coordinator.handleRelaySightingForTesting(
            "DRONE1",
            "zone-alpha",
            39.1,
            -121.2,
            123.4,
            9876L,
            null
        )

        assertEquals(0, track.peerWaypointCount)
    }

    @Test
    fun hardAuthFailure_notifiesListenerOnce() {
        var failureCount = 0
        var lastCode = 0
        coordinator.setHardFailureListenerForTesting { responseCode, _ ->
            failureCount++
            lastCode = responseCode
        }

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.fail(403, "Forbidden")
        transport.fail(403, "Forbidden")

        assertEquals(1, failureCount)
        assertEquals(403, lastCode)
    }

    @Test
    fun initialConnectFailureBeforeOpen_schedulesReconnectWithoutHardFailure() {
        transport.autoOpen = false
        var failureCount = 0
        coordinator.setHardFailureListenerForTesting { _, _ -> failureCount++ }

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.fail(503, "Service Unavailable")

        assertFalse(transport.connected)
        assertEquals(1, transport.connectCount)
        assertEquals(0, failureCount)
        assertEquals("failure", coordinator.getLastReconnectCauseForTesting())
        assertEquals(PeerCoordinator.CoordinationIndicatorState.DEGRADED, coordinator.coordinationIndicatorState)
    }

    @Test
    fun trackerServiceOutage_doesNotClearTrackerManagedCaltopoCredentials() {
        CaltopoClient.SetTrackerManagedCaltopoCredentials(
            CaltopoCredentials("team", "credential", "secret")
        )
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.fail(503, "Service Unavailable")

        val credentials = CaltopoClient.GetCaltopoCredentials()
        assertEquals("team", credentials.teamId)
        assertEquals("credential", credentials.credentialId)
        assertEquals("secret", credentials.credentialSecret)
        assertEquals(
            CaltopoClient.CALTOPO_CREDENTIAL_ORIGIN_TRACKER,
            CaltopoClient.GetCaltopoCredentialOrigin()
        )
    }

    @Test
    fun websocketCloseAfterHelloBeforeAck_schedulesReconnect() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        transport.close(1006, "network interrupted")

        assertFalse(transport.connected)
        assertEquals(1, transport.connectCount)
        assertEquals("closed", coordinator.getLastReconnectCauseForTesting())
        assertEquals(PeerCoordinator.CoordinationIndicatorState.DEGRADED, coordinator.coordinationIndicatorState)
    }

    @Test
    fun staleOwnerAssignment_doesNotOverrideNewerLease() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        confirmLocalDrone()

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 9L)
        assertTrue(track.localOwnerFlag)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 8L)

        assertTrue(track.localOwnerFlag)
        assertTrue(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun newerPeerOwnerAssignment_clearsLocalOwnership() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        confirmLocalDrone()

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)
        assertTrue(track.localOwnerFlag)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 5L)

        assertFalse(track.localOwnerFlag)
        assertFalse(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun ownerExpired_clearsLocalOwnership() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        confirmLocalDrone()

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)
        assertTrue(track.localOwnerFlag)

        coordinator.handleOwnerExpiredForTesting("DRONE1")

        assertFalse(track.localOwnerFlag)
        assertFalse(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun delayedOwnerExpiredForPreviousPeer_doesNotClearNewerLocalOwner() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 4L)
        assertFalse(track.localOwnerFlag)

        confirmLocalDrone()
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 5L)
        assertTrue(track.localOwnerFlag)

        transport.receive(
            JSONObject()
                .put("type", "owner_expired")
                .put("remoteId", "DRONE1")
                .put("prevOwnerGuid", "zone-bravo")
                .toString()
        )

        assertTrue(track.localOwnerFlag)
        assertTrue(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun delayedOwnerExpiredForCurrentOwner_clearsLocalOwner() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        confirmLocalDrone()
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 5L)

        transport.receive(
            JSONObject()
                .put("type", "owner_expired")
                .put("remoteId", "DRONE1")
                .put("prevOwnerGuid", "zone-alpha")
                .toString()
        )

        assertFalse(track.localOwnerFlag)
        assertFalse(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun missedHelloAck_forcesReconnectDeterministically() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        clock.advanceBy(10_001L)

        coordinator.checkAckLivenessForTesting()

        assertEquals(1L, coordinator.getForcedReconnectCountForTesting())
        awaitTrue("expected reconnect attempt after missed hello ack") {
            transport.connectCount >= 2
        }
    }

    @Test
    fun delayedHelloAckBeforeTimeout_doesNotForceReconnect() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        clock.advanceBy(9_999L)

        coordinator.checkAckLivenessForTesting()
        coordinator.handleHelloAckForTesting()

        assertEquals(0L, coordinator.getForcedReconnectCountForTesting())
        assertEquals(1, transport.connectCount)
        assertEquals(PeerCoordinator.CoordinationIndicatorState.HEALTHY, coordinator.coordinationIndicatorState)
    }

    @Test
    fun missedHeartbeatAck_forcesReconnectDeterministically() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(9L, clock.now())
        clock.advanceBy(10_001L)

        coordinator.checkAckLivenessForTesting()

        assertEquals(1L, coordinator.getForcedReconnectCountForTesting())
        awaitTrue("expected reconnect attempt after missed heartbeat ack") {
            transport.connectCount >= 2
        }
    }

    @Test
    fun delayedHeartbeatAckBeforeTimeout_isAccepted() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(7L, clock.now())
        clock.advanceBy(9_999L)

        coordinator.checkAckLivenessForTesting()
        coordinator.handleHeartbeatAckForTesting(7L, 0L)

        assertEquals(0L, coordinator.getForcedReconnectCountForTesting())
        assertEquals(7L, coordinator.getLastHeartbeatSeqAckedForTesting())
        assertEquals(1, transport.connectCount)
    }

    @Test
    fun trackerAcknowledgementSilence_reachesStandbyThresholdAfterSixtySeconds() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.stopBackgroundTimersForTesting()

        clock.advanceBy(59_999L)
        assertFalse(coordinator.hasTrackerAcknowledgementSilence(60_000L))

        clock.advanceBy(2L)
        assertTrue(coordinator.hasTrackerAcknowledgementSilence(60_000L))
    }

    @Test
    fun trackerSilence_doesNotTreatCachedOwnershipAsCurrentOperationalWork() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        val drone = CtDroneSpec("DRONE1")
        coordinator.onLiveTrackCreated(FakeLiveTrack("DRONE1"), drone, 25.0, clock.now())
        confirmLocalDrone()
        assertTrue(coordinator.hasOperationalActivityPreventingMapDisconnect())

        coordinator.stopBackgroundTimersForTesting()
        clock.advanceBy(60_001L)

        assertFalse(coordinator.hasOperationalActivityPreventingMapDisconnect())
    }

    @Test
    fun staleHeartbeatAck_isIgnoredWithoutReconnect() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(5L, clock.now())
        coordinator.handleHeartbeatAckForTesting(5L, 0L)
        coordinator.markHeartbeatSentForTesting(6L, clock.now())

        coordinator.handleHeartbeatAckForTesting(4L, 0L)

        assertEquals(0L, coordinator.getForcedReconnectCountForTesting())
        assertEquals(5L, coordinator.getLastHeartbeatSeqAckedForTesting())
        assertEquals(1, transport.connectCount)
    }

    @Test
    fun mismatchedHeartbeatAck_forcesReconnectDeterministically() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(9L, clock.now())

        coordinator.handleHeartbeatAckForTesting(8L, 0L)

        assertEquals(1L, coordinator.getForcedReconnectCountForTesting())
        awaitTrue("expected reconnect attempt after mismatched heartbeat ack") {
            transport.connectCount >= 2
        }
    }

    @Test
    fun repeatedMissedHeartbeatAckWhileReconnectPending_isCoalesced() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(9L, clock.now())
        clock.advanceBy(10_001L)

        coordinator.checkAckLivenessForTesting()
        awaitTrue("expected reconnect attempt after missed heartbeat ack") {
            transport.connectCount >= 2
        }
        val forcedReconnectsAfterFirstCheck = coordinator.getForcedReconnectCountForTesting()

        coordinator.checkAckLivenessForTesting()

        assertEquals(forcedReconnectsAfterFirstCheck, coordinator.getForcedReconnectCountForTesting())
        assertEquals(2, transport.connectCount)
    }

    @Test
    fun reconnectOpenClearsStaleHeartbeatWatchdogState() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(19L, clock.now())
        clock.advanceBy(10_001L)

        coordinator.checkAckLivenessForTesting()
        awaitTrue("expected reconnect attempt after missed heartbeat ack") {
            transport.connectCount >= 2
        }

        transport.open()
        coordinator.stopBackgroundTimersAndResetHeartbeatStateForTesting()
        coordinator.handleHelloAckForTesting()
        clock.advanceBy(10_001L)
        coordinator.checkAckLivenessForTesting()

        assertEquals(1L, coordinator.getForcedReconnectCountForTesting())
        assertEquals(2, transport.connectCount)
    }

    @Test
    fun queuedDroneConfirmation_flushesAfterDelayedOpen() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.sentMessages.clear()

        coordinator.onDroneConfirmed("RID-QUEUED", "NCSSAR", "Mavic", "Pilot", "1SAR7DJ")

        assertTrue(transport.sentMessages.none { JSONObject(it).optString("type") == "drone_confirmed" })
        assertTrue(transport.connectCount >= 2)

        transport.open()

        val confirmations = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "drone_confirmed" }
        assertEquals(1, confirmations.size)
        assertEquals("RID-QUEUED", confirmations.single().optString("remoteId"))
    }

    @Test
    fun queuedFirstSighting_flushesAfterDelayedOpen() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.sentMessages.clear()

        val drone = CtDroneSpec("RID-FIRST")
        val track = FakeLiveTrack("RID-FIRST")
        coordinator.onLiveTrackCreated(track, drone, 42.0, 1234L)

        assertTrue(transport.sentMessages.none { JSONObject(it).optString("type") == "first_sighting" })
        assertTrue(transport.connectCount >= 2)

        transport.open()

        val firstSightings = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "first_sighting" }
        assertEquals(1, firstSightings.size)
        assertEquals("RIDFIRST", firstSightings.single().optString("remoteId"))
        assertEquals(42.0, firstSightings.single().optDouble("distanceFromZoneM"), 0.0)
    }

    @Test
    fun unconfiguredTracker_staysStandaloneWithoutConnectingTransport() {
        TrackerPeerCoordinator.setTrackerConfigForTesting("", "")

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.updateMyPosition(39.2, -121.2)

        assertEquals(0, transport.connectCount)
        assertEquals(PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED, coordinator.coordinationIndicatorState)
        assertEquals("Tracker link not configured", coordinator.coordinationStatusText)
        assertTrue(coordinator.coordinationDiagnosticLines.contains("Tracker websocket not configured"))
    }

    private fun awaitTrue(message: String, timeoutMs: Long = 500L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        if (!condition()) {
            fail(message)
        }
    }

}
