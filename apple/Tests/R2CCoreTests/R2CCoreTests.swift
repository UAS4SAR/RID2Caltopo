import Foundation
import Testing

@Test func organizationAccessRequiresAuthenticationForOrganizationOrCaltopoTeamsAccount() {
    #expect(!OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(organizationName: ""))
    #expect(!OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(organizationName: "  \n"))
    #expect(OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(organizationName: "NCSSAR"))
    #expect(OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(
        organizationName: "",
        trackerURLPrefix: "https://r2c-tracker.com/ncssar",
        trackerAPIKey: "token"
    ))
    #expect(!OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(
        organizationName: "",
        trackerURLPrefix: "https://r2c-tracker.com/ncssar",
        trackerAPIKey: ""
    ))
    #expect(OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(
        organizationName: "",
        caltopoTeamID: "team",
        caltopoCredentialID: "credential",
        caltopoCredentialSecret: "secret"
    ))
    #expect(!OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(
        organizationName: "",
        caltopoTeamID: "team",
        caltopoCredentialID: "credential",
        caltopoCredentialSecret: ""
    ))
}

@Test func organizationAuthenticationSurvivesOnlyBriefBackgroundInactivity() {
    let backgroundedAt = Date(timeIntervalSince1970: 1_000)
    #expect(OrganizationAccessPolicy.authenticatedSessionRemainsValid(
        accessWasGranted: true,
        backgroundedAt: nil,
        resumedAt: backgroundedAt.addingTimeInterval(100)
    ))
    #expect(OrganizationAccessPolicy.authenticatedSessionRemainsValid(
        accessWasGranted: true,
        backgroundedAt: backgroundedAt,
        resumedAt: backgroundedAt.addingTimeInterval(14.999)
    ))
    #expect(!OrganizationAccessPolicy.authenticatedSessionRemainsValid(
        accessWasGranted: true,
        backgroundedAt: backgroundedAt,
        resumedAt: backgroundedAt.addingTimeInterval(15)
    ))
    #expect(!OrganizationAccessPolicy.authenticatedSessionRemainsValid(
        accessWasGranted: false,
        backgroundedAt: backgroundedAt,
        resumedAt: backgroundedAt
    ))
}

@Test func operationalMapTrackFreshnessPrefersOnlyStrictlyNewerPeerSamples() {
    let local = Date(timeIntervalSince1970: 1_000)

    #expect(OperationalMapTrackFreshness.prefersPeer(
        localSampleAt: local,
        peerSampleAt: local.addingTimeInterval(0.001)
    ))
    #expect(!OperationalMapTrackFreshness.prefersPeer(
        localSampleAt: local,
        peerSampleAt: local
    ))
    #expect(!OperationalMapTrackFreshness.prefersPeer(
        localSampleAt: local,
        peerSampleAt: local.addingTimeInterval(-0.001)
    ))
    #expect(OperationalMapTrackFreshness.prefersPeer(
        localSampleAt: nil,
        peerSampleAt: local
    ))
}

@Test func appleTrackerEnrollmentLinksOpenTheInstalledApp() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let contentView = try String(
        contentsOf: appleRoot.appendingPathComponent("App/ContentView.swift"),
        encoding: .utf8
    )
    let enrollmentClient = try String(
        contentsOf: appleRoot.appendingPathComponent("App/RidMappingAdminView.swift"),
        encoding: .utf8
    )
    let deviceIdentity = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleNetworkAddress.swift"),
        encoding: .utf8
    )
    let importer = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleOrgConfigImporter.swift"),
        encoding: .utf8
    )
    let infoPlist = try String(
        contentsOf: appleRoot.appendingPathComponent("App/Info.plist"),
        encoding: .utf8
    )
    let entitlements = try String(
        contentsOf: appleRoot.appendingPathComponent("App/RID2CaltopoApple.entitlements"),
        encoding: .utf8
    )

    #expect(contentView.contains("AppleTrackerEnrollmentClient.normalizedEnrollmentURL"))
    #expect(contentView.contains("trackerReauthenticationBrowserOpen"))
    #expect(contentView.contains("organizationAccessEvaluated = false"))
    #expect(contentView.contains("!organizationAccessEvaluated"))
    #expect(contentView.contains("Checking protected access…"))
    #expect(contentView.contains(
        "if organizationAuthenticationRequired, !organizationAccessGranted"
    ))
    #expect(contentView.contains("Tracker sign-in required"))
    #expect(contentView.contains("Button(\"Sign in\")"))
    #expect(contentView.contains("Button(\"Continue offline\", role: .cancel)"))
    #expect(contentView.contains("showTrackerReauthenticationPrompt = true"))
    #expect(contentView.contains("of: peerCoordinator.reauthenticationRequiredGeneration,"))
    #expect(contentView.contains("initial: true"))
    #expect(enrollmentClient.contains("static let appLinkScheme = \"r2cenroll\""))
    #expect(enrollmentClient.contains("fetchManagedOrganizationConfig"))
    #expect(enrollmentClient.contains("\"installation_id\": AppleDeviceIdentity.installationID()"))
    #expect(deviceIdentity.contains("keychainInstallationID()"))
    #expect(deviceIdentity.contains("storeKeychainInstallationID"))
    #expect(importer.contains("AppleManagedOrganizationConfig.apply"))
    #expect(importer.contains("AppleTrackerEnrollmentClient.normalizedEnrollmentURL(tokenText)"))
    #expect(importer.contains("importTrackerEnrollment(\n                    normalizedTrackerEnrollment,"))
    #expect(importer.contains("allowedContentTypes: [.image, .json, .plainText, .data]"))
    #expect(importer.contains("VNDetectBarcodesRequest()"))
    #expect(importer.contains("request.symbologies = [.qr]"))
    #expect(importer.contains("AppleTrackerEnrollmentClient.normalizedEnrollmentURL(payload)"))
    #expect(importer.contains("AndroidConfigTokenCodec.decode(payload)"))
    #expect(infoPlist.contains("<string>r2cenroll</string>"))
    #expect(entitlements.contains("<string>applinks:r2c-tracker.com</string>"))
    #expect(entitlements.contains("<string>applinks:prerel.r2c-tracker.com</string>"))
}
@testable import R2CCore

@Test func seiPositionContinuationRequiresRidAgreementOncePerStreamEpoch() {
    var continuation = OperationalSEIPositionContinuation()

    let mismatched = continuation.acceptHorizontalPosition(
        latitudeDegrees: 39.1,
        longitudeDegrees: -121.1,
        ridLatitudeDegrees: 39.2,
        ridLongitudeDegrees: -121.2,
        sourceTimestampMicroseconds: 1_000_000
    )
    #expect(!mismatched)
    let validated = continuation.acceptHorizontalPosition(
        latitudeDegrees: 39.2,
        longitudeDegrees: -121.2,
        ridLatitudeDegrees: 39.2,
        ridLongitudeDegrees: -121.2,
        sourceTimestampMicroseconds: 2_000_000
    )
    #expect(validated)
    let continued = continuation.acceptHorizontalPosition(
        latitudeDegrees: 39.205,
        longitudeDegrees: -121.205,
        ridLatitudeDegrees: 39.2,
        ridLongitudeDegrees: -121.2,
        sourceTimestampMicroseconds: 3_000_000
    )
    #expect(continued)
}

@Test func seiPositionContinuationRequiresRevalidationAfterSourceTimestampRestart() {
    var continuation = OperationalSEIPositionContinuation()
    let validated = continuation.acceptHorizontalPosition(
        latitudeDegrees: 39.2,
        longitudeDegrees: -121.2,
        ridLatitudeDegrees: 39.2,
        ridLongitudeDegrees: -121.2,
        sourceTimestampMicroseconds: 5_000_000
    )
    #expect(validated)
    let restartedAwayFromRID = continuation.acceptHorizontalPosition(
        latitudeDegrees: 39.205,
        longitudeDegrees: -121.205,
        ridLatitudeDegrees: 39.2,
        ridLongitudeDegrees: -121.2,
        sourceTimestampMicroseconds: 100_000
    )
    #expect(!restartedAwayFromRID)
}

@Test func seiRelativeUpRequiresRidAgreementThenContinuesUntilStreamRestart() {
    var continuation = OperationalSEIPositionContinuation()
    let mismatched = continuation.acceptRelativeUp(
        observedRelativeUpMeters: 80,
        ridAltitudeMeters: 510,
        takeoffReportedAltitudeMeters: 500
    )
    #expect(!mismatched)
    let validated = continuation.acceptRelativeUp(
        observedRelativeUpMeters: 10,
        ridAltitudeMeters: 510,
        takeoffReportedAltitudeMeters: 500
    )
    #expect(validated)
    let continued = continuation.acceptRelativeUp(
        observedRelativeUpMeters: 120,
        ridAltitudeMeters: 510,
        takeoffReportedAltitudeMeters: 500
    )
    #expect(continued)
    continuation.reset()
    let afterReset = continuation.acceptRelativeUp(
        observedRelativeUpMeters: 120,
        ridAltitudeMeters: 510,
        takeoffReportedAltitudeMeters: 500
    )
    #expect(!afterReset)
}

@Test func trackerPeerTrafficWireIncludesFreshnessAndSourceIdentity() throws {
    let client = TrackerCoordinationClient(
        mapID: "MAP1", zoneID: "zone-a", name: "Alpha", appVersion: "2.1", appVersionCode: 160
    )
    let sighting = TrackerCoordinationSighting(
        identity: TrackerCoordinationIdentity(
            remoteID: "RID-1", mappedID: "1SAR7DJ", organization: "", model: "", ownerName: ""
        ),
        droneTimestampMilliseconds: 1_234,
        latitude: 39.2,
        longitude: -121.2,
        altitudeMeters: 140,
        headingDegrees: 87
    )
    let data = try TrackerCoordinationWire.trafficPosition(
        client: client, sighting: sighting, source: "sei", sourceEpoch: "epoch-1", sequence: 9,
        altitudeSampleTimestampMilliseconds: 1_100,
        altitudeCalibration: TrackerTrafficAltitudeCalibration(
            flightEpoch: "flight-1",
            state: "locked",
            correctionMeters: 12.5,
            lockedAtMilliseconds: 1_000,
            demSource: "usgs-geotiff-local-1m",
            demResolutionMeters: 1
        ),
        proximityAlertDistanceFeet: 100
    )
    let object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    #expect(object["type"] as? String == "traffic_position")
    #expect(object["sampleTs"] as? Int == 1_234)
    #expect(object["source"] as? String == "sei")
    #expect(object["sourceEpoch"] as? String == "epoch-1")
    #expect(object["seq"] as? Int == 9)
    #expect(object["altSampleTs"] as? Int == 1_100)
    #expect(object["flightEpoch"] as? String == "flight-1")
    #expect(object["altCalibrationState"] as? String == "locked")
    #expect(object["mslAltM"] as? Double == 152.5)
    #expect(object["mslAltSampleTs"] as? Int == 1_100)
    #expect(object["padFt"] as? Double == 100)
    #expect(object["headingDeg"] as? Double == 87)
}

@Test func trackerPeerTrafficParsesWithoutChangingOwnership() throws {
    var state = TrackerCoordinationProtocolState(localZoneID: "zone-local")
    let data = try JSONSerialization.data(withJSONObject: [
        "type": "peer_traffic_position",
        "fromZoneId": "zone-peer",
        "remoteId": "RID-1",
        "mappedId": "1SAR7DJ",
        "source": "sei",
        "sourceEpoch": "epoch-1",
        "seq": 9,
        "sampleTs": 1_234,
        "receivedTs": 1_500,
        "lat": 39.2,
        "lng": -121.2,
        "altM": 140.0,
        "altSampleTs": 1_100,
        "flightEpoch": "flight-1",
        "altCalibrationState": "locked",
        "mslAltM": 152.5,
        "mslAltSampleTs": 1_100,
        "altCorrectionM": 12.5,
        "demSource": "usgs-geotiff-local-1m",
        "demResolutionM": 1.0,
        "incidentPadFt": 80.0,
        "shadowNearestDistanceM": 50.0,
        "shadowSchedulingPadFt": 100.0,
        "shadowIntervalMs": 2_000,
    ])
    let events = try state.handleIncoming(data, receivedAtMilliseconds: 2_000)
    guard case let .peerTrafficPosition(traffic) = events.first else {
        Issue.record("Expected peer traffic event")
        return
    }
    #expect(traffic.sourceZoneID == "zone-peer")
    #expect(traffic.source == "sei")
    #expect(traffic.sampleTimestampMilliseconds == 1_234)
    #expect(traffic.altitudeSampleTimestampMilliseconds == 1_100)
    #expect(traffic.flightEpoch == "flight-1")
    #expect(traffic.altitudeCalibrationState == "locked")
    #expect(traffic.mslAltitudeMeters == 152.5)
    #expect(traffic.mslAltitudeSampleTimestampMilliseconds == 1_100)
    #expect(traffic.altitudeCorrectionMeters == 12.5)
    #expect(traffic.demSource == "usgs-geotiff-local-1m")
    #expect(traffic.demResolutionMeters == 1.0)
    #expect(traffic.incidentPadFeet == 80.0)
    #expect(traffic.shadowNearestDistanceMeters == 50.0)
    #expect(traffic.shadowSchedulingPadFeet == 100.0)
    #expect(traffic.shadowIntervalMilliseconds == 2_000)
    #expect(!state.isLocalOwner(remoteID: "RID-1"))
}

@Test func trackerTrafficScheduleParsesAndClampsInterval() throws {
    var state = TrackerCoordinationProtocolState(localZoneID: "zone-local")
    let data = try JSONSerialization.data(withJSONObject: [
        "type": "traffic_schedule",
        "remoteId": "RID-1",
        "source": "sei",
        "sourceEpoch": "epoch-1",
        "seq": 9,
        "shadowIntervalMs": 50_000,
        "incidentPadFt": 80.0,
        "shadowNearestDistanceM": 50.0,
        "shadowSchedulingPadFt": 100.0,
    ])
    let events = try state.handleIncoming(data, receivedAtMilliseconds: 2_000)
    guard case let .trafficSchedule(schedule) = events.first else {
        Issue.record("Expected traffic schedule event")
        return
    }
    #expect(schedule.remoteID == "RID-1")
    #expect(schedule.sourceEpoch == "epoch-1")
    #expect(schedule.intervalMilliseconds == 16_000)
    #expect(schedule.incidentPadFeet == 80.0)
}

@Test func trackerStandbyPolicyRequiresVerifiedQuietStandaloneSession() {
    #expect(TrackerStandbyPolicy.normalizedDelaySeconds(nil) == 30)
    #expect(TrackerStandbyPolicy.normalizedDelaySeconds(1) == 5)
    #expect(TrackerStandbyPolicy.normalizedDelaySeconds(7_200) == 3_600)
    #expect(TrackerStandbyPolicy.isEligible(
        standalone: true,
        connected: true,
        helloAcknowledged: true,
        configurationSyncInProgress: false,
        activeSightings: 0,
        pendingConfirmations: 0,
        hasLiveVideo: false,
        activeMediaConnections: 0
    ))
    #expect(!TrackerStandbyPolicy.isEligible(
        standalone: false,
        connected: true,
        helloAcknowledged: true,
        configurationSyncInProgress: false,
        activeSightings: 0,
        pendingConfirmations: 0,
        hasLiveVideo: false,
        activeMediaConnections: 0
    ))
    #expect(!TrackerStandbyPolicy.isEligible(
        standalone: true,
        connected: true,
        helloAcknowledged: true,
        configurationSyncInProgress: true,
        activeSightings: 0,
        pendingConfirmations: 0,
        hasLiveVideo: false,
        activeMediaConnections: 0
    ))
    #expect(!TrackerStandbyPolicy.isEligible(
        standalone: true,
        connected: true,
        helloAcknowledged: true,
        configurationSyncInProgress: false,
        activeSightings: 0,
        pendingConfirmations: 0,
        hasLiveVideo: false,
        activeMediaConnections: 0,
        activeTrackerInteractions: 1
    ))
}

@Test func archiveFolderAgeUsesLargestRequestedWholeUnit() {
    #expect(ArchiveFolderDisplay.age(59) == "<1 minute")
    #expect(ArchiveFolderDisplay.age(60) == "1 minute")
    #expect(ArchiveFolderDisplay.age(59 * 60) == "59 minutes")
    #expect(ArchiveFolderDisplay.age(60 * 60) == "1 hour")
    #expect(ArchiveFolderDisplay.age(23 * 60 * 60) == "23 hours")
    #expect(ArchiveFolderDisplay.age(24 * 60 * 60) == "1 day")
    #expect(ArchiveFolderDisplay.age(29 * 24 * 60 * 60) == "29 days")
    #expect(ArchiveFolderDisplay.age(30 * 24 * 60 * 60) == "1 month")
    #expect(ArchiveFolderDisplay.age(364 * 24 * 60 * 60) == "12 months")
    #expect(ArchiveFolderDisplay.age(365 * 24 * 60 * 60) == "1 year")
}

@Test func archiveFolderSizeMatchesAndroidUnits() {
    #expect(ArchiveFolderDisplay.size(512) == "512 B")
    #expect(ArchiveFolderDisplay.size(1_536) == "1.5 KB")
    #expect(ArchiveFolderDisplay.size(2 * 1_024 * 1_024) == "2.0 MB")
    #expect(ArchiveFolderDisplay.size(3 * 1_024 * 1_024 * 1_024) == "3.0 GB")
}

@Test func approvedAppleRecordingUploadDoesNotWaitForWebSocketAcknowledgement() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let source = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleTrackerCoordinator.swift"),
        encoding: .utf8
    )
    let approvalStart = try #require(source.range(of: "func approveRecordingDownloadRequest()"))
    let approvalEnd = try #require(
        source.range(of: "func declineRecordingDownloadRequest()", range: approvalStart.upperBound..<source.endIndex)
    )
    let approval = source[approvalStart.lowerBound..<approvalEnd.lowerBound]
    #expect(approval.contains("uploadRecording(request)"))

    let ackStart = try #require(source.range(of: "if type == \"recording_download_decision_ack\""))
    let ackEnd = try #require(
        source.range(of: "if type == \"video_stream_request_cancelled\"", range: ackStart.upperBound..<source.endIndex)
    )
    let acknowledgement = source[ackStart.lowerBound..<ackEnd.lowerBound]
    #expect(!acknowledgement.contains("uploadRecording(request)"))
}

@Test func appleQueuesTrackerReceiveBeforeTheOpenCallback() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let source = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleTrackerCoordinator.swift"),
        encoding: .utf8
    )
    let connectStart = try #require(source.range(of: "private func connect()"))
    let receiveLoopStart = try #require(
        source.range(of: "private func startReceiveLoop(", range: connectStart.upperBound..<source.endIndex)
    )
    let connect = source[connectStart.lowerBound..<receiveLoopStart.lowerBound]
    #expect(connect.contains("socket.resume()"))
    #expect(connect.contains("startReceiveLoop(socket: socket, generation: currentGeneration)"))

    let openedStart = try #require(source.range(of: "private func socketOpened(generation:"))
    let openedEnd = try #require(
        source.range(of: "private func received(", range: openedStart.upperBound..<source.endIndex)
    )
    let opened = source[openedStart.lowerBound..<openedEnd.lowerBound]
    #expect(!opened.contains("startReceiveLoop"))
}

@Test func appleThumbnailCaptureRepublishesCurrentCaltopoMetadata() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let coordinator = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleTrackerCoordinator.swift"),
        encoding: .utf8
    )
    let viewModel = try String(
        contentsOf: appleRoot.appendingPathComponent("App/RIDTrackViewModel.swift"),
        encoding: .utf8
    )

    #expect(coordinator.contains("managedVideoThumbnailUpdatedHandler?(droneDesignator)"))
    #expect(coordinator.contains("let candidates = managedVideoStreams.filter"))
    #expect(coordinator.contains("if leftIsLive != rightIsLive { return leftIsLive }"))
    #expect(coordinator.contains("for advertisement in candidates.prefix(8)"))
    #expect(!coordinator.contains("for advertisement in managedVideoStreams.prefix(8)"))
    #expect(viewModel.contains("refreshCaltopoThumbnailMetadata(droneDesignator: droneDesignator)"))
    #expect(viewModel.contains("observation: track.lastObservation"))
}

@Test func appleLateStreamAndManualLongPressBothOpenTelemetryPairing() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let contentView = try String(
        contentsOf: appleRoot.appendingPathComponent("App/ContentView.swift"),
        encoding: .utf8
    )
    let mapView = try String(
        contentsOf: appleRoot.appendingPathComponent("App/RIDTrackMapView.swift"),
        encoding: .utf8
    )
    let streamsView = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleStreamRegistry.swift"),
        encoding: .utf8
    )

    #expect(contentView.contains("Presenting telemetry pairing prompt for late stream="))
    #expect(contentView.contains("droneConfirmations.isCurrentFlightConfirmed(track.aircraftID)"))
    #expect(mapView.contains("Manual telemetry pairing requested stream="))
    #expect(streamsView.contains("LongPressGesture(minimumDuration: 0.5)"))
}

@Test func trackerReauthenticationChallengeDecodesFastAPIErrorResponse() throws {
    let data = try JSONSerialization.data(withJSONObject: [
        "detail": [
            "code": "reauthentication_required",
            "reauthentication_url": "https://r2c-tracker.com/ncssar/device-reauthenticate?token=test",
        ],
    ])
    #expect(
        TrackerReauthenticationChallenge.url(fromHTTPError: data, statusCode: 403)?.absoluteString
            == "https://r2c-tracker.com/ncssar/device-reauthenticate?token=test"
    )
    #expect(TrackerReauthenticationChallenge.url(fromHTTPError: data, statusCode: 500) == nil)
    #expect(TrackerReauthenticationChallenge.url(fromHTTPError: Data(), statusCode: 403) == nil)
}

@Test func rejectedTrackerAuthorizationProvidesReenrollmentGuidance() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let coordinator = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleTrackerCoordinator.swift"),
        encoding: .utf8
    )
    let contentView = try String(
        contentsOf: appleRoot.appendingPathComponent("App/ContentView.swift"),
        encoding: .utf8
    )
    #expect(coordinator.contains("Tracker authorization rejected; re-enrollment required"))
    #expect(contentView.contains("Tracker re-enrollment required"))
    #expect(contentView.contains("scan a current"))
    #expect(contentView.contains("organization enrollment QR"))
    #expect(contentView.contains("lastTrackerReenrollmentNoticeCredential != credential"))
}

@Test func trackerEnrollmentResponseCarriesImmediateReauthenticationChallenge() throws {
    let data = try JSONSerialization.data(withJSONObject: [
        "credential": [
            "state": "reauth_required",
            "reauthentication_url": "https://r2c-tracker.com/ncssar/device-reauthenticate?token=test",
        ],
    ])
    #expect(
        TrackerReauthenticationChallenge.url(fromEnrollmentResponse: data)?.absoluteString
            == "https://r2c-tracker.com/ncssar/device-reauthenticate?token=test"
    )
    let untrusted = try JSONSerialization.data(withJSONObject: [
        "credential": [
            "state": "reauth_required",
            "reauthentication_url": "https://example.test/steal",
        ],
    ])
    #expect(TrackerReauthenticationChallenge.url(fromEnrollmentResponse: untrusted) == nil)
}

@Test func trackerBrowserReturnRetriesOnlyWhenNoCallbackIsPending() {
    #expect(TrackerReauthenticationBrowserReturnPolicy.shouldRetryCredential(
        browserWasOpen: true,
        challengeURLPresent: true,
        callbackPending: false
    ))
    #expect(!TrackerReauthenticationBrowserReturnPolicy.shouldRetryCredential(
        browserWasOpen: false,
        challengeURLPresent: true,
        callbackPending: false
    ))
    #expect(!TrackerReauthenticationBrowserReturnPolicy.shouldRetryCredential(
        browserWasOpen: true,
        challengeURLPresent: false,
        callbackPending: false
    ))
    #expect(!TrackerReauthenticationBrowserReturnPolicy.shouldRetryCredential(
        browserWasOpen: true,
        challengeURLPresent: true,
        callbackPending: true
    ))
}

@Test func directAppleTrackerEnrollmentRefreshesHomeProfileAndOpensReauthentication() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let source = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleOrgConfigImporter.swift"),
        encoding: .utf8
    )
    let start = try #require(source.range(of: "func importTrackerEnrollment("))
    let end = try #require(
        source.range(of: "func importToken(", range: start.upperBound..<source.endIndex)
    )
    let enrollment = source[start.lowerBound..<end.lowerBound]
    #expect(enrollment.contains("profileLifecycle.captureHome"))
    #expect(enrollment.contains("trackerReauthenticationRequiredHandler?(url)"))
}

@Test func appleTrackerCredentialRefreshForcesCoordinatorReconnect() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let contentView = try String(
        contentsOf: appleRoot.appendingPathComponent("App/ContentView.swift"),
        encoding: .utf8
    )
    let coordinator = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleTrackerCoordinator.swift"),
        encoding: .utf8
    )

    #expect(contentView.contains("notams.refreshNow(location: locationProvider.lastLocation)\n                    configurePeerCoordinator(forceReconnect: true)"))
    #expect(contentView.contains("Reauthentication completed; configuration preserved"))
    #expect(contentView.contains("configurePeerCoordinator(forceReconnect: true)"))
    #expect(contentView.contains("resumeTrackerAfterBrowserReturnIfNeeded"))
    #expect(contentView.contains("callbackPending: trackerCallbackPending"))
    #expect(contentView.contains("trackerReauthenticationURL = nil"))
    #expect(contentView.contains("without an app callback; retrying Tracker access"))
    #expect(coordinator.contains("guard !unchanged || forceReconnect else { return }"))
    #expect(coordinator.contains("trackerConfigurationChanged || forceReconnect"))
    #expect(coordinator.contains("reauthenticationURL = nil"))
}

@Test func appleWiFiIdentityRefreshesAfterPermissionAndLifecycleChanges() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let contentView = try String(
        contentsOf: appleRoot.appendingPathComponent("App/ContentView.swift"),
        encoding: .utf8
    )
    let diagnostics = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleNetworkAddress.swift"),
        encoding: .utf8
    )

    #expect(contentView.contains(".onChange(of: locationProvider.authorizationStatus)"))
    #expect(contentView.contains("refresh(reason: .locationAuthorizationChanged)"))
    #expect(contentView.contains("refresh(reason: .applicationBecameActive)"))
    #expect(diagnostics.contains("self.latestPath = path"))
    #expect(diagnostics.contains("refresh(reason: RefreshReason) async"))
    #expect(diagnostics.contains("self.record(path: path, reason: .networkPathChanged)"))
}

@Test func appleDeviceLocationProactivelyPrefetchesTerrain() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let contentView = try String(
        contentsOf: appleRoot.appendingPathComponent("App/ContentView.swift"),
        encoding: .utf8
    )
    let viewModel = try String(
        contentsOf: appleRoot.appendingPathComponent("App/RIDTrackViewModel.swift"),
        encoding: .utf8
    )
    let terrainService = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleTerrainElevationService.swift"),
        encoding: .utf8
    )

    #expect(contentView.contains("ridTracks.prefetchTerrainForDeviceLocation("))
    #expect(viewModel.contains("await terrainService.prefetch(latitude: latitude, longitude: longitude)"))
    #expect(viewModel.contains("latitude: track.lastObservation.latitude"))
    #expect(viewModel.contains("Task(priority: .utility)"))
    #expect(terrainService.contains("func prefetch(latitude: Double, longitude: Double)"))
    #expect(terrainService.contains("guard scheduledPrefetchCells.insert(cell).inserted else { return }"))
}

@Test func managedVideoProbeQueueKeepsEnoughDataInFlightForRoutedLinks() {
    #expect(ManagedVideoProbeQueuePolicy.maySend(chunksSentInBurst: 0, bufferedBytes: 0))
    #expect(ManagedVideoProbeQueuePolicy.maySend(
        chunksSentInBurst: 15,
        bufferedBytes: 1023 * 1024
    ))
    #expect(!ManagedVideoProbeQueuePolicy.maySend(chunksSentInBurst: 16, bufferedBytes: 0))
    #expect(!ManagedVideoProbeQueuePolicy.maySend(
        chunksSentInBurst: 0,
        bufferedBytes: 1024 * 1024
    ))
}

@Test func managedVideoSDPAddsRoutableCandidateToItsMediaSection() {
    let answer = """
    v=0
    m=video 9 UDP/TLS/RTP/SAVPF 96
    a=mid:0
    a=end-of-candidates
    m=audio 9 UDP/TLS/RTP/SAVPF 111
    a=mid:1
    """
    let candidate = ManagedVideoICECandidate(
        sdp: "candidate:1 1 udp 1 203.0.113.1 5000 typ srflx raddr 0.0.0.0 rport 0",
        mediaLineIndex: 0
    )

    let completed = ManagedVideoSDP.withICECandidates(
        answer,
        candidates: [candidate]
    )

    #expect(completed.contains(
        "a=candidate:1 1 udp 1 203.0.113.1 5000 typ srflx "
    ))
    #expect(completed.range(of: "a=candidate:")!.lowerBound
        < completed.range(of: "a=end-of-candidates")!.lowerBound)
    #expect(ManagedVideoSDP.hasRoutableICECandidate(completed))
    #expect(!ManagedVideoSDP.hasRelayICECandidate(completed))

    let relayed = ManagedVideoSDP.withICECandidates(
        answer,
        candidates: [ManagedVideoICECandidate(
            sdp: "candidate:2 1 udp 1 192.0.2.10 6000 typ relay raddr 0.0.0.0 rport 0",
            mediaLineIndex: 0
        )]
    )
    #expect(ManagedVideoSDP.hasRelayICECandidate(relayed))
}

@Test func managedVideoPresenceRequiresRecentDecodedFrames() {
    #expect(!ManagedVideoPresencePolicy.hasRecentDecodedFrame(
        frameCount: 0,
        decodedFrameAge: nil
    ))
    #expect(ManagedVideoPresencePolicy.hasRecentDecodedFrame(
        frameCount: 1,
        decodedFrameAge: 5.9
    ))
    #expect(!ManagedVideoPresencePolicy.hasRecentDecodedFrame(
        frameCount: 20,
        decodedFrameAge: 6.1
    ))
}

@Test func managedVideoSourceGraceTreatsCatalogedRecordingAsAvailable() {
    let unavailable = ManagedVideoPresencePolicy.unavailableApprovedSessionIDs(
        approvedSessionIDs: ["live", "recording", "missing"],
        liveSessionIDs: ["live"],
        recordingSessionIDs: ["recording"]
    )

    #expect(unavailable == ["missing"])
}

@Test
func managedVideoQualityPolicyBuildsCompleteNamedPresets() {
    let options = ManagedVideoQualityPolicy.options(
        sourceWidth: 3840,
        sourceHeight: 2160,
        sourceFps: 30,
        sourceBitrateBps: 12_000_000,
        usableUplinkBps: 5_000_000
    )

    #expect(options.map(\.preset) == ["High", "Balanced", "Low", "Emergency"])
    #expect(options.first?.width == 1280)
    #expect(options.first?.height == 720)
    #expect(options.first?.fps == 30)
    #expect(options.first?.estimatedBitrateBps == 3_000_000)
    #expect(options.map { "\($0.width)x\($0.height)" }.contains("960x540"))
    #expect(options.map { "\($0.width)x\($0.height)" }.contains("640x360"))
    #expect(options.allSatisfy { $0.width <= 3840 && $0.height <= 2160 })
    #expect(options.allSatisfy { $0.width.isMultiple(of: 2) && $0.height.isMultiple(of: 2) })
}

@Test
func managedVideoQualityPolicyOffersNominalThirtyForBurstyControllerCadence() {
    let options = ManagedVideoQualityPolicy.options(
        sourceWidth: 1280,
        sourceHeight: 720,
        sourceFps: 21,
        sourceBitrateBps: 0,
        usableUplinkBps: 10_000_000
    )

    #expect(options.first?.width == 1280)
    #expect(options.first?.height == 720)
    #expect(options.first?.fps == 30)
    #expect(options.first?.capacity == .enough)
}

@Test
func managedVideoQualityPolicyDoesNotUpscaleSmallSources() {
    let options = ManagedVideoQualityPolicy.options(
        sourceWidth: 640,
        sourceHeight: 480,
        sourceFps: 8,
        sourceBitrateBps: 0,
        usableUplinkBps: 2_000_000
    )

    #expect(Set(options.map { "\($0.width)x\($0.height)" }) == ["640x480"])
    #expect(Set(options.map(\.fps)) == Set([8, 5]))
    #expect(options.allSatisfy { $0.estimatedBitrateBps >= 100_000 })
}

@Test
func managedVideoQualityPolicyEnablesOnlySmallestFailedLowFallback() {
    let options = ManagedVideoQualityPolicy.options(
        sourceWidth: 1280,
        sourceHeight: 720,
        sourceFps: 30,
        sourceBitrateBps: 1_900_000,
        usableUplinkBps: 100_000
    )
    let startable = options.filter { $0.capacity != .insufficient }

    #expect(startable.count == 1)
    #expect(startable.first?.capacity == .fallback)
    #expect(startable.first?.width == 640)
    #expect(startable.first?.height == 360)
    #expect(startable.first?.fps == 5)
    #expect(startable.first?.estimatedBitrateBps ?? .max <= 200_000)
}

private actor CaltopoFolderResolverProbe {
    private(set) var fetchCount = 0
    private(set) var createdFolders: [String] = []
    private(set) var deletedFolders: [String] = []
    let snapshots: [CaltopoArtifactSnapshot]

    init(snapshot: CaltopoArtifactSnapshot = CaltopoArtifactSnapshot()) {
        snapshots = [snapshot]
    }

    init(snapshots: [CaltopoArtifactSnapshot]) {
        self.snapshots = snapshots
    }

    func fetch() async -> CaltopoArtifactSnapshot {
        let index = min(fetchCount, snapshots.count - 1)
        fetchCount += 1
        try? await Task.sleep(for: .milliseconds(20))
        return snapshots[index]
    }

    func create(title: String, visible: Bool, labelVisible: Bool) -> String {
        createdFolders.append(title)
        return visible && labelVisible ? "active-created" : "archive-created"
    }

    func delete(folderID: String) {
        deletedFolders.append(folderID)
    }

    func counts() -> (fetches: Int, creations: Int) {
        (fetchCount, createdFolders.count)
    }

    func deletions() -> [String] {
        deletedFolders
    }
}

@Test
func caltopoArchiveFolderNameSeparatesTrackFolderAndDate() {
    let name = CaltopoTrackFolderResolver.archiveFolderName(
        trackFolderName: " Drone Tracks ",
        date: Date(timeIntervalSince1970: 1_721_779_200),
        timeZone: TimeZone(secondsFromGMT: 0)!
    )

    #expect(name == "Drone Tracks 24Jul")
}

@Test
func caltopoFolderResolutionCoalescesConcurrentPublishers() async throws {
    let resolver = CaltopoTrackFolderResolver()
    let probe = CaltopoFolderResolverProbe()
    let date = Date(timeIntervalSince1970: 1_721_779_200) // 24 Jul 2024 UTC

    async let first = resolver.resolve(
        trackFolderName: "Drone Tracks",
        date: date,
        timeZone: TimeZone(secondsFromGMT: 0)!,
        fetchSnapshot: { await probe.fetch() },
        createFolder: { title, visible, labelVisible in
            await probe.create(title: title, visible: visible, labelVisible: labelVisible)
        }
    )
    async let second = resolver.resolve(
        trackFolderName: "Drone Tracks",
        date: date,
        timeZone: TimeZone(secondsFromGMT: 0)!,
        fetchSnapshot: { await probe.fetch() },
        createFolder: { title, visible, labelVisible in
            await probe.create(title: title, visible: visible, labelVisible: labelVisible)
        }
    )

    let results = try await [first, second]
    #expect(results[0] == CaltopoTrackFolderIDs(active: "active-created", archive: "archive-created"))
    #expect(results[1] == results[0])
    let counts = await probe.counts()
    #expect(counts.fetches == 2)
    #expect(counts.creations == 2)
}

@Test
func caltopoFolderResolutionKeepsPopulatedArchiveWithoutDeletingPreexistingFolders() async throws {
    let resolver = CaltopoTrackFolderResolver()
    let snapshot = CaltopoArtifactSnapshot(
        folders: [
            CaltopoArtifactFolder(id: "active", title: "Drone Tracks", initiallyVisible: true),
            CaltopoArtifactFolder(id: "archive-empty", title: "Drone Tracks 24Jul", initiallyVisible: false),
            CaltopoArtifactFolder(id: "archive-used", title: "Drone Tracks 24Jul", initiallyVisible: false),
            CaltopoArtifactFolder(
                id: "archive-with-child",
                title: "Drone Tracks 24Jul",
                initiallyVisible: false
            ),
            CaltopoArtifactFolder(
                id: "child",
                title: "Child Folder",
                initiallyVisible: false,
                parentID: "archive-with-child"
            ),
        ],
        items: [
            CaltopoArtifactItem(
                id: "track",
                title: "1SAR7DJI_204510Jul24",
                folderID: "archive-used",
                className: "Shape"
            ),
        ]
    )
    let probe = CaltopoFolderResolverProbe(snapshot: snapshot)

    let result = try await resolver.resolve(
        trackFolderName: "Drone Tracks",
        date: Date(timeIntervalSince1970: 1_721_779_200),
        timeZone: TimeZone(secondsFromGMT: 0)!,
        fetchSnapshot: { await probe.fetch() },
        createFolder: { title, visible, labelVisible in
            await probe.create(title: title, visible: visible, labelVisible: labelVisible)
        },
        deleteFolder: { folderID in
            await probe.delete(folderID: folderID)
        }
    )

    #expect(result == CaltopoTrackFolderIDs(active: "active", archive: "archive-used"))
    #expect(await probe.deletions().isEmpty)
}

@Test
func caltopoFolderResolutionDeletesOnlyItsOwnUnusedRaceLosers() async throws {
    let resolver = CaltopoTrackFolderResolver()
    let initial = CaltopoArtifactSnapshot()
    let settled = CaltopoArtifactSnapshot(folders: [
        CaltopoArtifactFolder(id: "active-other", title: "Drone Tracks", initiallyVisible: true),
        CaltopoArtifactFolder(id: "active-created", title: "Drone Tracks", initiallyVisible: true),
        CaltopoArtifactFolder(
            id: "archive-other",
            title: "Drone Tracks 24Jul",
            initiallyVisible: false
        ),
        CaltopoArtifactFolder(
            id: "archive-created",
            title: "Drone Tracks 24Jul",
            initiallyVisible: false
        ),
    ])
    let probe = CaltopoFolderResolverProbe(snapshots: [initial, settled])

    let result = try await resolver.resolve(
        trackFolderName: "Drone Tracks",
        date: Date(timeIntervalSince1970: 1_721_779_200),
        timeZone: TimeZone(secondsFromGMT: 0)!,
        fetchSnapshot: { await probe.fetch() },
        createFolder: { title, visible, labelVisible in
            await probe.create(title: title, visible: visible, labelVisible: labelVisible)
        },
        deleteFolder: { folderID in
            await probe.delete(folderID: folderID)
        }
    )

    #expect(result == CaltopoTrackFolderIDs(active: "active-other", archive: "archive-other"))
    #expect(Set(await probe.deletions()) == Set(["active-created", "archive-created"]))
}

@Test
func caltopoFolderResolutionReusesExistingDailyFolder() async throws {
    let resolver = CaltopoTrackFolderResolver()
    let probe = CaltopoFolderResolverProbe(snapshot: CaltopoArtifactSnapshot(folders: [
        CaltopoArtifactFolder(id: "active-existing", title: "DRONE TRACKS", initiallyVisible: true),
        CaltopoArtifactFolder(id: "archive-existing", title: "drone tracks 24jul", initiallyVisible: false),
    ]))
    let result = try await resolver.resolve(
        trackFolderName: "Drone Tracks",
        date: Date(timeIntervalSince1970: 1_721_779_200),
        timeZone: TimeZone(secondsFromGMT: 0)!,
        fetchSnapshot: { await probe.fetch() },
        createFolder: { title, visible, labelVisible in
            await probe.create(title: title, visible: visible, labelVisible: labelVisible)
        }
    )

    #expect(result == CaltopoTrackFolderIDs(active: "active-existing", archive: "archive-existing"))
    let counts = await probe.counts()
    #expect(counts.fetches == 1)
    #expect(counts.creations == 0)
}

@Test
func caltopoMarkerIconURLMatchesAndroidContract() throws {
    let url = try #require(CaltopoMarkerIcon.url(symbol: "clue", colorHex: " #FF00AA "))
    let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    #expect(components.scheme == "https")
    #expect(components.host == "caltopo.com")
    #expect(components.path == "/icon@2x.png")
    #expect(components.queryItems == [URLQueryItem(name: "cfg", value: "clue,FF00AA")])
    #expect(CaltopoMarkerIcon.url(symbol: "", colorHex: nil)?.absoluteString ==
        "https://caltopo.com/icon@2x.png?cfg=point")
}

@Test
func anomalyConfigurationMatchesAndroidColorDefaults() {
    #expect(abs(
        AnomalyConfigurationParity.scoreThreshold(sensitivity: 0.59) -
        Float(pow(15.0, 0.41))
    ) < 0.0001)
    #expect(AnomalyConfigurationParity.motionEvidenceScale(sensitivity: 0.60) == 1.0)
    #expect(abs(
        AnomalyConfigurationParity.minimumAreaFraction(base: 0.0015, sensitivity: 0.59) -
        0.002708535
    ) < 0.000001)
    #expect(AnomalyConfigurationParity.pixelStep(isColorMode: true, configuredStep: 0) == 1)
    #expect(AnomalyConfigurationParity.colorFrontendMode(isColorMode: true, configuredMode: 0) == 1)
    #expect(AnomalyConfigurationParity.usesColorRealtimeCadence(
        isColorMode: true,
        strideMode: 0,
        frameStride: 1,
        adaptiveMinimumFrames: 2,
        adaptiveMaximumSeconds: 1
    ))
}

@Test
func anomalyConfigurationPreservesExplicitNonDefaultCadence() {
    #expect(!AnomalyConfigurationParity.usesColorRealtimeCadence(
        isColorMode: true,
        strideMode: 0,
        frameStride: 4,
        adaptiveMinimumFrames: 2,
        adaptiveMaximumSeconds: 1
    ))
    #expect(!AnomalyConfigurationParity.usesColorRealtimeCadence(
        isColorMode: false,
        strideMode: 0,
        frameStride: 1,
        adaptiveMinimumFrames: 2,
        adaptiveMaximumSeconds: 1
    ))
}

@Test
func anomalyGuideGeometryMatchesAndroidScanFrameAndTargetScale() {
    let guide = AnomalyConfigurationParity.guideGeometry(
        frameWidth: 1_600,
        frameHeight: 900,
        scanZone: 0.50,
        smallTargetScreenFraction: 1.0 / 200.0
    )
    #expect(guide.scanWidth == 800)
    #expect(guide.scanHeight == 450)
    #expect(abs(guide.targetSpan - hypot(1_600.0, 900.0) / 200.0) < 0.0001)

    let clamped = AnomalyConfigurationParity.guideGeometry(
        frameWidth: 100,
        frameHeight: 50,
        scanZone: 0.1,
        smallTargetScreenFraction: 1
    )
    #expect(clamped.scanWidth == 50)
    #expect(clamped.scanHeight == 25)
    #expect(abs(clamped.targetSpan - hypot(100.0, 50.0) * 0.03) < 0.0001)
}

@Test
func anomalyDisplayRectTracksAspectFittedVideoInsteadOfTheGridTile() {
    let portraitTile = AnomalyConfigurationParity.aspectFitRect(
        containerWidth: 1_200,
        containerHeight: 1_440,
        contentAspectRatio: 16.0 / 9.0
    )
    #expect(portraitTile == AnomalyDisplayRect(
        x: 0,
        y: 382.5,
        width: 1_200,
        height: 675
    ))

    let wideTile = AnomalyConfigurationParity.aspectFitRect(
        containerWidth: 1_600,
        containerHeight: 600,
        contentAspectRatio: 16.0 / 9.0
    )
    #expect(abs(wideTile.x - (1_600 - (600 * 16.0 / 9.0)) / 2) < 0.0001)
    #expect(wideTile.y == 0)
    #expect(abs(wideTile.width - (600 * 16.0 / 9.0)) < 0.0001)
    #expect(wideTile.height == 600)
}

@Test
func caltopoLocalDevicePointCanBeSuppressedWithoutRemovingItsFolderItem() {
    let point = CaltopoPointArtifact(
        id: "LOCAL-ZONE",
        coordinate: MapCoordinate(latitude: 39, longitude: -105),
        title: "R2C: Jerry's iPad Pro",
        symbol: "radiotower",
        colorHex: "#0000FF",
        folderID: "drone-tracks",
        parentItemID: nil
    )
    let item = CaltopoArtifactItem(
        id: point.id,
        title: point.title,
        folderID: point.folderID,
        className: "Marker"
    )
    let snapshot = CaltopoArtifactSnapshot(points: [point], items: [item])

    let rendered = snapshot.excludingRenderedPointIDs(["local-zone"])

    #expect(rendered.points.isEmpty)
    #expect(rendered.items == [item])
}

@Test
func staleR2CDeviceMarkersRequireExplicitOwnershipAndExpiredHeartbeat() throws {
    let nowMilliseconds: Int64 = 1_700_000_500_000
    let data = Data(#"""
    {"state":{"features":[
      {"id":"stale","geometry":{"type":"Point","coordinates":[-105,39]},"properties":{"class":"Marker","title":"R2C: Old tablet","folderId":"tracks","r2c-guid":"old-guid","r2c-last-seen-epoch-ms":1700000000000}},
      {"id":"fresh","geometry":{"type":"Point","coordinates":[-105,39]},"properties":{"class":"Marker","title":"R2C: Current tablet","folderId":"tracks","r2c-guid":"new-guid","r2c-last-seen-epoch-ms":1700000400000}},
      {"id":"manual","geometry":{"type":"Point","coordinates":[-105,39]},"properties":{"class":"Marker","title":"R2C: Manually named","folderId":"tracks","updated":1600000000000}}
    ]}}
    """#.utf8)
    let snapshot = try CaltopoArtifactDecoder.decode(data: data)

    #expect(snapshot.staleR2CDeviceMarkerIDs(
        now: Date(timeIntervalSince1970: Double(nowMilliseconds) / 1_000),
        staleAfter: 180
    ) == ["stale"])
}

@Test
func incidentMapAutoDisconnectRequiresFiveQuietMinutesAndNoOperationalWork() {
    let connectedAt = Date(timeIntervalSince1970: 1_700_000_000)
    let idle = IncidentMapOperationalState(
        connectedToIncidentMap: true,
        activeFlightCount: 0,
        lastRIDMessageAt: nil,
        mapConnectedAt: connectedAt,
        hasManagedVideoOrTransfer: false,
        offlineMapPreparationActive: false
    )
    #expect(!IncidentMapAutoDisconnectPolicy.isOperationallyIdle(
        idle,
        now: connectedAt.addingTimeInterval(299)
    ))
    #expect(IncidentMapAutoDisconnectPolicy.isOperationallyIdle(
        idle,
        now: connectedAt.addingTimeInterval(300)
    ))

    let activeFlight = IncidentMapOperationalState(
        connectedToIncidentMap: true,
        activeFlightCount: 1,
        lastRIDMessageAt: connectedAt,
        mapConnectedAt: connectedAt,
        hasManagedVideoOrTransfer: false,
        offlineMapPreparationActive: false
    )
    #expect(!IncidentMapAutoDisconnectPolicy.isOperationallyIdle(
        activeFlight,
        now: connectedAt.addingTimeInterval(3_600)
    ))
}

@Test
func incidentMapRelocationRequiresAccurateFiftyFootMovementAfterQuietPeriod() {
    let connectedAt = Date(timeIntervalSince1970: 1_700_000_000)
    let now = connectedAt.addingTimeInterval(301)
    let state = IncidentMapOperationalState(
        connectedToIncidentMap: true,
        activeFlightCount: 0,
        lastRIDMessageAt: nil,
        mapConnectedAt: connectedAt,
        hasManagedVideoOrTransfer: false,
        offlineMapPreparationActive: false
    )
    var guardState = IncidentMapRelocationGuard()

    let firstEvaluation = guardState.evaluate(
        latitude: 39.153,
        longitude: -121.132,
        horizontalAccuracyMeters: 5,
        operationalState: state,
        now: now
    )
    let secondEvaluation = guardState.evaluate(
        latitude: 39.1532,
        longitude: -121.132,
        horizontalAccuracyMeters: 5,
        operationalState: state,
        now: now
    )
    #expect(!firstEvaluation)
    #expect(secondEvaluation)
}

@Test
func incidentMapScreenOffAnchorSurvivesActiveFlightUntilPolicyBecomesIdle() {
    let connectedAt = Date(timeIntervalSince1970: 1_700_000_000)
    var guardState = IncidentMapRelocationGuard()
    guardState.arm(
        latitude: 39.153,
        longitude: -121.132,
        horizontalAccuracyMeters: 5
    )
    let active = IncidentMapOperationalState(
        connectedToIncidentMap: true,
        activeFlightCount: 1,
        lastRIDMessageAt: connectedAt,
        mapConnectedAt: connectedAt,
        hasManagedVideoOrTransfer: false,
        offlineMapPreparationActive: false
    )
    let disconnectedWhileActive = guardState.evaluate(
        latitude: 39.1532,
        longitude: -121.132,
        horizontalAccuracyMeters: 5,
        operationalState: active,
        now: connectedAt.addingTimeInterval(600)
    )
    #expect(!disconnectedWhileActive)
    let idle = IncidentMapOperationalState(
        connectedToIncidentMap: true,
        activeFlightCount: 0,
        lastRIDMessageAt: connectedAt,
        mapConnectedAt: connectedAt,
        hasManagedVideoOrTransfer: false,
        offlineMapPreparationActive: false
    )
    let disconnectedWhenIdle = guardState.evaluate(
        latitude: 39.1532,
        longitude: -121.132,
        horizontalAccuracyMeters: 5,
        operationalState: idle,
        now: connectedAt.addingTimeInterval(600)
    )
    #expect(disconnectedWhenIdle)
}

@Test
func operationalDeviceNameUpgradesGenericAppleFallback() {
    #expect(OperationalDeviceName.preferredDisplayName(
        stored: "iPad",
        userAssigned: "iPad",
        hostname: "Jerrys-Ipad-pro.coredevice.local"
    ) == "Jerry's iPad Pro")
    #expect(OperationalDeviceName.preferredDisplayName(
        stored: "customer.sltyutx1.isp.starlink.com",
        userAssigned: "iPad",
        hostname: "Jerrys-Ipad-pro.coredevice.local"
    ) == "Jerry's iPad Pro")
}

@Test
func operationalDeviceNamePreservesExplicitOverrideAndRejectsOpaqueHostname() {
    #expect(OperationalDeviceName.preferredDisplayName(
        stored: "SAR Command Tablet",
        userAssigned: "Jerry’s Ipad pro",
        hostname: "Jerrys-Ipad-pro.coredevice.local"
    ) == "SAR Command Tablet")
    #expect(OperationalDeviceName.displayName(
        fromHostname: "E8CF81B4-A917-5456-91DD-EC1743193D48.coredevice.local"
    ) == nil)
}

@Test func operationalAppleDeviceModelUsesMarketingFamily() {
    #expect(OperationalDeviceModelName.apple(machineIdentifier: "iPad16,4") == "iPad Pro")
    #expect(OperationalDeviceModelName.apple(machineIdentifier: "iPad14,8") == "iPad Air")
    #expect(OperationalDeviceModelName.apple(machineIdentifier: "iPad16,1") == "iPad mini")
    #expect(OperationalDeviceModelName.apple(machineIdentifier: "iPad13,18") == "iPad")
}

@Test func mediaMTXRuntimeConfigurationMatchesAndroidCaptureSettings() throws {
    let base = Data("rtmp: yes\npathDefaults:\n  source: publisher\n".utf8)
    let root = URL(fileURLWithPath: "/tmp/stream archive")

    let disabled = try String(
        decoding: MediaMTXRuntimeConfiguration.build(
            base: base,
            captureStreams: false,
            recordingRoot: root
        ),
        as: UTF8.self
    )
    #expect(disabled.contains("pathDefaults:\n  record: no\n  source: publisher"))
    #expect(!disabled.contains("recordFormat: fmp4"))

    let enabled = try String(
        decoding: MediaMTXRuntimeConfiguration.build(
            base: base,
            captureStreams: true,
            recordingRoot: root
        ),
        as: UTF8.self
    )
    #expect(enabled.contains("pathDefaults:\n  record: yes"))
    #expect(enabled.contains(
        "recordPath: '/tmp/stream archive/%path/%path_%Y-%m-%d_%H-%M-%S-%f'"
    ))
    #expect(enabled.contains("recordFormat: fmp4"))
}

@Test func caltopoTeamMapHierarchyMatchesAndroidFolderRelationsAndRecents() throws {
    let now = Date(timeIntervalSince1970: 2_000_000_000)
    let recent = Int64(now.timeIntervalSince1970 * 1_000) - 1_000
    let payload = """
    {"accounts":[{"id":"acct","properties":{"title":"NCSSAR"}}],
     "rels":[{"properties":{"class":"UserAccountMapRel","mapId":"map2","folderId":"folder"}}],
     "features":[
       {"id":"folder","properties":{"class":"UserFolder","accountId":"acct","label":"Incidents"}},
       {"id":"map1","properties":{"class":"CollaborativeMap","folderId":"folder","title":"Older","updated":100}},
       {"id":"map2","properties":{"class":"CollaborativeMap","title":"Current Incident","updated":\(recent)}}]}
    """
    let roots = try CaltopoTeamMapDecoder.decode(data: Data(payload.utf8), now: now)
    #expect(roots.first?.title == "Recent Activity")
    let account = try #require(roots.first(where: { $0.id == "acct" }))
    let folder = try #require(account.children?.first(where: { $0.id == "folder" }))
    #expect(folder.children?.map(\.id) == ["map2", "map1"])
    #expect(folder.children?.first?.map?.title == "Current Incident")
}

@Test func caltopoTeamMapRequestUsesAndroidAccountEndpointAndSignedGet() async throws {
    // This key produces a signature containing both "+" and "/", exercising
    // the query encoding that URLComponents.queryItems does not provide.
    let secret = Data("team-secret-1".utf8).base64EncodedString()
    let configuration = CaltopoTeamMapConfiguration(
        domainAndPort: "caltopo.com", teamID: "team-42", credentialID: "credential-7",
        credentialSecretBase64: secret
    )
    let client = try CaltopoTeamMapClient(configuration: configuration)
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    let request = try await client.makeRequest(now: now)
    #expect(request.httpMethod == "GET")
    #expect(request.url?.path == "/api/v1/acct/team-42/since/0")
    let requestURL = try #require(request.url)
    let components = try #require(URLComponents(url: requestURL, resolvingAgainstBaseURL: false))
    let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    #expect(query["id"] == "credential-7")
    #expect(query["expires"] == "1700000120000")
    #expect(!(query["signature"] ?? "").isEmpty)
    #expect(query["signature"] == "uwNMwY6YwT7qcXhOp1yBhFPXFk779c+rP83s3O/SWjI=")
    #expect(requestURL.absoluteString.contains("c%2BrP83s3O%2FSWjI%3D"))
    #expect(!requestURL.absoluteString.contains("c+rP83s3O/SWjI"))
}

@Test func caltopoConfigurationErrorsAreOperatorReadable() {
    #expect(
        CaltopoLiveClientError.invalidConfiguration.localizedDescription
            == "The CalTopo domain, team, map, or credential configuration is incomplete."
    )
    #expect(
        CaltopoLiveClientError.invalidCredentialSecret.localizedDescription
            == "The CalTopo credential secret is not valid Base64."
    )
}

@Test func geoTiffElevationSourceSamplesDownloadedUSGSTileDirectly() throws {
    let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("r2c-dem-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let tile = directory.appendingPathComponent("USGS_1_n40w105.tif")
    try makeFloatGeoTiff().write(to: tile)

    let bounds = try #require(GeoTiffElevationSource.tileBounds(fileName: tile.lastPathComponent))
    #expect(bounds.south == 39 && bounds.north == 40)
    #expect(bounds.west == -105 && bounds.east == -104)
    let source = GeoTiffElevationSource(directory: directory)
    #expect(abs((source.sampleElevationMeters(latitude: 39.5, longitude: -104.5) ?? 0) - 250) < 0.001)
    #expect(source.sampleElevationMeters(latitude: 38, longitude: -104.5) == nil)
}

@Test func operationalFacilityMapMatchesAndroidQueryParserAndPolicy() throws {
    let url = try #require(OperationalFacilityMap.queryURL(latitude: 39.7392, longitude: -104.9903))
    let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    #expect(query["geometry"] == "-104.990300,39.739200")
    #expect(query["distance"] == "1.000000")
    #expect(query["units"] == "esriSRUnit_StatuteMile")
    #expect(query["returnGeometry"] == "true")
    #expect(query["outSR"] == "4326")
    #expect(OperationalFacilityMap.operatingRadiusNM == 0.868976)

    let payload = #"{"features":[{"attributes":{"OBJECTID":42,"CEILING":200,"UNIT":"FEET","APT1_FAAID":"DEN","APT1_ICAO":"KDEN","APT1_NAME":"Denver International Airport (DEN)","APT1_LAANC":1,"AIRSPACE_1":"B","AIRSPACE_2":"C"},"geometry":{"rings":[[[-105.0,39.7],[-105.0,39.8],[-104.9,39.8],[-105.0,39.7]]]}}]}"#
    let records = try OperationalFacilityMap.parse(Data(payload.utf8))
    #expect(records.count == 1)
    #expect(records[0].airspaceClasses == ["B", "C"])
    #expect(records[0].laancAvailable)
    #expect(records[0].rings.count == 1)
    #expect(records[0].rings[0][0] == .init(latitude: 39.7, longitude: -105.0))
    let state = OperationalFacilityMap.state(
        records: records,
        loading: false,
        errorMessage: nil,
        pilotCoordinate: .init(latitude: 39.75, longitude: -104.97)
    )
    #expect(state.severity == .danger)
    #expect(state.chipLabel.contains("Authorization required"))
    #expect(state.chipLabel.contains("FAA grid limit 200 ft AGL"))
    #expect(state.detail.contains("not the top of the controlled-airspace class"))
}

@Test func operationalFacilityMapUsesLowestGridLimitRegardlessOfResponseOrder() {
    let containingRing: [[OperationalAirspaceCoordinate]] = [[
        .init(latitude: 39.9, longitude: -120.1),
        .init(latitude: 40.1, longitude: -120.1),
        .init(latitude: 40.1, longitude: -119.9),
        .init(latitude: 39.9, longitude: -119.9),
        .init(latitude: 39.9, longitude: -120.1),
    ]]
    func record(objectID: Int64, ceilingFeet: Int) -> OperationalFacilityMapRecord {
        .init(
            objectID: objectID,
            ceilingFeet: ceilingFeet,
            unit: "FEET",
            primaryAirportFAAID: "TRK",
            primaryAirportICAO: "KTRK",
            primaryAirportName: "Truckee Tahoe Airport",
            laancAvailable: true,
            airspaceClasses: ["D"],
            rings: containingRing
        )
    }

    let oneHundred = record(objectID: 2, ceilingFeet: 100)
    let twoHundred = record(objectID: 1, ceilingFeet: 200)
    let forward = OperationalFacilityMap.state(
        records: [twoHundred, oneHundred],
        loading: false,
        errorMessage: nil,
        pilotCoordinate: .init(latitude: 40, longitude: -120)
    )
    let reverse = OperationalFacilityMap.state(
        records: [oneHundred, twoHundred],
        loading: false,
        errorMessage: nil,
        pilotCoordinate: .init(latitude: 40, longitude: -120)
    )

    #expect(forward.chipLabel == reverse.chipLabel)
    #expect(
        forward.chipLabel
            == "Airspace: Authorization required - Truckee Tahoe Airport Class D; FAA grid limit 100 ft AGL"
    )
    #expect(forward.detail.contains("lowest FAA UAS Facility Map limit"))
}

@Test func operationalFacilityMapTreatsBufferOnlyIntersectionAsNearby() {
    let record = OperationalFacilityMapRecord(
        objectID: 145193,
        ceilingFeet: 400,
        unit: "FEET",
        primaryAirportFAAID: "RDD",
        primaryAirportICAO: "KRDD",
        primaryAirportName: "Redding Rgnl",
        laancAvailable: true,
        airspaceClasses: ["D"],
        rings: [[
            .init(latitude: 40.58, longitude: -122.3242),
            .init(latitude: 40.61, longitude: -122.3242),
            .init(latitude: 40.61, longitude: -122.30),
            .init(latitude: 40.58, longitude: -122.30),
            .init(latitude: 40.58, longitude: -122.3242),
        ]]
    )
    let state = OperationalFacilityMap.state(
        records: [record],
        loading: false,
        errorMessage: nil,
        pilotCoordinate: .init(latitude: 40.59122, longitude: -122.33465)
    )

    #expect(state.severity == .caution)
    #expect(state.chipLabel == "Airspace nearby - Redding Rgnl Class D 0.5 mi")
    #expect(state.detail.contains("No FAA UAS Facility Map grid covers the current location."))
    #expect(state.detail.contains("authorization is required only if"))
}

@Test func operationalStatusStripUsesConciseHeadingsWithoutDiscardingDetails() {
    let detailedAirspace =
        "Airspace: Authorization required - Gowen Field Class C; FAA grid limit 100 ft AGL"
    #expect(OperationalStatusChipText.airspace(
        severity: .danger,
        detailedLabel: detailedAirspace
    ) == "Authorization required")
    #expect(OperationalStatusChipText.airspace(
        severity: .caution,
        detailedLabel: "Airspace nearby - Gowen Field Class C 0.4 mi"
    ) == "Airspace nearby")
    #expect(OperationalStatusChipText.notam(
        severity: .danger,
        detailedLabel: "NOTAMs: RESTRICTED 0.0 mi"
    ) == "NOTAM warning")
    #expect(OperationalStatusChipText.land(
        severity: .caution,
        detailedLabel: "Land rules: 17 nearby"
    ) == "Land rules nearby")
    #expect(OperationalStatusChipText.land(
        severity: .neutral,
        detailedLabel: "Land rules off"
    ) == "Land rules off")
    #expect(detailedAirspace.contains("Gowen Field"))
}

@Test func operationalMainScreenUsesIncidentMapContractAndHidesEmptyTrackHeader() {
    #expect(OperationalMainScreenPresentation.incidentMapLabel == "Incident map")
    #expect(OperationalMainScreenPresentation.incidentMapValue(
        mapID: "",
        mapTitle: ""
    ) == "Standalone")
    #expect(OperationalMainScreenPresentation.incidentMapValue(
        mapID: "map-42",
        mapTitle: "Washoe Search"
    ) == "Washoe Search")
    #expect(OperationalMainScreenPresentation.incidentMapValue(
        mapID: "map-42",
        mapTitle: " "
    ) == "map-42")
    #expect(!OperationalMainScreenPresentation.showsAircraftHeader(activeTrackCount: 0))
    #expect(OperationalMainScreenPresentation.showsAircraftHeader(activeTrackCount: 1))
    #expect(OperationalMainScreenPresentation.droneToBridgeRSSIText(-74) == "D→Bridge -74 dBm")
    #expect(OperationalMainScreenPresentation.droneToBridgeRSSIText(nil) == nil)
    #expect(OperationalMainScreenPresentation.droneToBridgeRSSIText(-128) == nil)
}

@Test func operationalStreamSetupMatchesAndroidWording() {
    #expect(OperationalStreamSetupPresentation.instruction(
        ingestAddress: "rtmp://192.168.1.5:1935",
        networkSSID: "Incident Wi-Fi"
    ) == "Stream video to: rtmp://192.168.1.5:1935/<droneDesig> on Incident Wi-Fi network")
    #expect(OperationalStreamSetupPresentation.instruction(
        ingestAddress: "rtmp://192.168.1.5:1935/",
        networkSSID: " "
    ) == "Stream video to: rtmp://192.168.1.5:1935/<droneDesig> on Wi-Fi name unavailable network")
}

@Test func operationalContourToggleChangesTileLayerFingerprintWithoutViewportChange() {
    let withoutContours = OperationalMapTileLayerState.fingerprint(
        baseLayer: .openStreetMap,
        contours: false,
        offlineOnly: false,
        revision: 7
    )
    let withContours = OperationalMapTileLayerState.fingerprint(
        baseLayer: .openStreetMap,
        contours: true,
        offlineOnly: false,
        revision: 7
    )

    #expect(withoutContours != withContours)
}

@Test func operationalFacilityMapPreservesAndroidClearAndFailureStates() throws {
    let clear = OperationalFacilityMap.state(records: [], loading: false, errorMessage: nil)
    #expect(clear.severity == .normal)
    #expect(clear.chipLabel == "Airspace clear")
    let unavailable = OperationalFacilityMap.state(records: [], loading: false, errorMessage: "offline")
    #expect(unavailable.severity == .neutral)
    #expect(unavailable.chipLabel == "Airspace unavailable")
    #expect(unavailable.detail == "offline")
}

@Test func operationalNotamUsesOneStatuteMileOperatorRadius() {
    let inside = OperationalNotam(
        id: "inside",
        title: "Inside",
        summary: "",
        distanceNM: 0.86,
        intersectsPilotArea: false,
        severity: .normal
    )
    let outside = OperationalNotam(
        id: "outside",
        title: "Outside",
        summary: "",
        distanceNM: 1.0,
        intersectsPilotArea: false,
        severity: .normal
    )

    let result = OperationalNotamPolicy.filtered(
        [inside, outside],
        radiusStatuteMiles: 1
    )

    #expect(result.visible.map(\.id) == ["inside"])
    #expect(result.suppressed == 1)
    #expect(OperationalNotamPolicy.faaQueryRadiusNM(radiusStatuteMiles: 1) == 1)
    #expect(OperationalNotamPolicy.faaQueryRadiusNM(radiusStatuteMiles: 2) == 2)
}

@Test func operationalNotamParsesGeoJsonAndPrioritizesIntersectingTfr() throws {
    let payload = #"{"data":{"geojson":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-105.01,39.72],[-104.97,39.72],[-104.97,39.76],[-105.01,39.76],[-105.01,39.72]]]},"properties":{"coreNOTAMData":{"notam":{"id":"tfr-1","icaoLocation":"KDEN","series":"FDC","number":"1","year":"26","text":"TEMPORARY FLIGHT RESTRICTION SFC-400 FT AGL","effectiveStart":"now","effectiveEnd":"later"},"notamTranslation":[{"domestic_message":"TFR SFC-400 FT AGL"}]}}}]}}"#
    let notices = try OperationalNotamParser.parseResponse(
        Data(payload.utf8),
        pilot: OperationalNotamCoordinate(latitude: 39.7392, longitude: -104.9903),
        operatingRadiusNM: 2
    )
    #expect(notices.count == 1)
    #expect(notices[0].intersectsPilotArea)
    #expect(notices[0].severity == .danger)
    #expect(notices[0].title == "Temporary flight restriction")
    #expect(notices[0].altitudeBand?.ceilingLabel == "400 FT")
    #expect(OperationalNotamPolicy.chipLabel(notices: notices, configured: true, loading: false, hasError: false).contains("RESTRICTED"))
}

@Test func operationalNotamUnconfiguredLabelDoesNotImplyPendingRequest() {
    #expect(OperationalNotamPolicy.chipLabel(
        notices: [],
        configured: false,
        loading: false,
        hasError: false
    ) == "NOTAMs not configured")
}

@Test func operationalLandRestrictionParsesAgencyLinksAndBoundaryPolicy() throws {
    let source = OperationalLandSource(
        id: "test-park",
        queryEndpoint: URL(string: "https://example.gov/query")!,
        agency: .nationalParkService,
        rule: .launchLandOperateRestricted,
        nameFields: ["UNIT_NAME"],
        identifierFields: ["UNIT_CODE"]
    )
    let geoJSON = Data(#"{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"UNIT_NAME":"Test National Park","UNIT_CODE":"TEST"},"geometry":{"type":"Polygon","coordinates":[[[-105.01,39.72],[-104.97,39.72],[-104.97,39.76],[-105.01,39.76],[-105.01,39.72]]]}}]}"#.utf8)
    let areas = try OperationalLandRestriction.parse(
        geoJSON,
        source: source,
        center: .init(latitude: 39.7392, longitude: -104.9903),
        operatingRadiusNM: 1
    )

    #expect(areas.count == 1)
    #expect(areas[0].name == "Test National Park")
    #expect(areas[0].containsOperator)
    #expect(areas[0].agency.rulesURL.host == "www.nps.gov")
    #expect(OperationalLandRestriction.severity(areas, hasError: false) == .danger)
    #expect(OperationalLandRestriction.chipLabel(areas, loading: false, hasError: false) == "Land rules: RESTRICTED")
}

@Test func operationalLandRestrictionQueryRadiusUsesStatuteMiles() throws {
    let source = OperationalLandSource(
        id: "test-park",
        queryEndpoint: URL(string: "https://example.gov/query")!,
        agency: .nationalParkService,
        rule: .launchLandOperateRestricted,
        nameFields: ["UNIT_NAME"],
        identifierFields: ["UNIT_CODE"]
    )
    let url = try #require(OperationalLandRestriction.queryURL(
        source: source,
        center: OperationalLandCoordinate(latitude: 0, longitude: 0),
        radiusStatuteMiles: 1
    ))
    let geometry = try #require(
        URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first(where: { $0.name == "geometry" })?
            .value
    )
    let bounds = geometry.split(separator: ",").compactMap { Double($0) }

    #expect(bounds.count == 4)
    #expect(abs(bounds[3] - (0.868976 / 60)) < 0.000_000_2)
}

@Test func operationalLandRestrictionKeepsStateParkAdvisoryDistinctFromNoFlyRule() throws {
    let area = OperationalLandArea(
        id: "cpw:1",
        name: "Example State Park",
        agency: .coloradoParksAndWildlife,
        rule: .propertySpecificRules,
        polygons: [],
        intersectsOperatingArea: true,
        containsOperator: true,
        distanceNM: 0,
        detailsURL: URL(string: "https://cpw.state.co.us/example")
    )

    #expect(OperationalLandRestriction.severity([area], hasError: false) == .caution)
    #expect(OperationalLandRestriction.chipLabel([area], loading: false, hasError: false) == "Land rules: 1 nearby")
    #expect(area.detailsURL?.host == "cpw.state.co.us")
}

@Test func operationalLandRestrictionDoesNotReportClearWhileWaitingForLocation() {
    #expect(OperationalLandRestriction.severity(
        [],
        hasError: false,
        waitingForLocation: true
    ) == .neutral)
    #expect(OperationalLandRestriction.chipLabel(
        [],
        loading: false,
        hasError: false,
        waitingForLocation: true
    ) == "Land rules pending")
}

@Test func operationalLandRestrictionMeasuresNearestBoundaryEdgeInsteadOfOnlyVertices() throws {
    let source = OperationalLandSource(
        id: "test-wilderness",
        queryEndpoint: URL(string: "https://example.gov/query")!,
        agency: .forestService,
        rule: .launchLandOperateRestricted,
        nameFields: ["name"],
        identifierFields: ["id"]
    )
    let geoJSON = Data(#"{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Long Boundary","id":"1"},"geometry":{"type":"Polygon","coordinates":[[[-105.2,39.75],[-104.8,39.75],[-104.8,39.80],[-105.2,39.80],[-105.2,39.75]]]}}]}"#.utf8)
    let areas = try OperationalLandRestriction.parse(
        geoJSON,
        source: source,
        center: .init(latitude: 39.74, longitude: -105),
        operatingRadiusNM: 1
    )

    #expect(areas.count == 1)
    #expect(!areas[0].containsOperator)
    #expect(areas[0].distanceNM < 0.7)
    #expect(areas[0].intersectsOperatingArea)
}

@Test func operationalZipRoundTripsAndroidCompatibleStoredEntriesAndRejectsTraversal() throws {
    let encoded = try OperationalZipArchive.encode([
        .init(path: "manifest.json", data: Data("{\"format\":\"rid2caltopo_mutual_aid_package\"}".utf8)),
        .init(path: "tiles/OSM-Standard/12/123/456.bin", data: Data([1, 2, 3, 4])),
    ])
    let decoded = try OperationalZipArchive.decode(encoded)
    #expect(decoded.map(\.path) == ["manifest.json", "tiles/OSM-Standard/12/123/456.bin"])
    #expect(decoded[1].data == Data([1, 2, 3, 4]))
    #expect(throws: OperationalZipError.self) {
        try OperationalZipArchive.encode([.init(path: "../escape", data: Data())])
    }
}

@Test func operationalZipWritesARealModificationDate() throws {
    let archive = try OperationalZipArchive.encode([
        .init(path: "log.txt", data: Data("diagnostic".utf8)),
    ])
    #expect(archive.count >= 14)
    let dosTime = UInt16(archive[10]) | UInt16(archive[11]) << 8
    let dosDate = UInt16(archive[12]) | UInt16(archive[13]) << 8
    #expect(dosTime != 0 || dosDate != 0)
    #expect(dosDate != 0)
}

@Test func operationalZipReadsDeflatedJavaCompatibleEntries() throws {
    let fixture = "UEsDBAoAAAAAAFJK81wacLVdDAAAAAwAAAAIABwAbmFtZS50eHRVVAkAA8vqXGovEl1qdXgLAAEE9QEAAAQUAAAAUklEMkNhbHRvcG8KUEsDBBQAAgAIADxN9Fx5ZW/0XwQAAIIIAAAHABwAMS4yLnR4dFVUCQADxEFeas1BXmp1eAsAAQT1AQAABBQAAACVVc2O2zYQvucp5taLFW8CFCjak9P9QYDsbhBvkPOYGknsUiRDUva6T9Vn6JP1G0peu0EvPezCpsSZ+X7m8ycukguZgX0v+dc3DW3aVlra+DYF2zZ54Iivoeuc9UIjR4pJIicuNnjqQqIyCO1ttjs3P8cRUxYnpuDm7+yeQgyk19cxuGMf/IoOtgz0uJe0t3JY0WPMK2Lf0u3kHF1LYeu0T5aCBzvOUp+a4EuY0NE6wfnX7d2W3v39F13f3FMbDt4FbnEOQHZUXCvUCD3K4JNhb8S5Ovbci+n9z1erq6urRutR5k7KEXOOtrx95QGAGsMGELP9c56Ce6mTpOBQd2Tri3itviLr9+zAGtrjpSR/gIPab8ft3GXgPND3Cfz5AkYwlxNOa3mJIRUqodbULifG660kCo24K5Iu+T/P6YJh544EFqRd6XydTaO0DWTUKpBjlJKOjQs51wbvfrlavwf4LoRCm7tPuDNGZxUIsZNU8qzSSdp7juv7qVxiz1HEDCuAisUaJRnzqxALhtZy70PWZzrpzUvE6YUnRk7PAJQEp8n6vtoJY4x6OeKilnSTClk49dUMg3ChDBcYPQZC2bOBOFXuPIgDRfiUuLUBfB7qtwPckDoQhM/dJG5FexmsqS6KEqKDEJWoZXCUtXwm93azaSqj/ZTw1UOx3ZEeHp829+un2y8AUJLFHKgLs+FzVZ1y4TLlV89csIFu1eNaLOBA+ybpcHm4oFcvZsGS2HJEfxfSbEgKOHN8zOcJpwisoA/L4eweZi1JeFwUXCpC0wb70KmhIuMcLspgXXcjskGT9atj/z1sF8yU4aW56rLb8x69duqTbZe9irGBgoDckryAeM+uaW2OGJnwF6aykKIuo4TvmOKMJXLOcUhY+ka8ScdYTv6mkwhz+OzYPE9xrZSDmlryFFvqZbxUI2kqEwZgq5XNsyoxb9vajnXpZo50x2d2l3SpocKFV/q6XVriJfN8kgaBQZzMoIQjP1tXYbz5IpoJaudlGpDZdQKXmzlhPyQsM3wPW1EH4p3qq521ai9eEqBubMqYV5QxeBj20KrwDSrBB7+RwX9BiLC6qIqu9aopETAwjWSIY5GnSb5PFgTF4ZitUTFkb81MWIgyQ8OxclxRIp+c7aw5Z0zEiiBiURJ+paMURB02s5UTxp+yuqjofjR720pA1znbud1rpkBuH0ZGRCHUVXCwiG2/C6FH6eukAIwLU/uDrG/p5tUE1brz4/+hdkHWZrAGrYB5j7VjfXNJTIVnH7dncGoO/N7or5qKimnpm21uLX3Zbj+S7gFh+S9gn7dLd8H3cNOBk6+blcPCHRZPacn/lcSVrwbB5nD1YvFoNxX93dKpTvvDnlRNZA20v+iNGEMi+gLAiugzdkgZnSd/2DysPwjjbYI5AzL84/XJFbkiPTn16/Vn/bFBI/0VR+MBpU9LjCdGUDXRTgyDiR99sVAXl+ab7dO99q73YnVWqXzQdl7YGBFh/wBQSwECHgMKAAAAAABSSvNcGnC1XQwAAAAMAAAACAAYAAAAAAABAAAApIEAAAAAbmFtZS50eHRVVAUAA8vqXGp1eAsAAQT1AQAABBQAAABQSwECHgMUAAIACAA8TfRceWVv9F8EAACCCAAABwAYAAAAAAABAAAApIFOAAAAMS4yLnR4dFVUBQADxEFeanV4CwABBPUBAAAEFAAAAFBLBQYAAAAAAgACAJsAAADuBAAAAAA="
    let entries = try OperationalZipArchive.decode(Data(base64Encoded: fixture)!)
    #expect(entries.first(where: { $0.path == "name.txt" })?.data == Data("RID2Caltopo\n".utf8))
    #expect(entries.first(where: { $0.path == "1.2.txt" })?.data.count ?? 0 > 1_000)
}

@Test func offlineMapPlannerMatchesWebMercatorAndAndroidDemNaming() throws {
    let bounds = OperationalMapBounds(north: 39.75, south: 39.73, west: -105.01, east: -104.98)
    let count = OperationalOfflineMapPlanner.tileCount(bounds: bounds, minimumZoom: 12, maximumZoom: 12)
    let tiles = try #require(OperationalOfflineMapPlanner.tiles(
        bounds: bounds,
        minimumZoom: 12,
        maximumZoom: 12
    ))
    #expect(count == tiles.count)
    #expect(!tiles.isEmpty)
    #expect(Set(tiles).count == tiles.count)
    #expect(OperationalOfflineMapPlanner.demTileNames(bounds: bounds) == ["n40w106", "n40w105"])
    #expect(OperationalDEMResolution.allCases.first == .maximum1m)
    #expect(OperationalOfflineMapPlanner.estimatedBytes(
        tileCount: 0, includeContours: false, demTileCount: 1, demResolution: .enhanced10m
    ) > OperationalOfflineMapPlanner.estimatedBytes(
        tileCount: 0, includeContours: false, demTileCount: 1, demResolution: .standard30m
    ))
}

@Test func offlineMapPlannerRejectsUnboundedDownloadPlans() {
    let world = OperationalMapBounds(north: 80, south: -80, west: -180, east: 180)
    #expect(OperationalOfflineMapPlanner.tiles(
        bounds: world,
        minimumZoom: 8,
        maximumZoom: 19,
        maximumCount: 1_000
    ) == nil)
}

@Test func visibleMapLongPressSelectsExpectedWebMercatorTile() {
    let tile = OperationalVisibleMapTile.tile(
        latitude: 43.615,
        longitude: -116.2023,
        zoom: 12
    )
    #expect(tile == OperationalOfflineTile(zoom: 12, x: 725, y: 1495))
}

@Test func visibleMapZoomMatchesMapKitWorldScale() {
    let worldWidth = 268_435_456.0
    #expect(OperationalVisibleMapTile.zoomLevel(
        worldMapWidth: worldWidth,
        visibleMapWidth: worldWidth / 4,
        viewportWidth: 256
    ) == 2)
    #expect(OperationalVisibleMapTile.zoomLevel(
        worldMapWidth: worldWidth,
        visibleMapWidth: 0,
        viewportWidth: 1_024
    ) == 0)
}

@Test func signalLossRequiresAFlightThatLeftAndStayedAway() {
    let base = OperationalSignalLossInput(
        signalIdleSeconds: 12,
        learnedIntervalSeconds: nil,
        learnedSamples: 0,
        distanceFromDeviceFeet: 500,
        distanceFromTakeoffFeet: 600,
        bridgeCheckDistanceFeet: 300,
        maximumTrackDelaySeconds: 30,
        hasPreviouslyExceededBridgeDistance: false
    )
    let lost = OperationalSignalLossPolicy.evaluate(base)
    #expect(lost.alert)
    #expect(lost.hasExceededBridgeDistance)
    #expect(lost.idleThresholdSeconds == 10)

    let locationStaleButAircraftStillHeard = OperationalSignalLossPolicy.evaluate(.init(
        signalIdleSeconds: 12,
        trackTelemetryIdleSeconds: 1,
        learnedIntervalSeconds: nil,
        learnedSamples: 0,
        distanceFromDeviceFeet: 500,
        distanceFromTakeoffFeet: 600,
        bridgeCheckDistanceFeet: 300,
        maximumTrackDelaySeconds: 30,
        hasPreviouslyExceededBridgeDistance: true
    ))
    #expect(!locationStaleButAircraftStillHeard.alert)

    let freshPairedSEICoversRIDAndPeerLoss = OperationalSignalLossPolicy.evaluate(.init(
        signalIdleSeconds: 20,
        trackTelemetryIdleSeconds: 20,
        pairedSEIIdleSeconds: 1,
        learnedIntervalSeconds: nil,
        learnedSamples: 0,
        distanceFromDeviceFeet: 500,
        distanceFromTakeoffFeet: 600,
        bridgeCheckDistanceFeet: 300,
        maximumTrackDelaySeconds: 30,
        hasPreviouslyExceededBridgeDistance: true
    ))
    #expect(!freshPairedSEICoversRIDAndPeerLoss.alert)

    let stalePairedSEINoLongerCoversRIDLoss = OperationalSignalLossPolicy.evaluate(.init(
        signalIdleSeconds: 20,
        trackTelemetryIdleSeconds: 20,
        pairedSEIIdleSeconds: 11,
        learnedIntervalSeconds: nil,
        learnedSamples: 0,
        distanceFromDeviceFeet: 500,
        distanceFromTakeoffFeet: 600,
        bridgeCheckDistanceFeet: 300,
        maximumTrackDelaySeconds: 30,
        hasPreviouslyExceededBridgeDistance: true
    ))
    #expect(stalePairedSEINoLongerCoversRIDLoss.alert)

    let returned = OperationalSignalLossPolicy.evaluate(.init(
        signalIdleSeconds: 12,
        learnedIntervalSeconds: nil,
        learnedSamples: 0,
        distanceFromDeviceFeet: 50,
        distanceFromTakeoffFeet: 40,
        bridgeCheckDistanceFeet: 300,
        maximumTrackDelaySeconds: 30,
        hasPreviouslyExceededBridgeDistance: true
    ))
    #expect(!returned.alert)
}

@Test func bridgeAudioMonitoringPausesOnlyWhenEveryActiveFlightHasFreshPairedSEI() {
    #expect(!OperationalBridgeAlertPolicy.shouldMonitor(
        scannerRunning: false,
        activeFlightCount: 1,
        allActiveFlightsCoveredByFreshPairedSEI: false
    ))
    #expect(!OperationalBridgeAlertPolicy.shouldMonitor(
        scannerRunning: true,
        activeFlightCount: 0,
        allActiveFlightsCoveredByFreshPairedSEI: false
    ))
    #expect(!OperationalBridgeAlertPolicy.shouldMonitor(
        scannerRunning: true,
        activeFlightCount: 1,
        allActiveFlightsCoveredByFreshPairedSEI: true
    ))
    #expect(OperationalBridgeAlertPolicy.shouldMonitor(
        scannerRunning: true,
        activeFlightCount: 2,
        allActiveFlightsCoveredByFreshPairedSEI: false
    ))
}

@Test func altitudeAlertUsesAndroidThresholds() {
    #expect(OperationalAltitudeAlertPolicy.severity(aglFeet: nil) == .normal)
    #expect(OperationalAltitudeAlertPolicy.severity(aglFeet: 179.9) == .normal)
    #expect(OperationalAltitudeAlertPolicy.severity(aglFeet: 180) == .caution)
    #expect(OperationalAltitudeAlertPolicy.severity(aglFeet: 200) == .overLimit)
}

@Test func observationPreservesTransportAndIdentity() {
    let observation = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "1SAR-TEST-01",
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        latitude: 39.7392,
        longitude: -104.9903,
        signalStrengthDbm: -63
    )

    #expect(observation.source == .bluetoothLegacy)
    #expect(observation.aircraftId == "1SAR-TEST-01")
    #expect(observation.signalStrengthDbm == -63)
}

@Test func proximityAlertRequiresTeamTrafficAndLocalEligibility() {
    var engine = RidProximityAlertEngine()
    let ineligible = proximityDrone(
        id: "A",
        longitude: 0,
        team: true,
        eligible: false
    )
    let nearbyUnknown = proximityDrone(
        id: "B",
        longitude: 0.00005,
        team: false,
        eligible: false
    )
    #expect(engine.update(drones: [ineligible, nearbyUnknown], thresholdFeet: 40).activeAlert == nil)

    engine.reset()
    let eligible = proximityDrone(
        id: "A",
        longitude: 0,
        team: true,
        eligible: true
    )
    let output = engine.update(drones: [eligible, nearbyUnknown], thresholdFeet: 40)
    #expect(output.activeAlert?.pairKey == "A|B")
    #expect(output.activeAlert?.horizontalSeparationFeet ?? 100 < 40)
}

@Test func proximityAlertUsesVerticalSpacingOnlyForTwoTeamDrones() {
    var engine = RidProximityAlertEngine()
    let eligibleTeam = proximityDrone(
        id: "A",
        longitude: 0,
        altitude: 100,
        team: true,
        eligible: true
    )
    let highUnknown = proximityDrone(
        id: "B",
        longitude: 0.00005,
        altitude: 200,
        team: false,
        eligible: false
    )
    #expect(engine.update(drones: [eligibleTeam, highUnknown], thresholdFeet: 40).activeAlert != nil)

    engine.reset()
    let highTeam = proximityDrone(
        id: "B",
        longitude: 0.00005,
        altitude: 200,
        team: true,
        eligible: false
    )
    #expect(engine.update(drones: [eligibleTeam, highTeam], thresholdFeet: 40).activeAlert == nil)
}

@Test func proximityAlertSuspendsResumesAndClearsAfterAndroidDelay() {
    var engine = RidProximityAlertEngine()
    let start = Date(timeIntervalSince1970: 1_000)
    let first = proximityDrone(id: "A", longitude: 0, team: true, eligible: true)
    let second = proximityDrone(id: "B", longitude: 0.00005, team: true, eligible: false)

    let active = engine.update(drones: [first, second], thresholdFeet: 40, now: start)
    #expect(active.activeAlert != nil)
    let suspended = engine.suspend()
    #expect(suspended.activeAlert == nil)
    #expect(suspended.canResume)
    #expect(engine.resume().activeAlert != nil)

    let separated = proximityDrone(id: "B", longitude: 0.001, team: true, eligible: false)
    #expect(engine.update(drones: [first, separated], thresholdFeet: 40, now: start).activeAlert != nil)
    #expect(engine.update(drones: [first, separated], thresholdFeet: 40, now: start.addingTimeInterval(2.9)).activeAlert != nil)
    #expect(engine.update(drones: [first, separated], thresholdFeet: 40, now: start.addingTimeInterval(3.1)).activeAlert == nil)
}

@Test func predictiveHeadAlertsBeforeReportedPositionsCrossThreshold() {
    let firstDate = Date(timeIntervalSince1970: 2_000)
    let secondDate = firstDate.addingTimeInterval(1)
    let firstInitial = proximityDrone(
        id: "A",
        longitude: 0,
        sampleDate: firstDate,
        team: true,
        eligible: true
    )
    let secondInitial = proximityDrone(
        id: "B",
        longitude: 0.00030,
        sampleDate: firstDate,
        team: true,
        eligible: false
    )
    let firstCurrent = proximityDrone(
        id: "A",
        longitude: 0,
        sampleDate: secondDate,
        team: true,
        eligible: true
    )
    let secondCurrent = proximityDrone(
        id: "B",
        longitude: 0.00020,
        sampleDate: secondDate,
        team: true,
        eligible: false
    )

    var predictive = RidProximityAlertEngine()
    #expect(predictive.update(
        drones: [firstInitial, secondInitial],
        thresholdFeet: 40,
        predictiveEnabled: true,
        now: firstDate
    ).activeAlert == nil)
    let projected = predictive.update(
        drones: [firstCurrent, secondCurrent],
        thresholdFeet: 40,
        predictiveEnabled: true,
        now: secondDate
    )
    #expect(projected.activeAlert != nil)
    #expect(projected.activeAlert?.horizontalSeparationFeet ?? 100 < 40)
    #expect(projected.activeAlert?.currentHorizontalSeparationFeet ?? 0 > 40)
    #expect(projected.activeAlert?.usesProjection == true)

    var reportedOnly = RidProximityAlertEngine()
    _ = reportedOnly.update(
        drones: [firstInitial, secondInitial],
        thresholdFeet: 40,
        predictiveEnabled: false,
        now: firstDate
    )
    #expect(reportedOnly.update(
        drones: [firstCurrent, secondCurrent],
        thresholdFeet: 40,
        predictiveEnabled: false,
        now: secondDate
    ).activeAlert == nil)
}

private func proximityDrone(
    id: String,
    longitude: Double,
    altitude: Double? = 100,
    sampleDate: Date = Date(),
    team: Bool,
    eligible: Bool
) -> RidProximityDrone {
    RidProximityDrone(
        remoteID: id,
        mappedID: "MAP-\(id)",
        latitude: 39,
        longitude: -105 + longitude,
        altitudeMeters: altitude,
        sampleDate: sampleDate,
        teamDrone: team,
        localAlertEligible: eligible
    )
}

@Test func relativePositionReportsOperatorRangeAndBearing() throws {
    let east = try #require(RidGeometry.relativePosition(
        fromLatitude: 0,
        longitude: 0,
        toLatitude: 0,
        longitude: 0.001
    ))
    #expect(abs(east.distanceMeters - 111.195) < 0.1)
    #expect(abs(east.bearingDegrees - 90) < 0.001)
    #expect(east.cardinalDirection == "E")

    let north = try #require(RidGeometry.relativePosition(
        fromLatitude: 39,
        longitude: -105,
        toLatitude: 39.001,
        longitude: -105
    ))
    #expect(abs(north.bearingDegrees) < 0.001)
    #expect(north.cardinalDirection == "N")
    #expect(RidGeometry.relativePosition(
        fromLatitude: 91,
        longitude: 0,
        toLatitude: 0,
        longitude: 0
    ) == nil)
}

@Test func headingNormalizationNeverFormatsOutsideZeroThroughThreeFiftyNine() {
    #expect(RidHeading.normalized(361) == 1)
    #expect(RidHeading.normalized(-1) == 359)
    #expect(RidHeading.roundedWholeDegrees(359.6) == 0)
    #expect(RidHeading.roundedWholeDegrees(361) == 1)
    #expect(RidHeading.normalized(.infinity) == nil)

    let observation = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "HEADING",
        receivedAt: Date(),
        latitude: 39,
        longitude: -105,
        headingDegrees: 361
    )
    #expect(observation.headingDegrees == 1)
}

@Test func closestTrafficPairReportsThreeDimensionalSeparationWhenAvailable() throws {
    let closest = try #require(RidTrafficSeparation.closestPair(in: [
        RidTrafficPosition(aircraftID: "ALPHA", latitude: 0, longitude: 0, altitudeMeters: 100),
        RidTrafficPosition(aircraftID: "BRAVO", latitude: 0, longitude: 0.0001, altitudeMeters: 103),
        RidTrafficPosition(aircraftID: "CHARLIE", latitude: 0, longitude: 0.01, altitudeMeters: 100),
    ]))

    #expect(closest.firstAircraftID == "ALPHA")
    #expect(closest.secondAircraftID == "BRAVO")
    #expect(abs(closest.horizontalMeters - 11.1195) < 0.1)
    #expect(closest.verticalMeters == 3)
    #expect(abs((closest.threeDimensionalMeters ?? 0) - hypot(11.1195, 3)) < 0.1)
}

@Test func trafficSeparationHandlesMissingAltitudeAndInsufficientTraffic() throws {
    let pair = try #require(RidTrafficSeparation.closestPair(in: [
        RidTrafficPosition(aircraftID: "ALPHA", latitude: 39, longitude: -105, altitudeMeters: nil),
        RidTrafficPosition(aircraftID: "BRAVO", latitude: 39.001, longitude: -105, altitudeMeters: 100),
    ]))
    #expect(pair.verticalMeters == nil)
    #expect(pair.threeDimensionalMeters == nil)
    #expect(RidTrafficSeparation.closestPair(in: [
        RidTrafficPosition(aircraftID: "ALPHA", latitude: 39, longitude: -105, altitudeMeters: nil),
    ]) == nil)
}

@Test func streamEndpointUsesMediaMtxLoopbackContract() {
    let endpoint = MediaStreamEndpoint(designator: "drone-one")

    #expect(endpoint.rtmpPort == 1935)
    #expect(endpoint.loopbackRtspURL?.absoluteString == "rtsp://127.0.0.1:8554/drone-one")
    #expect(endpoint.loopbackHlsURL?.absoluteString == "http://127.0.0.1:8888/drone-one/index.m3u8")
}

@Test func liveStreamSelectionReplacesPlaceholderWhenFirstPublisherArrives() {
    let publisher = "1sar7DjMn4pr"
    let focus = LiveStreamSelectionPolicy.focusAfterPublisherStarted(
        currentFocus: "demo",
        publisherPath: publisher,
        activePublisherPaths: [publisher]
    )
    #expect(focus == publisher)
    #expect(LiveStreamSelectionPolicy.playbackPath(
        focusedID: focus,
        activePublisherPaths: [publisher]
    ) == publisher)
}

@Test func liveStreamSelectionRejectsReaderCreatedPlaceholderHlsMuxer() {
    #expect(!LiveStreamSelectionPolicy.shouldAcceptHLSMuxer(
        path: "demo",
        activePublisherPaths: []
    ))
    #expect(LiveStreamSelectionPolicy.playbackPath(
        focusedID: "demo",
        activePublisherPaths: []
    ) == nil)
}

@Test func liveStreamDecoderResetsWhenItsPublisherTerminates() {
    #expect(LiveStreamDecoderLifecyclePolicy.shouldResetAfterPublisherStopped(
        sessionPath: "1sar7djmvc3pr",
        decoderPath: "/1sar7djmvc3pr"
    ))
    #expect(!LiveStreamDecoderLifecyclePolicy.shouldResetAfterPublisherStopped(
        sessionPath: "1sar7dja360",
        decoderPath: "1sar7djmvc3pr"
    ))
}

@Test func differentPublisherReplacesStaleWaitingDecoder() {
    #expect(LiveStreamDecoderLifecyclePolicy.shouldStartDecoder(
        publisherPath: "1sar7dja360",
        decoderPath: "1sar7djmvc3pr",
        decoderIsIdle: false
    ))
    #expect(!LiveStreamDecoderLifecyclePolicy.shouldStartDecoder(
        publisherPath: "1sar7djmvc3pr",
        decoderPath: "1sar7djmvc3pr",
        decoderIsIdle: false
    ))
    #expect(LiveStreamDecoderLifecyclePolicy.shouldStartDecoder(
        publisherPath: "1sar7dja360",
        decoderPath: nil,
        decoderIsIdle: true
    ))
}

@Test func samePublisherReusesItsSingleOperationalTelemetryDecoder() {
    #expect(!LiveStreamDecoderLifecyclePolicy.shouldStartDecoder(
        publisherPath: "/1sar7djmtrc4td",
        decoderPath: "1sar7djmtrc4td",
        decoderIsIdle: false
    ))
}

@Test func operationalMapTileSourcesMatchAndroidContracts() throws {
    #expect(OperationalMapBaseLayer.openStreetMap.tileURL(zoom: 12, x: 657, y: 1582)?.absoluteString
        == "https://tile.openstreetmap.org/12/657/1582.png")
    #expect(OperationalMapBaseLayer.imagery.tileURL(zoom: 12, x: 657, y: 1582)?.absoluteString
        == "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/12/1582/657.jpg")
}

@Test func operationalMapOverzoomSelectsAndOffsetsTheLevelNineteenParent() throws {
    let level20 = try #require(OperationalOverzoomTile.resolve(
        requestedZoom: 20,
        requestedX: 657,
        requestedY: 1_582,
        sourceMaximumZoom: 19
    ))
    #expect(level20.sourceZoom == 19)
    #expect(level20.sourceX == 328)
    #expect(level20.sourceY == 791)
    #expect(level20.zoomDelta == 1)
    #expect(level20.childX == 1)
    #expect(level20.childY == 0)

    let level22 = try #require(OperationalOverzoomTile.resolve(
        requestedZoom: 22,
        requestedX: 5_261,
        requestedY: 12_659,
        sourceMaximumZoom: 19
    ))
    #expect(level22.sourceX == 657)
    #expect(level22.sourceY == 1_582)
    #expect(level22.zoomDelta == 3)
    #expect(level22.childX == 5)
    #expect(level22.childY == 3)
    #expect(OperationalOverzoomTile.resolve(
        requestedZoom: 19,
        requestedX: 657,
        requestedY: 1_582,
        sourceMaximumZoom: 19
    ) == nil)
}

@Test func operationalPipSizingMatchesAndroidInsetRules() {
    #expect(OperationalPipSizing.clampInsetFraction(.nan)
        == OperationalPipSizing.defaultInsetFraction)
    #expect(OperationalPipSizing.clampInsetFraction(0.1)
        == OperationalPipSizing.minimumInsetFraction)
    #expect(OperationalPipSizing.clampInsetFraction(0.9)
        == OperationalPipSizing.maximumInsetFraction)

    let landscape = OperationalPipSizing.insetSize(
        containerWidth: 1024,
        containerHeight: 768,
        insetFraction: 0.33,
        aspectRatio: 1024.0 / 768.0
    )
    #expect(abs(landscape.width - 330) < 0.001)
    #expect(abs(landscape.height - 247.5) < 0.001)

    let portrait = OperationalPipSizing.insetSize(
        containerWidth: 768,
        containerHeight: 1024,
        insetFraction: 0.33,
        aspectRatio: 768.0 / 1024.0
    )
    #expect(abs(portrait.width - 245.52) < 0.001)
    #expect(abs(portrait.height - 327.36) < 0.001)

    let heightLimited = OperationalPipSizing.insetSize(
        containerWidth: 1000,
        containerHeight: 120,
        insetFraction: 0.55
    )
    #expect(abs(heightLimited.width - (96 * 16.0 / 9.0)) < 0.001)
    #expect(abs(heightLimited.height - 96) < 0.001)
}

@Test func operationalMapLayoutAppliesPersistedPipPreference() {
    #expect(OperationalMapVideoLayout.map.withPictureInPicture(true) == .mapPrimary)
    #expect(OperationalMapVideoLayout.video.withPictureInPicture(true) == .videoPrimary)
    #expect(OperationalMapVideoLayout.split.withPictureInPicture(true) == .split)
    #expect(OperationalMapVideoLayout.mapPrimary.withPictureInPicture(false) == .map)
    #expect(OperationalMapVideoLayout.videoPrimary.withPictureInPicture(false) == .video)
    #expect(OperationalMapVideoLayout.split.withPictureInPicture(false) == .split)
}

@Test func operationalSplitSizingUsesDragDistanceAndReachesCollapsedEdges() {
    #expect(abs(OperationalSplitSizing.adjustedFraction(
        current: 0.5,
        dragDelta: 100,
        available: 1000
    ) - 0.6) < 0.0001)
    #expect(OperationalSplitSizing.adjustedFraction(
        current: 0.5,
        dragDelta: 1000,
        available: 1000
    ) == OperationalSplitSizing.maximumFraction)
    #expect(OperationalSplitSizing.adjustedFraction(
        current: 0.5,
        dragDelta: -1000,
        available: 1000
    ) == OperationalSplitSizing.minimumFraction)
    #expect(OperationalSplitSizing.adjustedFraction(
        current: 0,
        dragDelta: 100,
        available: 0
    ) == OperationalSplitSizing.minimumFraction)
}

@Test func operationalSplitSizingSnapsWithinTwoHandleWidthsOfEitherEdge() {
    #expect(OperationalSplitSizing.snappedFraction(
        0.088,
        available: 1000,
        handleWidth: 44
    ) == OperationalSplitSizing.minimumFraction)
    #expect(abs(OperationalSplitSizing.snappedFraction(
        0.089,
        available: 1000,
        handleWidth: 44
    ) - 0.089) < 0.0001)
    #expect(OperationalSplitSizing.snappedFraction(
        0.912,
        available: 1000,
        handleWidth: 44
    ) == OperationalSplitSizing.maximumFraction)
    #expect(abs(OperationalSplitSizing.snappedFraction(
        0.911,
        available: 1000,
        handleWidth: 44
    ) - 0.911) < 0.0001)
}

@Test func operationalMapFullScreenPreservesPictureInPicture() {
    #expect(OperationalMapVideoLayout.mapPrimary.fullScreenPresentation(
        pictureInPictureEnabled: true
    ) == .mapPrimary)
    #expect(OperationalMapVideoLayout.videoPrimary.fullScreenPresentation(
        pictureInPictureEnabled: true
    ) == .videoPrimary)
    #expect(OperationalMapVideoLayout.split.fullScreenPresentation(
        pictureInPictureEnabled: true
    ) == .videoPrimary)
    #expect(OperationalMapVideoLayout.mapPrimary.fullScreenPresentation(
        pictureInPictureEnabled: false
    ) == .video)
}

@Test func caltopoTrackLabelMatchesAndroidFirstWaypointTimestamp() throws {
    let timeZone = TimeZone(secondsFromGMT: -7 * 60 * 60)!
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = timeZone
    let firstWaypoint = try #require(calendar.date(from: DateComponents(
        year: 2026,
        month: 7,
        day: 24,
        hour: 20,
        minute: 45,
        second: 10
    )))

    #expect(
        CaltopoTrackLabel.androidCompatible(
            baseLabel: "1SAR7DJI",
            firstWaypointAt: firstWaypoint,
            timeZone: timeZone
        ) == "1SAR7DJI_204510Jul24"
    )
}

@Test func pilotDisplayPreferenceMatchesAndroidNormalizationAndColorDefaults() {
    #expect(PilotDisplayPreference.normalizePilotCallsign("  apple1 ") == "APPLE1")
    #expect(PilotDisplayPreference.normalizePilotCallsign("  ") == nil)
    #expect(PilotDisplayPreference.preferredPilotCallsign(
        saved: "  1sar7 ",
        existing: "1sar1001"
    ) == "1SAR7")
    #expect(PilotDisplayPreference.preferredPilotCallsign(
        saved: "",
        existing: "1sar1001"
    ) == "1sar1001")
    #expect(PilotDisplayPreference.sanitizeTrackColor("e53935", fallback: defaultActiveTrackColor) == "#E53935")
    #expect(PilotDisplayPreference.sanitizeTrackColor("invalid", fallback: defaultActiveTrackColor) == "#1E88E5")
    #expect(PilotDisplayPreference() == PilotDisplayPreference(
        activeTrackColor: "#1E88E5",
        archiveTrackColor: "#FF00FF",
        bearingEnabled: false
    ))
}

@Test func duplicatePilotCallsignPolicyWarnsWithoutInvalidatingIdentity() {
    #expect(PilotDisplayPreference.callsignsMatch(" 1sar7 ", "1SAR7"))
    #expect(!PilotDisplayPreference.callsignsMatch("1SAR7", "1SAR8"))
    #expect(PilotDisplayPreference.activeAssignmentWarning(
        callsign: "1SAR7",
        aircraftLabel: "1SAR7DjMtrc4td"
    ) == "Warning: pilot callsign 1SAR7 is already assigned to active drone 1SAR7DjMtrc4td. Confirm only if this is intentional.")
    #expect(RidAircraftIdentity(
        remoteID: "RID-2",
        organization: "NCSSAR",
        pilotCallsign: "1SAR7",
        droneDescription: "DJI Matrice 4TD"
    ).isComplete)
}

@Test func operationalMapBearingLineReachesViewportEdgeAtCardinalHeadings() throws {
    let start = MapScreenPoint(x: 50, y: 40)
    let north = try #require(OperationalMapGeometry.bearingLineToViewportEdge(
        start: start, headingDegrees: 0, viewportWidth: 100, viewportHeight: 80
    ))
    let east = try #require(OperationalMapGeometry.bearingLineToViewportEdge(
        start: start, headingDegrees: 90, viewportWidth: 100, viewportHeight: 80
    ))
    let south = try #require(OperationalMapGeometry.bearingLineToViewportEdge(
        start: start, headingDegrees: 180, viewportWidth: 100, viewportHeight: 80
    ))
    let west = try #require(OperationalMapGeometry.bearingLineToViewportEdge(
        start: start, headingDegrees: 270, viewportWidth: 100, viewportHeight: 80
    ))
    #expect(abs(north.x - 50) < 0.0001 && north.y == 0)
    #expect(east.x == 100 && abs(east.y - 40) < 0.0001)
    #expect(abs(south.x - 50) < 0.0001 && south.y == 80)
    #expect(west.x == 0 && abs(west.y - 40) < 0.0001)
    #expect(OperationalMapGeometry.bearingLineToViewportEdge(
        start: start, headingDegrees: nil, viewportWidth: 100, viewportHeight: 80
    ) == nil)
}

@Test func operationalTravelBearingRequiresClearDisplacementAndSurvivesStationaryKeepalives() async throws {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore(policy: .init(minimumDistanceMeters: 0))
    _ = await store.ingest(trackObservation(
        id: "BEARING", at: start, latitude: 39.0, longitude: -121.0, headingDegrees: 270
    ))
    let jitter = await store.ingest(trackObservation(
        id: "BEARING", at: start.addingTimeInterval(1),
        latitude: 39.00001, longitude: -121.0, headingDegrees: 1
    ))
    guard case let .accepted(jitterTrack) = jitter else {
        Issue.record("Expected jitter point to be retained for the geometry test")
        return
    }
    #expect(OperationalMapGeometry.travelBearingDegrees(points: jitterTrack.points) == nil)

    let north = await store.ingest(trackObservation(
        id: "BEARING", at: start.addingTimeInterval(5),
        latitude: 39.00012, longitude: -121.0, headingDegrees: 180
    ))
    guard case let .accepted(movingTrack) = north else {
        Issue.record("Expected displaced point")
        return
    }
    let movingBearing = try #require(OperationalMapGeometry.travelBearingDegrees(points: movingTrack.points))
    #expect(abs(movingBearing) < 0.1)

    let keepalive = await store.ingest(trackObservation(
        id: "BEARING", at: start.addingTimeInterval(8),
        latitude: 39.00012, longitude: -121.0, headingDegrees: 1
    ))
    guard case let .accepted(stoppedTrack) = keepalive else {
        Issue.record("Expected stationary keepalive")
        return
    }
    let stoppedBearing = try #require(OperationalMapGeometry.travelBearingDegrees(points: stoppedTrack.points))
    #expect(abs(stoppedBearing) < 0.1)
}

@Test func operationalTravelBearingFollowsLatestVisibleMovementImmediately() async throws {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore(policy: .init(
        maximumSpeedMetersPerSecond: 1_000,
        minimumDistanceMeters: 0
    ))
    let samples: [(TimeInterval, Double, Double)] = [
        (0, 39.00000, -121.00000),
        (2, 39.00005, -121.00000),
        (4, 39.00010, -121.00000),
        (6, 39.00015, -121.00000),
        (7, 39.00015, -120.99982)
    ]
    var track: RidAircraftTrack?
    for sample in samples {
        let result = await store.ingest(trackObservation(
            id: "IMMEDIATE-BEARING",
            at: start.addingTimeInterval(sample.0),
            latitude: sample.1,
            longitude: sample.2,
            headingDegrees: 361
        ))
        if case let .accepted(acceptedTrack) = result { track = acceptedTrack }
    }
    let eastBearing = try #require(OperationalMapGeometry.travelBearingDegrees(points: track?.points ?? []))
    #expect(abs(eastBearing - 90) < 0.2)
}

@Test func operationalTravelBearingWorksWithTwoSparsePointsAndTurnsWithoutHistoryLag() async throws {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore(policy: .init(minimumDistanceMeters: 0))
    let samples: [(TimeInterval, Double, Double)] = [
        (0, 39.00000, -121.00000),
        (30, 39.00000, -120.99997),
    ]
    var track: RidAircraftTrack?
    for sample in samples {
        let result = await store.ingest(trackObservation(
            id: "SPARSE-BEARING",
            at: start.addingTimeInterval(sample.0),
            latitude: sample.1,
            longitude: sample.2,
            headingDegrees: 361
        ))
        if case let .accepted(acceptedTrack) = result { track = acceptedTrack }
    }
    let eastBearing = try #require(OperationalMapGeometry.travelBearingDegrees(points: track?.points ?? []))
    #expect(abs(eastBearing - 90) < 0.2)

    let turn = await store.ingest(trackObservation(
        id: "SPARSE-BEARING",
        at: start.addingTimeInterval(60),
        latitude: 39.00003,
        longitude: -120.99997,
        headingDegrees: 361
    ))
    guard case let .accepted(turnedTrack) = turn else {
        Issue.record("Expected sparse turn point")
        return
    }
    let northBearing = try #require(OperationalMapGeometry.travelBearingDegrees(points: turnedTrack.points))
    #expect(abs(northBearing) < 0.2)
}

@Test func operationalMapGestureReleasesFocusedAircraft() {
    #expect(OperationalMapFocusPolicy.shouldReleaseFocus(
        hasFocusedAircraft: true,
        isOperatorGesture: true
    ))
    #expect(!OperationalMapFocusPolicy.shouldReleaseFocus(
        hasFocusedAircraft: false,
        isOperatorGesture: true
    ))
    #expect(!OperationalMapFocusPolicy.shouldReleaseFocus(
        hasFocusedAircraft: true,
        isOperatorGesture: false
    ))
}

@Test func operationalAircraftStatusLabelMatchesAndroidTokens() {
    #expect(OperationalAircraftDisplay.statusLabel(
        atoFeet: 125.2,
        aglFeet: 90.4,
        aglStale: false,
        rangeFeet: 420,
        headingDegrees: 273.2
    ) == "ATO:125' AGL:90' RNG:420' HDG:273°")
    #expect(OperationalAircraftDisplay.statusLabel(
        atoFeet: nil,
        aglFeet: 75,
        aglStale: true,
        rangeFeet: nil,
        headingDegrees: nil
    ) == "ATO:--' AGL:75?' RNG:--' HDG:--°")
    #expect(OperationalAircraftDisplay.statusLabel(
        atoFeet: 1_500,
        aglFeet: -1_500,
        aglStale: false,
        rangeFeet: 1_250,
        headingDegrees: 365
    ) == "ATO:--' AGL:--' RNG:1250' HDG:5°")
}

@Test func operationalAircraftStreamHeaderMatchesMapEntriesAndOrder() {
    #expect(OperationalAircraftDisplay.streamHeader(
        designator: "1SAR7Mn4pr",
        atoFeet: 125.2,
        aglFeet: 90.4,
        aglStale: false,
        rangeFeet: 420,
        headingDegrees: 273.2
    ) == "1SAR7Mn4pr  ATO:125' AGL:90' RNG:420' HDG:273°")
}

@Test func operationalAltitudeUsesAtoAndTerrainDeltaLikeAndroid() throws {
    var coordinator = OperationalAltitudeCoordinator()
    coordinator.ingest(RidObservation(
        source: .bluetoothExtended, aircraftId: "TEST",
        receivedAt: Date(timeIntervalSince1970: 100),
        latitude: 39, longitude: -105,
        altitudeMeters: 500, heightMeters: 0, heightReference: .takeoff
    ))
    coordinator.applyTakeoffTerrain(OperationalTerrainSample(elevationMeters: 450))
    coordinator.ingest(RidObservation(
        source: .bluetoothExtended, aircraftId: "TEST",
        receivedAt: Date(timeIntervalSince1970: 101),
        latitude: 39.0001, longitude: -105,
        altitudeMeters: 510, heightMeters: 10, heightReference: .takeoff
    ))
    coordinator.applyCurrentTerrain(
        OperationalTerrainSample(elevationMeters: 445),
        coordinate: try #require(coordinator.currentCoordinate)
    )

    #expect(abs((coordinator.display.atoFeet ?? 0) - 32.8084) < 0.001)
    #expect(abs((coordinator.display.aglFeet ?? 0) - 49.2126) < 0.001)
    #expect((coordinator.display.rangeFeet ?? 0) > 30)
    #expect(coordinator.display.aglUsesTerrain)
    #expect(!coordinator.display.aglStale)
}

@Test func operationalAltitudeExposesPeerTrafficTakeoffReference() throws {
    var coordinator = OperationalAltitudeCoordinator()
    coordinator.ingest(RidObservation(
        source: .bluetoothExtended, aircraftId: "TEST",
        receivedAt: Date(timeIntervalSince1970: 100),
        latitude: 39, longitude: -105,
        altitudeMeters: 500, heightMeters: 0, heightReference: .takeoff
    ))

    let reference = try #require(coordinator.peerTrafficReference)
    #expect(reference.takeoffCoordinate == .init(latitude: 39, longitude: -105))
    #expect(reference.reportedGroundAltitudeMeters == 500)
    let calibration = TrackerTrafficAltitudeCalibration(
        flightEpoch: "flight-1",
        state: "locked",
        reportedGroundAltitudeMeters: 500,
        correctionMeters: -50
    )
    #expect(calibration.normalizedMSLMeters(rawAltitudeMeters: 510) == 460)
    #expect(calibration.reportedAltitudeMeters(relativeUpMeters: 10) == 510)
}

@Test func operationalAltitudeClampsNegativeTerrainEstimateToGroundLevel() throws {
    var coordinator = OperationalAltitudeCoordinator()
    coordinator.ingest(RidObservation(
        source: .bluetoothExtended, aircraftId: "TEST",
        receivedAt: Date(timeIntervalSince1970: 100),
        latitude: 39, longitude: -105,
        altitudeMeters: 500, heightMeters: 0, heightReference: .takeoff
    ))
    coordinator.applyTakeoffTerrain(OperationalTerrainSample(elevationMeters: 500))
    coordinator.applyCurrentTerrain(
        OperationalTerrainSample(elevationMeters: 503),
        coordinate: try #require(coordinator.currentCoordinate)
    )

    #expect(coordinator.display.aglFeet == 0)
}

@Test func operationalAltitudeMarksCachedTerrainStale() throws {
    var coordinator = OperationalAltitudeCoordinator()
    coordinator.ingest(RidObservation(
        source: .bluetoothLegacy, aircraftId: "TEST", receivedAt: Date(),
        latitude: 39, longitude: -105,
        altitudeMeters: 500, heightMeters: 20, heightReference: .takeoff
    ))
    coordinator.applyTakeoffTerrain(OperationalTerrainSample(elevationMeters: 450))
    coordinator.applyCurrentTerrain(
        OperationalTerrainSample(elevationMeters: 450, stale: true),
        coordinate: try #require(coordinator.currentCoordinate)
    )
    #expect(coordinator.display.aglStale)
    #expect(coordinator.display.aglFeet != nil)
}

@Test func operationalAltitudeSealsStableSixSampleReference() {
    var coordinator = OperationalAltitudeCoordinator()
    for index in 0 ..< 6 {
        coordinator.ingest(RidObservation(
            source: .bluetoothExtended, aircraftId: "TEST",
            receivedAt: Date(timeIntervalSince1970: Double(index)),
            latitude: 39 + Double(index) * 0.00001, longitude: -105,
            altitudeMeters: 510, heightMeters: 10, heightReference: .takeoff
        ))
    }
    #expect(coordinator.seedSource == .automaticSealed)
}

@Test func operationalAltitudeManualCalibrationTargetsFiftyFeet() {
    var coordinator = OperationalAltitudeCoordinator()
    coordinator.ingest(RidObservation(
        source: .trackerRelay, aircraftId: "TEST", receivedAt: Date(),
        latitude: 39, longitude: -105, altitudeMeters: 510
    ))
    coordinator.manualCalibrateAtFiftyFeet()
    #expect(coordinator.seedSource == .manual)
    #expect(abs((coordinator.display.atoFeet ?? 0) - 50) < 0.001)
}

@Test func operationalClueProjectionMatchesAndroidFlatGroundFallback() {
    let nadir = OperationalClueGeometry.project(
        droneLatitude: 39,
        droneLongitude: -105,
        droneAltitudeMeters: 500,
        headingDegrees: 90,
        aglMeters: 100,
        gimbalAngleDegrees: -90
    )
    #expect(nadir.latitude == 39)
    #expect(nadir.longitude == -105)
    #expect(nadir.altitudeMeters == 400)

    let forward = OperationalClueGeometry.project(
        droneLatitude: 39,
        droneLongitude: -105,
        droneAltitudeMeters: 500,
        headingDegrees: 90,
        aglMeters: 100,
        gimbalAngleDegrees: -45
    )
    let relative = RidGeometry.relativePosition(
        fromLatitude: 39, longitude: -105,
        toLatitude: forward.latitude, longitude: forward.longitude
    )
    #expect(abs((relative?.distanceMeters ?? 0) - 100) < 0.1)
    #expect(abs((relative?.bearingDegrees ?? 0) - 90) < 0.1)
    #expect(forward.altitudeMeters == 400)

    let upward = OperationalClueGeometry.project(
        droneLatitude: 39,
        droneLongitude: -105,
        droneAltitudeMeters: 500,
        headingDegrees: 90,
        aglMeters: 100,
        gimbalAngleDegrees: 35
    )
    #expect(upward.latitude == 39)
    #expect(upward.longitude == -105)
    #expect(upward.altitudeMeters == 400)
}

@Test func operationalClueProjectionHeightPrefersFreshAglThenFallsBackSafely() throws {
    let agl = try #require(OperationalClueGeometry.selectedProjectionHeight(
        freshAGLMeters: 30,
        atoMeters: 25,
        validatedDJIRelativeUpMeters: 24
    ))
    #expect(agl.meters == 30)
    #expect(agl.sourceLabel == "fresh AGL")

    let ato = try #require(OperationalClueGeometry.selectedProjectionHeight(
        freshAGLMeters: nil,
        atoMeters: 25,
        validatedDJIRelativeUpMeters: 24
    ))
    #expect(ato.meters == 25)
    #expect(ato.sourceLabel == "ATO flat-ground fallback")
    let fieldFallback = OperationalClueGeometry.project(
        droneLatitude: 39,
        droneLongitude: -121,
        droneAltitudeMeters: 550,
        headingDegrees: 44,
        aglMeters: ato.meters,
        gimbalAngleDegrees: -25.6
    )
    let fieldOffset = try #require(RidGeometry.relativePosition(
        fromLatitude: 39,
        longitude: -121,
        toLatitude: fieldFallback.latitude,
        longitude: fieldFallback.longitude
    ))
    #expect(fieldOffset.distanceMeters > 51)
    #expect(fieldOffset.distanceMeters < 53)
    #expect(abs(fieldOffset.bearingDegrees - 44) < 0.1)

    let dji = try #require(OperationalClueGeometry.selectedProjectionHeight(
        freshAGLMeters: .nan,
        atoMeters: -1,
        validatedDJIRelativeUpMeters: 24
    ))
    #expect(dji.meters == 24)
    #expect(dji.sourceLabel.contains("validated DJI"))

    #expect(OperationalClueGeometry.selectedProjectionHeight(
        freshAGLMeters: nil,
        atoMeters: nil,
        validatedDJIRelativeUpMeters: nil
    ) == nil)
}

@Test func operationalClueProjectionUsesConfirmedCameraBearing() {
    let projected = OperationalClueGeometry.project(
        droneLatitude: 39.15419,
        droneLongitude: -121.1323089,
        droneAltitudeMeters: 543,
        headingDegrees: 275,
        aglMeters: 35.58111686159563,
        gimbalAngleDegrees: -23
    )
    let relative = RidGeometry.relativePosition(
        fromLatitude: 39.15419,
        longitude: -121.1323089,
        toLatitude: projected.latitude,
        longitude: projected.longitude
    )
    #expect(abs((relative?.bearingDegrees ?? 0) - 275) < 0.1)
    #expect((relative?.distanceMeters ?? 0) > 80)
}

@Test func operationalClueProjectionIntersectsRisingDEMTerrain() async {
    let projected = await OperationalClueGeometry.projectWithTerrain(
        droneLatitude: 39,
        droneLongitude: -105,
        droneAltitudeMeters: 100,
        headingDegrees: 0,
        aglMeters: 100,
        gimbalAngleDegrees: -45,
        sampleElevationMeters: { latitude, _ in
            let northMeters = (latitude - 39) * 111_195
            return OperationalTerrainSample(elevationMeters: max(0, northMeters * 0.5))
        }
    )
    let northMeters = (projected.latitude - 39) * 111_195
    #expect(northMeters > 66)
    #expect(northMeters < 68)
    #expect((projected.altitudeMeters ?? 0) > 33)
    #expect((projected.altitudeMeters ?? 0) < 34)
    #expect(projected.terrainProjectionApplied)
}

@Test func operationalClueProjectionFollowsShallowSightlineAcrossDescendingTerrain() async {
    let projected = await OperationalClueGeometry.projectWithTerrain(
        droneLatitude: 39,
        droneLongitude: -105,
        droneAltitudeMeters: 1_030,
        headingDegrees: 0,
        aglMeters: 30,
        gimbalAngleDegrees: -8,
        sampleElevationMeters: { latitude, _ in
            let northMeters = max(0, (latitude - 39) * 111_195)
            let groundMeters = northMeters <= 1_500
                ? 1_000 - (northMeters * 0.2)
                : 700 + ((northMeters - 1_500) * 0.1)
            return OperationalTerrainSample(
                elevationMeters: groundMeters,
                source: "usgs-geotiff-local-1m",
                horizontalResolutionMeters: 1
            )
        }
    )
    let northMeters = (projected.latitude - 39) * 111_195
    #expect(northMeters > 1_900)
    #expect(northMeters < 2_100)
    #expect(projected.terrainProjectionApplied)
    #expect(projected.demResolutionMeters == 1)
}

@Test func centerpointElevationTapRequiresTheConfiguredCenterRadius() {
    #expect(OperationalCenterpointElevation.isNearCenter(
        x: 500, y: 300, width: 1_000, height: 600, radius: 80
    ))
    #expect(OperationalCenterpointElevation.isNearCenter(
        x: 560, y: 340, width: 1_000, height: 600, radius: 80
    ))
    #expect(!OperationalCenterpointElevation.isNearCenter(
        x: 581, y: 300, width: 1_000, height: 600, radius: 80
    ))
    #expect(!OperationalCenterpointElevation.isNearCenter(
        x: 500, y: 300, width: 0, height: 600, radius: 80
    ))
}

@Test func centerpointElevationDisplayDistinguishesKnownResolutionFromOnlineDEM() {
    #expect(OperationalCenterpointElevation.displayText(.init(
        elevationFeet: 4_812,
        demResolutionMeters: 1
    )) == "4812' MSL · 1m DEM")
    #expect(OperationalCenterpointElevation.displayText(.init(
        elevationFeet: 4_812,
        demResolutionMeters: nil
    )) == "4812' MSL · USGS DEM")
    #expect(OperationalCenterpointElevation.displayText(nil) == "--' MSL")
    #expect(OperationalCenterpointElevation.displayText(
        .init(elevationFeet: 4_849, demResolutionMeters: 1),
        referenceElevationFeet: 4_812,
        mode: .reference
    ) == "+37' REF · 1m DEM")
    #expect(OperationalCenterpointElevation.displayText(
        .init(elevationFeet: 4_800, demResolutionMeters: nil),
        referenceElevationFeet: 4_812,
        mode: .reference
    ) == "-12' REF · USGS DEM")
    #expect(OperationalCenterpointElevation.displayText(
        .init(elevationFeet: 4_800, demResolutionMeters: nil),
        referenceElevationFeet: 4_812,
        mode: .msl
    ) == "4800' MSL · USGS DEM")
}

@Test func centerpointReferenceLongPressRequiresActiveFocusedCenterpoint() {
    #expect(OperationalCenterpointElevation.shouldSetReference(
        focused: true,
        elevationEnabled: true,
        pressNearCenter: true
    ))
    #expect(!OperationalCenterpointElevation.shouldSetReference(
        focused: false,
        elevationEnabled: true,
        pressNearCenter: true
    ))
    #expect(!OperationalCenterpointElevation.shouldSetReference(
        focused: true,
        elevationEnabled: false,
        pressNearCenter: true
    ))
    #expect(!OperationalCenterpointElevation.shouldSetReference(
        focused: true,
        elevationEnabled: true,
        pressNearCenter: false
    ))
}

@Test func operationalClueGimbalSelectionMatchesAndroidTelemetryFallback() {
    #expect(OperationalClueGeometry.selectedGimbalAngleDegrees(streamPitchDegrees: -44.5) == -44.5)
    #expect(OperationalClueGeometry.selectedGimbalAngleDegrees(streamPitchDegrees: -120) == -90)
    #expect(OperationalClueGeometry.selectedGimbalAngleDegrees(streamPitchDegrees: 12) == 12)
    #expect(OperationalClueGeometry.selectedGimbalAngleDegrees(streamPitchDegrees: nil) == -90)
    #expect(OperationalClueGeometry.selectedGimbalAngleDegrees(streamPitchDegrees: .nan) == -90)
}

@Test func operationalClueHeadingSelectionMatchesAndroidTelemetryPriority() {
    let djiHeading = OperationalClueGeometry.selectedHeading(
        cameraAzimuthDegrees: 111.46,
        cameraYawDegrees: 725,
        streamHeadingDegrees: 180,
        ridHeadingDegrees: 90,
        derivedHeadingDegrees: 274
    )
    #expect(abs((djiHeading.degrees ?? 0) - 111.46) < 0.000001)
    #expect(djiHeading.sourceLabel == "DJI camera azimuth")
    #expect(OperationalClueGeometry.selectedHeading(
        cameraYawDegrees: 725,
        streamHeadingDegrees: 180,
        ridHeadingDegrees: 90,
        derivedHeadingDegrees: 274
    ) == OperationalClueHeadingSelection(degrees: 274, sourceLabel: "Derived drone heading"))
    #expect(OperationalClueGeometry.selectedHeading(
        cameraYawDegrees: 725,
        streamHeadingDegrees: 180,
        ridHeadingDegrees: 90
    ) == OperationalClueHeadingSelection(degrees: 5, sourceLabel: "Camera yaw"))
    #expect(OperationalClueGeometry.selectedHeading(
        cameraYawDegrees: nil,
        streamHeadingDegrees: -10,
        ridHeadingDegrees: 90
    ) == OperationalClueHeadingSelection(degrees: 350, sourceLabel: "Stream heading"))
    #expect(OperationalClueGeometry.selectedHeading(
        cameraYawDegrees: .nan,
        streamHeadingDegrees: nil,
        ridHeadingDegrees: 361
    ) == OperationalClueHeadingSelection(degrees: 1, sourceLabel: "RID aircraft track"))
    #expect(OperationalClueGeometry.selectedHeading(
        cameraYawDegrees: nil,
        streamHeadingDegrees: nil,
        ridHeadingDegrees: nil
    ) == OperationalClueHeadingSelection(degrees: nil, sourceLabel: nil))
}

@Test func operationalClueRecordRoundTripsDurableUploadState() throws {
    let record = OperationalClueRecord(
        id: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
        capturedAt: Date(timeIntervalSince1970: 1_700_000_000),
        aircraftID: "RID01", designator: "ALPHA1",
        droneLatitude: 39, droneLongitude: -105, droneAltitudeMeters: 500,
        clueLatitude: 39.001, clueLongitude: -104.999, clueAltitudeMeters: 400,
        headingDegrees: 45, aglMeters: 100, atoMeters: 105,
        gimbalAngleDegrees: -45, title: "Clue 1", clueDescription: "Description",
        imageFilename: "a.jpg", thumbnailFilename: "a-thumb.jpg",
        uploadState: .failed, uploadAttempts: 3, lastUploadError: "offline",
        caltopoMediaID: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
    )
    let decoded = try JSONDecoder().decode(
        OperationalClueRecord.self,
        from: JSONEncoder().encode(record)
    )
    #expect(decoded == record)
}

@Test func operationalAircraftLabelLayoutSeparatesOverlappingLabels() throws {
    let inputs = [
        MapAircraftLabelInput(
            id: "a", anchor: MapScreenPoint(x: 100, y: 100),
            nameWidth: 80, nameHeight: 24, statusWidth: 190, statusHeight: 22
        ),
        MapAircraftLabelInput(
            id: "b", anchor: MapScreenPoint(x: 106, y: 104),
            nameWidth: 80, nameHeight: 24, statusWidth: 190, statusHeight: 22
        ),
    ]
    let layouts = OperationalAircraftDisplay.layoutLabels(inputs, viewportWidth: 360, viewportHeight: 300)
    let first = try #require(layouts.first { $0.id == "a" })
    let second = try #require(layouts.first { $0.id == "b" })
    #expect(!first.bounds.intersects(second.bounds))
    #expect(second.leaderEnd != nil)
}

@Test func operationalAircraftPredictionMatchesAndroidAgeAndLookaheadBounds() throws {
    let previous = MapCoordinate(latitude: 39, longitude: -105)
    let current = MapCoordinate(latitude: 39, longitude: -104.9999)
    let predicted = try #require(OperationalAircraftDisplay.predictedCoordinate(
        previous: previous,
        previousTime: Date(timeIntervalSince1970: 100),
        current: current,
        currentTime: Date(timeIntervalSince1970: 101),
        now: Date(timeIntervalSince1970: 102)
    ))
    #expect(predicted.longitude > current.longitude)
    #expect(OperationalAircraftDisplay.predictedCoordinate(
        previous: previous,
        previousTime: Date(timeIntervalSince1970: 100),
        current: current,
        currentTime: Date(timeIntervalSince1970: 101),
        now: Date(timeIntervalSince1970: 101.2)
    ) == nil)
}

@Test func caltopoArtifactDecoderBuildsVisibleOperationalOverlays() throws {
    let data = Data(#"""
    {"state":{"features":[
      {"id":"ops","properties":{"class":"Folder","title":"Operations","visible":true}},
      {"id":"hidden","properties":{"class":"Folder","title":"Hidden","visible":false}},
      {"id":"marker","geometry":{"type":"Point","coordinates":[-104.99,39.74]},"properties":{"class":"Marker","title":"Clue","description":"Inspect marker","folderId":"ops","marker-symbol":"aperture","marker-color":"#FF00FF"}},
      {"id":"line","geometry":{"type":"LineString","coordinates":[[-105,39.7],[-104.9,39.8]]},"properties":{"class":"Shape","title":"Search line","description":"Inspect line","folderId":"ops","stroke":"#00FF00","stroke-width":4}},
      {"id":"polygon","geometry":{"type":"Polygon","coordinates":[[[-105,39.7],[-104.9,39.7],[-104.9,39.8],[-105,39.7]]]},"properties":{"class":"Assignment","title":"Division A","description":"Search the north drainage","stroke":"#FF0000","stroke-opacity":0.5,"fill":"#FF0000","fill-opacity":0.2}},
      {"id":"hidden-marker","geometry":{"type":"Point","coordinates":[-104,39]},"properties":{"class":"Marker","title":"Hidden clue","folderId":"hidden"}},
      {"id":"hidden-media","geometry":{"type":"Point","coordinates":[-104,39]},"properties":{"class":"MapMediaObject","title":"Hidden clue photo","parentId":"Marker:hidden-marker","marker-symbol":"aperture"}},
      {"id":"live","geometry":{"type":"LineString","coordinates":[[-105,39],[-104,40]]},"properties":{"class":"LiveTrack","title":"Duplicate aircraft","folderId":"ops"}}
    ]}}
    """#.utf8)

    let snapshot = try CaltopoArtifactDecoder.decode(data: data)
    #expect(snapshot.totalFeatureCount == 8)
    let serverHidden = Set(snapshot.folders.filter { !$0.initiallyVisible }.map(\.id))
    let visible = snapshot.hiding(folderIDs: serverHidden)
    #expect(visible.points.map(\.title) == ["Clue"])
    #expect(snapshot.lines.map(\.title) == ["Search line"])
    #expect(snapshot.polygons.map(\.title) == ["Division A"])
    #expect(snapshot.points.first?.description == "Inspect marker")
    #expect(snapshot.lines.first?.description == "Inspect line")
    #expect(snapshot.polygons.first?.description == "Search the north drainage")
    #expect(snapshot.coordinates(forItemID: "polygon").count == 4)
    #expect(snapshot.coordinates(forItemID: "missing").isEmpty)
    #expect(snapshot.polygons.first?.strokeHex == "#7FFF0000")
    #expect(snapshot.polygons.first?.fillHex == "#33FF0000")
    #expect(snapshot.ignoredTrackCount == 1)
    #expect(snapshot.folders.contains { $0.title == "Operations" })
    #expect(snapshot.folders.contains { $0.title == "Assignments" })
    #expect(try JSONDecoder().decode(CaltopoArtifactSnapshot.self, from: JSONEncoder().encode(snapshot)) == snapshot)
}

@Test func caltopoArtifactVisibilityOperatorOverridesWinForActiveSession() {
    let resolved = CaltopoArtifactVisibilityPolicy.hiddenFolderIDs(
        localHidden: ["local-hidden", "operator-shown"],
        defaultHidden: ["server-hidden", "operator-shown"],
        operatorVisibilityOverrides: [
            "operator-shown": true,
            "operator-hidden": false,
        ]
    )

    #expect(resolved == ["local-hidden", "server-hidden", "operator-hidden"])
}

@Test func caltopoArtifactVisibilityFindsOnlyLegacyPersistedSelectionKeys() {
    let keys = CaltopoArtifactVisibilityPolicy.legacyPersistedSelectionKeys([
        "map.visibility.GF7A7ER.items",
        "map.visibility.GF7A7ER.folders",
        "map.pilotDisplay.N1234.active",
        "caltopo.mapID",
    ])

    #expect(keys == [
        "map.visibility.GF7A7ER.folders",
        "map.visibility.GF7A7ER.items",
    ])
}

@Test func operationalCoordinateFormatsMatchAndroid() {
    #expect(OperationalCoordinateDisplayFormat.restored(from: "usng") == .usng)
    #expect(OperationalCoordinateDisplayFormat.restored(from: "utm") == .utm)
    #expect(OperationalCoordinateDisplayFormat.restored(from: nil) == .decimal)
    #expect(OperationalCoordinateDisplayFormat.restored(from: "invalid") == .decimal)
    #expect(OperationalCoordinateFormatter.format(
        latitude: 39.9526,
        longitude: -75.1652,
        as: .decimal
    ) == "loc:39.95260,-75.16520")
    #expect(OperationalCoordinateFormatter.format(
        latitude: 39.9526,
        longitude: -75.1652,
        as: .utm
    ) == "loc:18S 485889 4422509")
    #expect(OperationalCoordinateFormatter.format(
        latitude: 39.9526,
        longitude: -75.1652,
        as: .usng
    ) == "loc:18S VK 85889 22509")
    #expect(OperationalCoordinateFormatter.format(
        latitude: .nan,
        longitude: -75.1652,
        as: .decimal
    ) == "loc:unknown")
}

@Test func caltopoArtifactDecoderAcceptsResultWrappedMapState() throws {
    let data = Data(#"{"result":{"state":{"features":[{"id":"marker","geometry":{"type":"Point","coordinates":[-104.99,39.74]},"properties":{"class":"Marker","title":"Wrapped clue"}}]}}}"#.utf8)
    let snapshot = try CaltopoArtifactDecoder.decode(data: data)
    #expect(snapshot.totalFeatureCount == 1)
    #expect(snapshot.points.map(\.title) == ["Wrapped clue"])
}

@Test func caltopoArtifactDecoderFindsDeepResponseEnvelopeAndShowsRootArtifacts() throws {
    let data = Data(#"{"response":{"data":{"result":{"state":{"features":[{"id":"line","geometry":{"type":"LineString","coordinates":[[-105,39.7],[-104.9,39.8]]},"properties":{"class":"Shape","title":"Root line"}}]}}}}}"#.utf8)
    let snapshot = try CaltopoArtifactDecoder.decode(data: data)
    #expect(snapshot.lines.map(\.title) == ["Root line"])
    #expect(snapshot.folders.first { $0.id == "__caltopo_lines_polygons__" }?.initiallyVisible == true)
}

@Test func caltopoArtifactSnapshotCountsDuplicateMarkerOccurrences() throws {
    let data = Data(#"""
    {"state":{"features":[
      {"id":"device-marker","geometry":{"type":"Point","coordinates":[-117.9,34.9]},"properties":{"class":"Marker","title":"R2C: iPad","folderId":"tracks"}},
      {"id":"DEVICE-MARKER","geometry":{"type":"Point","coordinates":[-117.9,34.9]},"properties":{"class":"Marker","title":"R2C: iPad","folderId":"tracks"}}
    ]}}
    """#.utf8)

    let snapshot = try CaltopoArtifactDecoder.decode(data: data)

    #expect(snapshot.occurrenceCount(ofItemID: " device-marker ") == 2)
    #expect(snapshot.occurrenceCount(ofItemID: "") == 0)
}

@Test func localClueArtifactSuppressionRemovesMarkerAndAttachedMedia() throws {
    let data = Data(#"""
    {"state":{"features":[
      {"id":"clues","properties":{"class":"Folder","title":"Clues"}},
      {"id":"local-marker","geometry":{"type":"Point","coordinates":[-121.13,39.15]},"properties":{"class":"Marker","title":"Cows","description":"Full clue details","folderId":"clues"}},
      {"id":"local-media","geometry":{"type":"Point","coordinates":[-121.13,39.15]},"properties":{"class":"MapMediaObject","title":"Cows","parentId":"Marker:local-marker","backendMediaId":"photo-id"}},
      {"id":"other-marker","geometry":{"type":"Point","coordinates":[-121.14,39.16]},"properties":{"class":"Marker","title":"Other","folderId":"clues"}}
    ]}}
    """#.utf8)

    let snapshot = try CaltopoArtifactDecoder.decode(data: data)
    let rendered = snapshot.excludingRenderedPointIDs(["LOCAL-MARKER", "LOCAL-MEDIA"])

    #expect(rendered.points.map(\.id) == ["other-marker"])
    #expect(rendered.items.count == snapshot.items.count)
}

@Test func caltopoArtifactVisibilitySuppressesOnlyGeometrylessReplacementAssignments() throws {
    let data = Data(#"""
    {"state":{"features":[
      {"id":"old-ad","properties":{"class":"Assignment","title":"AD 105"}},
      {"id":"current-ad","geometry":{"type":"Polygon","coordinates":[[[-117.9,34.9],[-117.8,34.9],[-117.8,35.0],[-117.9,34.9]]]},"properties":{"class":"Assignment","title":"AD 105"}},
      {"id":"pending-ae","properties":{"class":"Assignment","title":"AE 106"}}
    ]}}
    """#.utf8)

    let snapshot = try CaltopoArtifactDecoder.decode(data: data)

    #expect(snapshot.items.map(\.id).sorted() == ["current-ad", "old-ad", "pending-ae"])
    #expect(snapshot.visibilityItems.map(\.id).sorted() == ["current-ad", "pending-ae"])
}

@Test func caltopoArtifactVisibilityHonorsFolderHierarchyItemsAndOrphans() throws {
    let data = Data(#"""
    {"state":{"features":[
      {"id":"parent","properties":{"class":"Folder","title":"Operations","visible":true}},
      {"id":"child","properties":{"class":"Folder","title":"Division A","folderId":"parent","visible":true}},
      {"id":"child-marker","geometry":{"type":"Point","coordinates":[-105,39.7]},"properties":{"class":"Marker","title":"Child clue","folderId":"child"}},
      {"id":"orphan-line","geometry":{"type":"LineString","coordinates":[[-105,39.7],[-104.9,39.8]]},"properties":{"class":"Shape","title":"Orphan line","folderId":"missing-folder"}},
      {"id":"marker","geometry":{"type":"Point","coordinates":[-104.8,39.8]},"properties":{"class":"Marker","title":"Clue","folderId":"parent"}},
      {"id":"media","geometry":{"type":"Point","coordinates":[-104.8,39.8]},"properties":{"class":"MapMediaObject","title":"Clue photo","parentId":"Marker:marker"}}
    ]}}
    """#.utf8)

    let snapshot = try CaltopoArtifactDecoder.decode(data: data)
    #expect(snapshot.folders.first { $0.id == "child" }?.parentID == "parent")
    #expect(snapshot.folders.contains { $0.id == "missing-folder" && $0.title.hasPrefix("Unlisted Folder ") })
    #expect(snapshot.items.contains { $0.id == "child-marker" && $0.folderID == "child" })

    let parentHidden = snapshot.hiding(folderIDs: ["parent"])
    #expect(parentHidden.points.isEmpty)
    #expect(parentHidden.lines.map(\.title) == ["Orphan line"])

    let markerHidden = snapshot.hiding(folderIDs: [], itemIDs: ["marker"])
    #expect(markerHidden.points.map(\.title) == ["Child clue"])
}

@Test func caltopoItemVisibilityHidesEveryMultiGeometryComponent() throws {
    let data = Data(#"""
    {"state":{"features":[
      {"id":"ops","properties":{"class":"Folder","title":"Operations"}},
      {"id":"multi-line","geometry":{"type":"MultiLineString","coordinates":[[[-105,39],[-104,40]],[[-104,39],[-103,40]]]},"properties":{"class":"Shape","title":"Segments","folderId":"ops"}},
      {"id":"multi-area","geometry":{"type":"MultiPolygon","coordinates":[[[[-105,39],[-104,39],[-104,40],[-105,39]]],[[[-103,39],[-102,39],[-102,40],[-103,39]]]]},"properties":{"class":"Shape","title":"Areas","folderId":"ops"}}
    ]}}
    """#.utf8)

    let snapshot = try CaltopoArtifactDecoder.decode(data: data)
    #expect(snapshot.lines.count == 2)
    #expect(snapshot.polygons.count == 2)
    let hidden = snapshot.hiding(folderIDs: [], itemIDs: ["multi-line", "multi-area"])
    #expect(hidden.lines.isEmpty)
    #expect(hidden.polygons.isEmpty)
}

@Test func caltopoMapSnapshotRequestUsesSignedSinceZeroEndpoint() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        domainAndPort: "caltopo.com",
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0",
        connectKey: "NCSSAR-UAS"
    ))
    let request = try await client.makeMapSnapshotRequest(now: Date(timeIntervalSince1970: 1_700_000_000))
    #expect(request.httpMethod == "GET")
    let components = try #require(request.url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) })
    #expect(components.path == "/api/v1/map/map123/since/0")
    let values = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    #expect(values["id"] == "credential")
    #expect(values["expires"] == "1700000120000")
    #expect(values["signature"]?.isEmpty == false)
}

@Test func liveVideoRecoveryDetectsConnectionAndDecodedFrameStalls() {
    var policy = LiveVideoRecoveryPolicy(configuration: .init(
        connectionTimeout: 10,
        frameStallTimeout: 4,
        retryDelays: [1, 2]
    ))
    policy.reset(at: 100)

    #expect(policy.evaluate(at: 109.9) == nil)
    #expect(policy.evaluate(at: 110) == .reconnect(after: 1, trigger: .connectionTimedOut))
    #expect(policy.evaluate(at: 120) == nil)

    policy.beginRecoveryAttempt(at: 120)
    policy.recordDecodedFrame(at: 121)
    #expect(policy.evaluate(at: 124.9) == nil)
    #expect(policy.evaluate(at: 125) == .reconnect(after: 1, trigger: .decodedFramesStalled))
}

@Test func liveVideoRecoveryWaitsForMediaMtxPublisherThenRetriesImmediately() {
    var policy = LiveVideoRecoveryPolicy(configuration: .init(
        connectionTimeout: 3,
        frameStallTimeout: 2,
        retryDelays: [1, 2]
    ))
    policy.reset(at: 0)
    #expect(policy.setPublisherAvailable(false) == nil)
    #expect(policy.evaluate(at: 3) == .waitForPublisher(trigger: .connectionTimedOut))
    #expect(policy.setPublisherAvailable(true) == .reconnect(after: 0, trigger: .publisherReturned))
}

@Test func liveVideoRecoveryCancelsScheduledChurnWhenPublisherDisappears() {
    var policy = LiveVideoRecoveryPolicy(configuration: .init(
        connectionTimeout: 3,
        frameStallTimeout: 2,
        retryDelays: [1, 2]
    ))
    policy.reset(at: 0)
    #expect(policy.playerFailed(detail: "playlist 404") == .reconnect(
        after: 1,
        trigger: .playerFailed("playlist 404")
    ))
    #expect(policy.setPublisherAvailable(false) == .waitForPublisher(
        trigger: .playerFailed("playlist 404")
    ))
    #expect(policy.setPublisherAvailable(true) == .reconnect(after: 0, trigger: .publisherReturned))
}

@Test func liveVideoRecoveryBacksOffFailuresAndResetsAfterAFrame() {
    var policy = LiveVideoRecoveryPolicy(configuration: .init(
        connectionTimeout: 3,
        frameStallTimeout: 2,
        retryDelays: [1, 2, 4]
    ))
    policy.reset(at: 0)

    #expect(policy.playerFailed(detail: "404") == .reconnect(after: 1, trigger: .playerFailed("404")))
    policy.beginRecoveryAttempt(at: 1)
    #expect(policy.playerFailed(detail: "404") == .reconnect(after: 2, trigger: .playerFailed("404")))
    policy.beginRecoveryAttempt(at: 3)
    policy.recordDecodedFrame(at: 4)
    #expect(policy.consecutiveRecoveryCount == 0)
    #expect(policy.playerFailed(detail: "reset") == .reconnect(after: 1, trigger: .playerFailed("reset")))
}

@Test func liveVideoDecoderSelectionRemembersNativeIncompatibilityAcrossHLSFrames() {
    var policy = LiveVideoDecoderSelectionPolicy()

    policy.nativeDecoderFailed(decodedFramesThisAttempt: 0)
    #expect(policy.requiresHLSFallback)

    // Frames decoded by the HLS backend must not make the next recovery retry
    // the already-incompatible native backend.
    #expect(policy.requiresHLSFallback)

    policy.reset()
    #expect(!policy.requiresHLSFallback)
}

@Test func liveVideoDecoderSelectionRetriesNativeAfterAnEstablishedNativeSession() {
    var policy = LiveVideoDecoderSelectionPolicy()

    policy.nativeDecoderFailed(decodedFramesThisAttempt: 42)
    #expect(!policy.requiresHLSFallback)
}

@Test func bluetoothServiceDataDecodesBasicID() throws {
    let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: basicIDMessage("RID2CALTOPO12345"), counter: 7)
    )

    #expect(advertisement.messageCounter == 7)
    #expect(advertisement.messages.count == 1)
    guard case let .basicID(basicID) = advertisement.messages[0].payload else {
        Issue.record("Expected a Basic ID payload")
        return
    }
    #expect(basicID.idType == 1)
    #expect(basicID.aircraftType == 2)
    #expect(basicID.uasID == "RID2CALTOPO12345")
}

@Test func droneScoutRelayPingRecognizesDefaultIdentityWithoutLocation() throws {
    let relayPing = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: basicIDMessage("DroneScout Bridge"), counter: 8)
    )
    let aircraft = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: basicIDMessage("RID2CALTOPO12345"), counter: 9)
    )

    #expect(DroneScoutRelayPing.matches(relayPing))
    #expect(DroneScoutRelayPing.matches(identity: "dronescout_bridge 01"))
    #expect(!DroneScoutRelayPing.matches(aircraft))
}

@Test func terrainSchedulingAndNetworkCachePreserveFineResolution() {
    let first = OperationalAltitudeCoordinator.Coordinate(
        latitude: 39.153600,
        longitude: -121.132110
    )
    let nearby = OperationalAltitudeCoordinator.Coordinate(
        latitude: 39.153620,
        longitude: -121.132090
    )

    #expect(OperationalAltitudeCoordinator.terrainKey(first) != OperationalAltitudeCoordinator.terrainKey(nearby))
    #expect(OperationalAltitudeCoordinator.terrainCacheKey(first) != OperationalAltitudeCoordinator.terrainCacheKey(nearby))
}

@Test func geoTiffUTMConversionMatchesKnownCoordinate() throws {
    let utm = try #require(GeoTiffElevationSource.latLonToUTM(
        latitude: 43.19113, longitude: -110.92524, epsg: 26912
    ))
    #expect(abs(utm.x - 506_075) < 5)
    #expect(abs(utm.y - 4_782_042) < 5)
}

@Test func geoTiffConusAlbersConversionMatchesEpsg6350Reference() {
    let albers = GeoTiffElevationSource.latLonToConusAlbers(
        latitude: 39.2616, longitude: -121.0608
    )
    #expect(abs(albers.x + 2_117_785.808) < 0.02)
    #expect(abs(albers.y - 2_085_095.786) < 0.02)
}

@Test func s1mCatalogSelectsOnlyTheTileContainingTheRequestedPoint() throws {
    let data = Data(#"{"items":[{"downloadURL":"https://example.test/outside.tif","boundingBox":{"minX":-120.0,"maxX":-119.9,"minY":38.0,"maxY":38.1}},{"downloadURL":"https://example.test/S1M_tile.tif","sizeInBytes":350000000,"boundingBox":{"minX":-121.1,"maxX":-121.0,"minY":39.2,"maxY":39.3}}]}"#.utf8)
    let products = try OperationalS1MCatalog.products(
        data: data,
        containing: (latitude: 39.2616, longitude: -121.0608)
    )
    let result = try #require(products.first)
    #expect(result.url.absoluteString == "https://example.test/S1M_tile.tif")
    #expect(result.fileName.hasPrefix("R2C_S1M_"))
    #expect(result.expectedBytes == 350_000_000)
}

@Test func geoTiffRecognizesPlannerEncodedOneMeterBounds() throws {
    let bounds = try #require(GeoTiffElevationSource.tileBounds(
        fileName: "R2C_S1M_4317269_4326281_-11100000_-11087679_S1M_example.tif"
    ))
    #expect(abs(bounds.south - 43.17269) < 0.000001)
    #expect(abs(bounds.east + 110.87679) < 0.000001)
}

@Test func droneScoutBridgeLossGateAnnouncesOnceAndResetsAfterPing() {
    #expect(DroneScoutRelayPing.signalFreshnessSeconds == 32)
    #expect(DroneScoutBridgeLossAnnouncementGate.defaultThreshold == 32)
    var gate = DroneScoutBridgeLossAnnouncementGate()
    let start = Date(timeIntervalSince1970: 1_000)

    let initial = gate.shouldAnnounce(
        monitoringActive: true, lastPingAt: nil, now: start, muted: false
    )
    let atBoundary = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: nil,
        now: start.addingTimeInterval(32),
        muted: false
    )
    let crossedBoundary = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: nil,
        now: start.addingTimeInterval(32.001),
        muted: false
    )
    let repeatedLoss = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: nil,
        now: start.addingTimeInterval(30),
        muted: false
    )
    #expect(!initial)
    #expect(!atBoundary)
    #expect(crossedBoundary)
    #expect(!repeatedLoss)

    let restored = start.addingTimeInterval(30)
    let restoredPing = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: restored,
        now: restored.addingTimeInterval(1),
        muted: false
    )
    let lostAgain = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: restored,
        now: restored.addingTimeInterval(32.001),
        muted: false
    )
    #expect(!restoredPing)
    #expect(lostAgain)
}

@Test func droneScoutBridgeLossGateHonorsMuteAndMonitoringState() {
    var gate = DroneScoutBridgeLossAnnouncementGate()
    let start = Date(timeIntervalSince1970: 2_000)

    let initial = gate.shouldAnnounce(
        monitoringActive: true, lastPingAt: nil, now: start, muted: false
    )
    let mutedLoss = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: nil,
        now: start.addingTimeInterval(32.001),
        muted: true
    )
    let unmutedSameLoss = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: nil,
        now: start.addingTimeInterval(30),
        muted: false
    )
    let stopped = gate.shouldAnnounce(
        monitoringActive: false,
        lastPingAt: nil,
        now: start.addingTimeInterval(31),
        muted: false
    )
    let restarted = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: nil,
        now: start.addingTimeInterval(40),
        muted: false
    )
    let restartedLoss = gate.shouldAnnounce(
        monitoringActive: true,
        lastPingAt: nil,
        now: start.addingTimeInterval(72.001),
        muted: false
    )
    #expect(!initial)
    #expect(!mutedLoss)
    #expect(!unmutedSameLoss)
    #expect(!stopped)
    #expect(!restarted)
    #expect(restartedLoss)
}

@Test func locationDecoderMatchesAndroidWireFormulas() throws {
    let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: locationMessage(), counter: 9)
    )

    guard case let .location(location) = advertisement.messages[0].payload else {
        Issue.record("Expected a Location payload")
        return
    }
    #expect(abs(location.latitude - 39.7392) < 0.0000001)
    #expect(abs(location.longitude - -104.9903) < 0.0000001)
    #expect(location.pressureAltitudeMeters == 100)
    #expect(location.geodeticAltitudeMeters == 200)
    #expect(location.heightMeters == 50)
    #expect(location.horizontalAccuracyCode == 10)
    #expect(location.directionDegrees == 90)
    #expect(location.horizontalSpeedMetersPerSecond == 10)
    #expect(location.verticalSpeedMetersPerSecond == -2)

    let wrappedAdvertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(
            message: locationMessage(direction: 181, eastWest: true),
            counter: 10
        )
    )
    guard case let .location(wrapped) = wrappedAdvertisement.messages[0].payload else {
        Issue.record("Expected a wrapped Location payload")
        return
    }
    #expect(wrapped.directionDegrees == nil)

    let invalidSpeedAdvertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(
            message: locationMessage(horizontalSpeed: 0xFF, verticalSpeed: 126),
            counter: 11
        )
    )
    guard case let .location(invalidSpeed) = invalidSpeedAdvertisement.messages[0].payload else {
        Issue.record("Expected an invalid-speed Location payload")
        return
    }
    #expect(invalidSpeed.horizontalSpeedMetersPerSecond == nil)
    #expect(invalidSpeed.verticalSpeedMetersPerSecond == nil)
}

@Test func packedBasicIDAndLocationProduceObservation() async throws {
    let basic = basicIDMessage("SESSION-ID")
    let serial = basicIDMessage("RID2CALTOPO12345")
    let location = locationMessage()
    let pack = messagePack([basic, serial, location])
    let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: pack, counter: 11)
    )

    #expect(advertisement.messages.count == 3)
    let assembler = OpenDroneIDTrackAssembler()
    let observation = await assembler.ingest(
        advertisement,
        transmitterID: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
        source: .bluetoothExtended,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        signalStrengthDbm: -48
    )

    #expect(observation?.aircraftId == "RID2CALTOPO12345")
    #expect(observation?.source == .bluetoothExtended)
    #expect(observation?.altitudeMeters == 100)
    #expect(observation?.heightMeters == 50)
    #expect(observation?.heightReference == .takeoff)
    #expect(observation?.horizontalAccuracyCode == 10)
    #expect(observation?.signalStrengthDbm == -48)
}

@Test func assemblerDoesNotReemitCachedLocationForIdentityOnlyAdvertisement() async throws {
    let transmitterID = UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
    let assembler = OpenDroneIDTrackAssembler()
    let initial = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(
            message: messagePack([
                basicIDMessage("RID2CALTOPO12345"),
                locationMessage(),
            ]),
            counter: 20
        )
    )
    let initialObservation = await assembler.ingest(
        initial,
        transmitterID: transmitterID,
        source: .bluetoothExtended,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        signalStrengthDbm: -48
    )
    #expect(initialObservation != nil)

    let identityOnly = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: basicIDMessage("RID2CALTOPO12345"), counter: 21)
    )
    let staleObservation = await assembler.ingest(
        identityOnly,
        transmitterID: transmitterID,
        source: .bluetoothExtended,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_001),
        signalStrengthDbm: -47
    )
    #expect(staleObservation == nil)

    let locationOnly = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: locationMessage(), counter: 22)
    )
    let freshObservation = await assembler.ingest(
        locationOnly,
        transmitterID: transmitterID,
        source: .bluetoothExtended,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_002),
        signalStrengthDbm: -46
    )
    #expect(freshObservation?.aircraftId == "RID2CALTOPO12345")
    #expect(freshObservation?.signalStrengthDbm == -46)
}

@Test func assemblerReportsWhyAnAdvertisementDidNotProduceAnObservation() async throws {
    let transmitterID = UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
    let assembler = OpenDroneIDTrackAssembler()
    let locationOnly = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: locationMessage(), counter: 30)
    )
    let missingIdentity = await assembler.ingestWithResult(
        locationOnly,
        transmitterID: transmitterID,
        source: .bluetoothLegacy,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        signalStrengthDbm: -60
    )
    #expect(missingIdentity.observation == nil)
    #expect(missingIdentity.disposition == .missingIdentity)

    let identityOnly = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: basicIDMessage("RID2CALTOPO12345"), counter: 31)
    )
    let noFreshLocation = await assembler.ingestWithResult(
        identityOnly,
        transmitterID: transmitterID,
        source: .bluetoothLegacy,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_001),
        signalStrengthDbm: -59
    )
    #expect(noFreshLocation.observation == nil)
    #expect(noFreshLocation.disposition == .noFreshLocation)
    #expect(noFreshLocation.aircraftID == "RID2CALTOPO12345")

    let recovered = await assembler.ingestWithResult(
        locationOnly,
        transmitterID: transmitterID,
        source: .bluetoothLegacy,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_002),
        signalStrengthDbm: -58
    )
    #expect(recovered.disposition == .observation)
    #expect(recovered.observation?.aircraftId == "RID2CALTOPO12345")
}

@Test func nonLocationAircraftMessageRefreshesLifecycleWithoutRefreshingLocationSignal() async {
    let store = RidTrackStore(policy: .init(activeTimeout: 30))
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    _ = await store.ingest(RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "RID2CALTOPO12345",
        receivedAt: start,
        latitude: 39.7392,
        longitude: -104.9903,
        signalStrengthDbm: -61
    ))

    let refreshed = await store.noteAircraftMessage(RidAircraftMessage(
        source: .bluetoothLegacy,
        aircraftID: "RID2CALTOPO12345",
        receivedAt: start.addingTimeInterval(25)
    ))

    #expect(refreshed)
    let track = await store.snapshot().first
    #expect(track?.lastSignalAt == start)
    #expect(track?.lastAircraftMessageAt == start.addingTimeInterval(25))
    #expect(await store.removeInactive(at: start.addingTimeInterval(50)).isEmpty)
    #expect(await store.removeInactive(at: start.addingTimeInterval(56)).map(\.aircraftID) == [
        "RID2CALTOPO12345"
    ])
}

@Test func pairedVideoKeepsFlightActiveAndRestartPreservesRemoteIDBinding() async {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore(policy: .init(activeTimeout: 30))
    _ = await store.ingest(RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "RID2CALTOPO12345",
        receivedAt: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    var video = PairedVideoFlightActivityStore()
    video.pair(streamID: "RC2/Red1", aircraftID: "RID2CALTOPO12345")
    video.publisherStarted(streamID: "RC2/Red1", at: start.addingTimeInterval(5))

    let whilePublishing = start.addingTimeInterval(90)
    #expect(await store.removeInactive(
        at: whilePublishing,
        pairedVideoLastActivityAt: video.activityByAircraftID(at: whilePublishing)
    ).isEmpty)

    video.publisherStopped(streamID: "RC2/Red1", at: whilePublishing)
    #expect(await store.removeInactive(
        at: whilePublishing.addingTimeInterval(29),
        pairedVideoLastActivityAt: video.activityByAircraftID(at: whilePublishing.addingTimeInterval(29))
    ).isEmpty)

    video.publisherStarted(streamID: "rc2/red1", at: whilePublishing.addingTimeInterval(29))
    #expect(video.boundAircraftID(for: "RC2/RED1") == "RID2CALTOPO12345")
    #expect(await store.removeInactive(
        at: whilePublishing.addingTimeInterval(60),
        pairedVideoLastActivityAt: video.activityByAircraftID(at: whilePublishing.addingTimeInterval(60))
    ).isEmpty)
}

@Test func flightEndsThirtySecondsAfterRidAndPairedVideoAreBothAbsent() async {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore(policy: .init(activeTimeout: 30))
    _ = await store.ingest(RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "RID2CALTOPO12345",
        receivedAt: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    var video = PairedVideoFlightActivityStore()
    video.pair(streamID: "Red1", aircraftID: "RID2CALTOPO12345")
    let videoStoppedAt = start.addingTimeInterval(60)
    video.publisherStarted(streamID: "Red1", at: start.addingTimeInterval(5))
    video.publisherStopped(streamID: "Red1", at: videoStoppedAt)

    #expect(await store.removeInactive(
        at: videoStoppedAt.addingTimeInterval(29.999),
        pairedVideoLastActivityAt: video.activityByAircraftID(at: videoStoppedAt.addingTimeInterval(29.999))
    ).isEmpty)
    #expect(await store.removeInactive(
        at: videoStoppedAt.addingTimeInterval(30.001),
        pairedVideoLastActivityAt: video.activityByAircraftID(at: videoStoppedAt.addingTimeInterval(30.001))
    ).map(\.aircraftID) == ["RID2CALTOPO12345"])
}

@Test func droneScoutSelfIDIdentifiesRelayAndBridgeInputRSSI() async throws {
    let metadata = DroneScoutRelayMetadata.parse("DS WIFI B -74 dBm drone")
    #expect(metadata?.droneToBridgeRssiDbm == -74)
    #expect(metadata?.receptionMode == "WIFI B")
    #expect(metadata?.sourceKind == "drone")
    #expect(DroneScoutRelayMetadata.parse("Search aircraft alpha") == nil)

    let pack = messagePack([
        basicIDMessage("RID2CALTOPO12345"),
        selfIDMessage("DS WIFI B -74 dBm drone"),
        locationMessage(),
    ])
    let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: pack, counter: 12)
    )
    let observation = await OpenDroneIDTrackAssembler().ingest(
        advertisement,
        transmitterID: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
        source: .bluetoothExtended,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        signalStrengthDbm: -48
    )

    #expect(observation?.signalStrengthDbm == -48)
    #expect(observation?.droneScoutRelay?.droneToBridgeRssiDbm == -74)
}

@Test func trackStorePreservesDirectRSSIWhenRelayedPacketArrives() async {
    let store = RidTrackStore()
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    _ = await store.ingest(RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "RID2CALTOPO12345",
        receivedAt: start,
        latitude: 39.7392,
        longitude: -104.9903,
        signalStrengthDbm: -61
    ))
    _ = await store.ingest(RidObservation(
        source: .bluetoothExtended,
        aircraftId: "RID2CALTOPO12345",
        receivedAt: start.addingTimeInterval(4),
        latitude: 39.7393,
        longitude: -104.9903,
        signalStrengthDbm: -42,
        droneScoutRelay: DroneScoutRelayMetadata(
            droneToBridgeRssiDbm: -78,
            receptionMode: "WIFI B",
            sourceKind: "drone"
        )
    ))

    let track = await store.snapshot().first
    #expect(track?.lastSignalStrengthDbm == -42)
    #expect(track?.lastDirectSignalStrengthDbm == -61)
    #expect(track?.lastDirectSignalSource == .bluetoothLegacy)
    #expect(track?.lastDroneToBridgeSignalStrengthDbm == -78)
}

@Test func trackStoreRejectsPoorHorizontalAccuracyBeforeTakeoffAndAltitudeSeed() async throws {
    let store = RidTrackStore()
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let stale = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "AUTEL",
        receivedAt: start,
        latitude: 39.146693,
        longitude: -121.112983,
        altitudeMeters: 517,
        heightMeters: 0,
        heightReference: .takeoff,
        horizontalAccuracyCode: 9
    )
    let rejected = await store.ingest(stale)
    guard case let .rejectedHorizontalAccuracy(code, track) = rejected else {
        Issue.record("Expected the <30 m startup fix to be rejected")
        return
    }
    #expect(code == 9)
    #expect(track == nil)
    #expect(await store.snapshot().isEmpty)

    let actual = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "AUTEL",
        receivedAt: start.addingTimeInterval(3),
        latitude: 39.153078,
        longitude: -121.132800,
        altitudeMeters: 549,
        heightMeters: 5,
        heightReference: .takeoff,
        horizontalAccuracyCode: 10
    )
    let accepted = await store.ingest(actual)
    guard case let .accepted(track) = accepted else {
        Issue.record("Expected the <10 m fix to establish the flight")
        return
    }
    #expect(track.points.count == 1)
    #expect(track.points[0].latitude == actual.latitude)
    #expect(track.points[0].longitude == actual.longitude)

    var coordinator = OperationalAltitudeCoordinator()
    coordinator.ingest(track.lastObservation)
    #expect(coordinator.takeoffCoordinate == .init(
        latitude: actual.latitude,
        longitude: actual.longitude
    ))

    let poorWhileActive = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "AUTEL",
        receivedAt: start.addingTimeInterval(4),
        latitude: 39.20,
        longitude: -121.20,
        horizontalAccuracyCode: 0
    )
    let activeRejected = await store.ingest(poorWhileActive)
    guard case let .rejectedHorizontalAccuracy(activeCode, activeTrack) = activeRejected else {
        Issue.record("Expected unknown accuracy to remain signal-only")
        return
    }
    #expect(activeCode == 0)
    #expect(activeTrack?.points.count == 1)
    #expect(activeTrack?.lastSignalAt == poorWhileActive.receivedAt)
}

@Test func malformedServiceDataIsRejected() {
    #expect(throws: OpenDroneIDParserError.invalidApplicationCode(0x00)) {
        try OpenDroneIDParser.parseBluetoothServiceData(Data([0x00, 0x01]))
    }
    #expect(throws: OpenDroneIDParserError.truncatedMessage(expected: 25, actual: 3)) {
        try OpenDroneIDParser.parseBluetoothServiceData(Data([0x0D, 0x01, 0x02, 0x12, 0x41]))
    }
}

@Test func trackStoreMatchesAndroidDedupAndSpeedPolicy() async {
    let policy = RidTrackPolicy(maximumSpeedMetersPerSecond: 90)
    let store = RidTrackStore(policy: policy)
    let start = Date(timeIntervalSince1970: 1_700_000_000)

    let first = await store.ingest(trackObservation(
        id: "rid-test_01",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    guard case let .accepted(firstTrack) = first else {
        Issue.record("Expected first waypoint to be accepted")
        return
    }
    #expect(firstTrack.aircraftID == "RIDTEST01")

    let duplicate = await store.ingest(trackObservation(
        id: "RIDTEST01",
        at: start.addingTimeInterval(1),
        latitude: 39.7392,
        longitude: -104.9903,
        source: .wifiBeacon,
        signalStrengthDbm: -72
    ))
    guard case let .signalOnly(duplicateTrack, reason) = duplicate else {
        Issue.record("Expected duplicate to refresh signal without adding a point")
        return
    }
    #expect(reason == .duplicatePosition)
    #expect(duplicateTrack.points.count == 1)
    #expect(duplicateTrack.lastSignalAt == start.addingTimeInterval(1))
    #expect(duplicateTrack.lastSignalSource == .wifiBeacon)
    #expect(duplicateTrack.lastSignalStrengthDbm == -72)

    let impossible = await store.ingest(trackObservation(
        id: "RIDTEST01",
        at: start.addingTimeInterval(2),
        latitude: 40.7392,
        longitude: -104.9903
    ))
    guard case let .signalOnly(impossibleTrack, reason) = impossible else {
        Issue.record("Expected coordinate jump to be signal-only")
        return
    }
    guard case let .implausibleSpeed(speed) = reason else {
        Issue.record("Expected implausible-speed reason")
        return
    }
    #expect(speed > 90)
    #expect(impossibleTrack.points.count == 1)
}

@Test func defaultTrackSpeedCeilingAllowsGpsToleranceUpToTwoHundredMilesPerHour() {
    let policy = RidTrackPolicy()
    #expect(abs(policy.maximumSpeedMetersPerSecond * 2.236936 - 200) < 0.01)
}

@Test func trackStoreAcceptsStationaryKeepaliveAndAgesTracks() async {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore()
    _ = await store.ingest(trackObservation(
        id: "KEEPALIVE",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    let keepalive = await store.ingest(trackObservation(
        id: "KEEPALIVE",
        at: start.addingTimeInterval(3),
        latitude: 39.7392,
        longitude: -104.9903
    ))
    guard case let .accepted(track) = keepalive else {
        Issue.record("Expected the three-second stationary keepalive to be retained")
        return
    }
    #expect(track.points.count == 2)
    #expect(await store.activeSnapshot(at: start.addingTimeInterval(32)).count == 1)
    #expect(await store.activeSnapshot(at: start.addingTimeInterval(34)).isEmpty)
    #expect(await store.removeInactive(at: start.addingTimeInterval(34)).map(\.aircraftID) == ["KEEPALIVE"])
}

@Test func trackStoreAppliesAndroidMinimumDistanceAndRuntimeDelaySettings() async {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore(policy: .init(
        minimumDistanceMeters: 2,
        activeTimeout: 30
    ))
    _ = await store.ingest(trackObservation(
        id: "MINIMUM",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    let nearby = await store.ingest(trackObservation(
        id: "MINIMUM",
        at: start.addingTimeInterval(5),
        latitude: 39.73921,
        longitude: -104.9903
    ))
    guard case let .signalOnly(track, reason) = nearby else {
        Issue.record("Expected a sub-threshold waypoint to be signal-only")
        return
    }
    guard case let .belowMinimumDistance(meters) = reason else {
        Issue.record("Expected below-minimum-distance reason")
        return
    }
    #expect(meters > 1)
    #expect(meters < 2)
    #expect(track.points.count == 1)

    var updated = await store.policy
    updated.activeTimeout = 10
    await store.updatePolicy(updated)
    #expect(await store.activeSnapshot(at: start.addingTimeInterval(14)).count == 1)
    #expect(await store.activeSnapshot(at: start.addingTimeInterval(16)).isEmpty)
}

@Test func trackStoreDoesNotExtendFlightFromDistanceAlone() async {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore(policy: .init(
        minimumDistanceMeters: 0,
        activeTimeout: 30
    ))
    _ = await store.ingest(trackObservation(
        id: "REMOTE",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    _ = await store.ingest(trackObservation(
        id: "REMOTE",
        at: start.addingTimeInterval(10),
        latitude: 39.7400,
        longitude: -104.9903
    ))
    #expect(await store.activeSnapshot(at: start.addingTimeInterval(39)).count == 1)
    #expect(await store.activeSnapshot(at: start.addingTimeInterval(41)).isEmpty)
}

@Test func trafficSeparationReturnsEveryPairInDistanceOrder() {
    let positions = [
        RidTrafficPosition(aircraftID: "A", latitude: 39, longitude: -105, altitudeMeters: 100),
        RidTrafficPosition(aircraftID: "B", latitude: 39, longitude: -104.999, altitudeMeters: 110),
        RidTrafficPosition(aircraftID: "C", latitude: 39, longitude: -104.997, altitudeMeters: 130),
    ]
    let pairs = RidTrafficSeparation.allPairs(in: positions)
    #expect(pairs.count == 3)
    #expect(pairs[0].firstAircraftID == "A")
    #expect(pairs[0].secondAircraftID == "B")
    #expect(pairs[0].verticalMeters == 10)
    #expect(pairs[2].firstAircraftID == "A")
    #expect(pairs[2].secondAircraftID == "C")
}

@Test func trackGeoJsonMatchesAndroidArchiveEnvelope() async throws {
    let store = RidTrackStore()
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    _ = await store.ingest(trackObservation(
        id: "ARCHIVE01",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    _ = await store.ingest(trackObservation(
        id: "ARCHIVE01",
        at: start.addingTimeInterval(10),
        latitude: 39.7398,
        longitude: -104.9897
    ))
    let track = try #require(await store.snapshot().first)
    let data = try RidTrackGeoJSON.encode(
        track: track,
        metadata: RidTrackArchiveMetadata(
            mappedID: "ALPHA1",
            organization: "NCSSAR",
            incident: "Test Incident",
            operationalPeriod: "1",
            mapID: "map-123",
            buildVersion: "1.7.0",
            buildTime: "02Aug2026:092559"
        )
    )
    let root = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    #expect(root["type"] as? String == "FeatureCollection")
    let features = try #require(root["features"] as? [[String: Any]])
    let feature = try #require(features.first)
    let geometry = try #require(feature["geometry"] as? [String: Any])
    #expect(geometry["type"] as? String == "LineString")
    let coordinates = try #require(geometry["coordinates"] as? [[String]])
    #expect(coordinates.count == 2)
    #expect(coordinates[0][0] == "-104.990300")
    #expect(coordinates[0][1] == "39.739200")
    #expect(coordinates[0][3] == "1700000000000")
    let properties = try #require(feature["properties"] as? [String: Any])
    #expect(properties["title"] as? String == "ALPHA1")
    let r2c = try #require(properties["r2c_prop"] as? [String: Any])
    #expect(r2c["rid"] as? String == "ARCHIVE01")
    #expect(r2c["org"] as? String == "NCSSAR")
    #expect(r2c["map_id"] as? String == "map-123")
    #expect(r2c["BUILD_VERSION"] as? String == "1.7.0")
    #expect(r2c["BUILD_TIME"] as? String == "02Aug2026:092559")
}

@Test func operatorArchiveFilenamesUseOneLocalizedTimestampContract() async throws {
    let pacific = try #require(TimeZone(identifier: "America/Los_Angeles"))
    let start = try #require(ISO8601DateFormatter().date(from: "2026-08-31T17:54:45Z"))
    let store = RidTrackStore()
    _ = await store.ingest(trackObservation(
        id: "ARCHIVE01",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    let track = try #require(await store.snapshot().first)

    let timestamp = OperationalDiagnosticLogFormat.filenameTimestamp(
        start,
        timeZone: pacific
    )
    #expect(timestamp == "31Aug2026-105445-PDT-0700")
    #expect(
        RidTrackGeoJSON.suggestedFilename(for: track, timeZone: pacific)
            == "ARCHIVE01-31Aug2026-105445-PDT-0700.json"
    )
    #expect(
        RidTrackGeoJSON.suggestedClueReportFilename(for: track, timeZone: pacific)
            == "ARCHIVE01-31Aug2026-105445-PDT-0700.kmz"
    )
}

@Test func buildTimeMatchesAndroidArchiveFormat() throws {
    let date = try #require(ISO8601DateFormatter().date(from: "2026-08-02T16:25:59Z"))
    let timeZone = try #require(TimeZone(secondsFromGMT: -7 * 60 * 60))
    #expect(RidBuildMetadata.formattedBuildTime(date, timeZone: timeZone) == "02Aug2026:092559")
}

@Test func trackerArchiveUploadMatchesAndroidEligibilityAndRequestContract() throws {
    let configuration = TrackerArchiveUploadConfiguration(
        urlPrefix: "https://tracker.example.test/r2c/",
        apiKey: "secret-token",
        organization: " ncssar "
    )
    let eligible = Data("""
    {"features":[{"properties":{"r2c_prop":{"org":"NCSSAR","rid":"RID-1","local_archive_only":false}}}]}
    """.utf8)
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: eligible,
        configuration: configuration,
        knownRemoteIDs: ["RID-1"]
    ) == .eligible)

    let request = try TrackerArchiveUploadContract.makeRequest(
        geoJSON: eligible,
        configuration: configuration
    )
    #expect(request.url?.absoluteString == "https://tracker.example.test/r2c/upload")
    #expect(request.httpMethod == "PUT")
    #expect(request.value(forHTTPHeaderField: "X-SAR-Token") == "secret-token")
    #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json; charset=utf-8")
    #expect(request.httpBody == eligible)
}

@Test func trackerArchiveUploadScopesRootPrefixToOrganization() throws {
    let configuration = TrackerArchiveUploadConfiguration(
        urlPrefix: "https://r2c-tracker.com",
        apiKey: "device-token",
        organization: " NCSSAR "
    )
    let request = try TrackerArchiveUploadContract.makeRequest(
        geoJSON: Data("{}".utf8),
        configuration: configuration
    )
    #expect(configuration.urlPrefix == "https://r2c-tracker.com/ncssar")
    #expect(request.url?.absoluteString == "https://r2c-tracker.com/ncssar/upload")
}

@Test func trackerArchiveUploadRejectsLocalForeignAndUnknownTracks() throws {
    let configuration = TrackerArchiveUploadConfiguration(
        urlPrefix: "https://tracker.example.test",
        apiKey: "token",
        organization: "NCSSAR"
    )
    func archive(org: String, rid: String, localOnly: Bool = false) -> Data {
        Data("""
        {"features":[{"properties":{"r2c_prop":{"org":"\(org)","rid":"\(rid)","local_archive_only":\(localOnly)}}}]}
        """.utf8)
    }
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: archive(org: "NCSSAR", rid: "RID-1", localOnly: true),
        configuration: configuration, knownRemoteIDs: ["RID-1"]
    ) == .localArchiveOnly)
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: archive(org: "OTHER", rid: "RID-1"),
        configuration: configuration, knownRemoteIDs: ["RID-1"]
    ) == .organizationMismatch)
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: archive(org: "NCSSAR", rid: "UNKNOWN"),
        configuration: configuration, knownRemoteIDs: ["RID-1"]
    ) == .unknownTeamAircraft)
    #expect(TrackerArchiveUploadContract.isTransient(statusCode: 429))
    #expect(TrackerArchiveUploadContract.isTransient(statusCode: 503))
    #expect(!TrackerArchiveUploadContract.shouldMarkReported(statusCode: 503))
    #expect(TrackerArchiveUploadContract.shouldMarkReported(statusCode: 401))
}

@Test func caltopoSignerMatchesAndroidHmacContract() throws {
    let signature = try CaltopoRequestSigner.signature(
        method: "POST",
        path: "/api/v1/map/map123/LiveTrack",
        expiresMilliseconds: 1_700_000_010_000,
        payload: #"{"type":"Feature"}"#,
        credentialSecretBase64: "c2VjcmV0"
    )
    #expect(signature == "4c5G2oSEYaecJyf81tFERXDlcClrG4EZZPYWysPN+4Q=")
}

@Test func caltopoPointRequestMatchesAndroidPositionEndpoint() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        domainAndPort: "caltopo.com",
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0",
        connectKey: "NCSSAR-UAS"
    ))
    let observation = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "RID01",
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        latitude: 39.7392,
        longitude: -104.9903,
        altitudeMeters: 1_600.8,
        headingDegrees: 92,
        speedMetersPerSecond: 10
    )
    let request = try await client.makePointRequest(
        remoteID: "RID01",
        observation: observation,
        cameraMetadata: CaltopoCameraMetadata(
            externalURL: try #require(URL(string: "https://r2c-tracker.com/t/Bz2DZg")),
            thumbnailURL: try #require(URL(
                string: "https://r2c-tracker.com/ncssar/api/v1/video/thumbnail/session-1"
            )),
            azimuthDegrees: 111.46,
            tiltDegrees: -37,
            horizontalFovDegrees: 37.703125,
            verticalFovDegrees: 21.207031
        )
    )
    let url = try #require(request.url)
    #expect(request.httpMethod == "GET")
    let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    #expect(components.path == "/api/v1/position/report/NCSSAR-UAS")
    let values = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    #expect(values["id"] == "RID01")
    #expect(values["lat"] == "39.7392000")
    #expect(values["lng"] == "-104.9903000")
    #expect(values["elevation"] == "1600")
    #expect(values["aircraft:altitude"] == "5252")
    #expect(request.httpBody == nil)
    #expect(abs((Double(values["aircraft:gs"] ?? "") ?? 0) - 19.4384449) < 0.000001)
    #expect(values["aircraft:track"] == "92.0")
    #expect(values["camera:external_url"] == "https://r2c-tracker.com/t/Bz2DZg")
    #expect(values["camera:thumbnail_url"] == "https://r2c-tracker.com/ncssar/api/v1/video/thumbnail/session-1")
    #expect(values["camera:azimuth"] == "111.46")
    #expect(values["camera:tilt"] == "-37.0")
    #expect(values["camera:fov_width"] == "37.703125")
    #expect(values["camera:fov_height"] == "21.207031")
    #expect(values["aircraft"] == nil)
    #expect(values["camera"] == nil)
}

@Test func caltopoPointRequestOmitsAircraftAltitudeWhenUnavailable() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let observation = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "RID01",
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        latitude: 39.7392,
        longitude: -104.9903,
        altitudeMeters: nil
    )

    let request = try await client.makePointRequest(remoteID: "RID01", observation: observation)
    let url = try #require(request.url)
    let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    let values = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })

    #expect(values["elevation"] == "-1000")
    #expect(values["aircraft:altitude"] == nil)
}

@Test func djiCameraOrientationMatchesControllerHeadingAndUsesTiltCalibration() {
    #expect(abs((OperationalClueGeometry.djiControllerCameraAzimuthDegrees(
        seiCameraAzimuthDegrees: 80.8
    ) ?? 0) - 350.8) < 0.000001)
    #expect(OperationalClueGeometry.djiControllerCameraAzimuthDegrees(
        seiCameraAzimuthDegrees: nil
    ) == nil)
    // August 24 M4TD clue: controller reported 288 degrees and -17 degrees.
    #expect(abs((OperationalClueGeometry.djiControllerCameraAzimuthDegrees(
        seiCameraAzimuthDegrees: 16.733
    ) ?? 0) - 286.733) < 0.000001)
    #expect(abs((OperationalClueGeometry.djiCalibratedTiltDegrees(
        rawTiltDegrees: -29.264
    ) ?? 0) - (-17.54)) < 0.01)
    let august24Projection = OperationalClueGeometry.project(
        droneLatitude: 39.154044,
        droneLongitude: -121.131754,
        droneAltitudeMeters: 531.9,
        headingDegrees: OperationalClueGeometry.djiControllerCameraAzimuthDegrees(
            seiCameraAzimuthDegrees: 16.733
        ),
        aglMeters: 22,
        gimbalAngleDegrees: -17.5
    )
    #expect(august24Projection.latitude > 39.154044)
    #expect(august24Projection.longitude < -121.131754)
    #expect(OperationalClueGeometry.djiCalibratedTiltDegrees(rawTiltDegrees: -90) == -90)
    #expect(OperationalClueGeometry.djiCalibratedTiltDegrees(rawTiltDegrees: -14.5625) == 0)
    #expect(abs((OperationalClueGeometry.djiCalibratedTiltDegrees(rawTiltDegrees: -24.5) ?? 0) - (-11.86)) < 0.02)
    #expect(OperationalClueGeometry.djiCalibratedTiltDegrees(rawTiltDegrees: 120) == 90)
}

@Test func cameraFovBoundariesCenterOnSEIAzimuthAndRejectInvalidWidths() throws {
    let east = try #require(OperationalMapGeometry.cameraFovBoundaryBearings(
        cameraAzimuthDegrees: 90,
        horizontalFovDegrees: 40
    ))
    #expect(east.leftDegrees == 70)
    #expect(east.rightDegrees == 110)

    let north = try #require(OperationalMapGeometry.cameraFovBoundaryBearings(
        cameraAzimuthDegrees: 5,
        horizontalFovDegrees: 30
    ))
    #expect(north.leftDegrees == 350)
    #expect(north.rightDegrees == 20)
    #expect(OperationalMapGeometry.cameraFovBoundaryBearings(
        cameraAzimuthDegrees: 90,
        horizontalFovDegrees: 0
    ) == nil)
    #expect(OperationalMapGeometry.cameraFovBoundaryBearings(
        cameraAzimuthDegrees: 90,
        horizontalFovDegrees: 181
    ) == nil)
}

@Test func djiVideoPositionDecodesMatriceTag4CoordinatesAndAltitude() throws {
    let expectedLatitude = 39.153083
    let expectedLongitude = -121.132845
    let expectedAltitude = 574.595
    let altitudeAngle = 360 - expectedAltitude * 1_000 * 360 / 4_294_967_296
    let position = try #require(OperationalClueGeometry.djiVideoPosition(
        tag4AnglesDegrees: [
            2.344, 0, 55.457, 45.917, 0, 359.945,
            expectedLatitude * 2,
            expectedLongitude + 360,
            altitudeAngle
        ]
    ))
    #expect(abs(position.latitude - expectedLatitude) < 0.0000001)
    #expect(abs(position.longitude - expectedLongitude) < 0.0000001)
    #expect(abs(position.altitudeMeters - expectedAltitude) < 0.001)
    #expect(OperationalClueGeometry.djiVideoPosition(
        tag4AnglesDegrees: Array(repeating: 0, count: 9)
    ) == nil)
    let agl = try #require(OperationalClueGeometry.djiVideoAglMeters(
        mslAltitudeMeters: 574.595,
        groundElevationMeters: 510
    ))
    #expect(abs(agl - 64.595) < 0.000001)
    #expect(OperationalClueGeometry.djiVideoAglMeters(
        mslAltitudeMeters: 500,
        groundElevationMeters: 600
    ) == nil)
}

@Test func djiFullWidthPositionValidatesAgainstRIDWithoutChangingCoordinates() throws {
    let referenceLatitude = 39.319435
    let referenceLongitude = -120.658820
    let earthRadius = 6_378_137.0
    let north = 375.216
    let east = -371.216
    let seiLatitude = referenceLatitude + north / earthRadius * 180 / .pi
    let seiLongitude = referenceLongitude + east /
        (earthRadius * cos(referenceLatitude * .pi / 180)) * 180 / .pi
    let validated = try #require(OperationalClueGeometry.djiValidatedHorizontalPosition(
        latitudeDegrees: seiLatitude,
        longitudeDegrees: seiLongitude,
        ridLatitudeDegrees: seiLatitude + 1.5 / earthRadius * 180 / .pi,
        ridLongitudeDegrees: seiLongitude
    ))
    #expect(abs(validated.latitudeDegrees - seiLatitude) < 0.000000001)
    #expect(abs(validated.longitudeDegrees - seiLongitude) < 0.000000001)
    #expect(abs((OperationalClueGeometry.djiValidatedRelativeUpMeters(
        observedRelativeUpMeters: 68,
        ridAltitudeMeters: 1_462,
        takeoffMslMeters: 1_394
    ) ?? 0) - 68) < 0.000000001)

    let ambiguousLatitude = referenceLatitude + 32.768 / earthRadius * 180 / .pi
    let ambiguousLongitude = referenceLongitude + 32.768 /
        (earthRadius * cos(referenceLatitude * .pi / 180)) * 180 / .pi
    #expect(OperationalClueGeometry.djiValidatedHorizontalPosition(
        latitudeDegrees: referenceLatitude,
        longitudeDegrees: referenceLongitude,
        ridLatitudeDegrees: ambiguousLatitude,
        ridLongitudeDegrees: ambiguousLongitude
    ) == nil)
}

@Test func caltopoStopRequestMatchesAndroidLiveTrackDelete() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        domainAndPort: "caltopo.com",
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let request = try await client.makeStopLiveTrackRequest(
        liveTrackID: "track456",
        now: Date(timeIntervalSince1970: 1_700_000_000)
    )
    #expect(request.httpMethod == "DELETE")
    let components = try #require(request.url.flatMap {
        URLComponents(url: $0, resolvingAgainstBaseURL: false)
    })
    #expect(components.path == "/api/v1/map/map123/LiveTrack/track456")
    let values = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    #expect(values["id"] == "credential")
    #expect(values["expires"] == "1700000120000")
    let expectedSignature = try CaltopoRequestSigner.signature(
        method: "DELETE",
        path: components.path,
        expiresMilliseconds: 1_700_000_120_000,
        payload: "",
        credentialSecretBase64: "c2VjcmV0"
    )
    #expect(values["signature"] == expectedSignature)
}

@Test func caltopoDeleteFolderRequestUsesSignedFolderEndpoint() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        domainAndPort: "caltopo.com",
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let request = try await client.makeDeleteFolderRequest(
        folderID: "empty-folder",
        now: Date(timeIntervalSince1970: 1_700_000_000)
    )
    #expect(request.httpMethod == "DELETE")
    let components = try #require(request.url.flatMap {
        URLComponents(url: $0, resolvingAgainstBaseURL: false)
    })
    #expect(components.path == "/api/v1/map/map123/Folder/empty-folder")
    let values = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map {
        ($0.name, $0.value ?? "")
    })
    #expect(values["id"] == "credential")
    #expect(values["expires"] == "1700000120000")
    let expectedSignature = try CaltopoRequestSigner.signature(
        method: "DELETE",
        path: components.path,
        expiresMilliseconds: 1_700_000_120_000,
        payload: "",
        credentialSecretBase64: "c2VjcmV0"
    )
    #expect(values["signature"] == expectedSignature)
}

@Test func caltopoLiveTrackStartsInVisibleDroneTracksFolder() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0",
        connectKey: "NCSSAR-UAS"
    ))
    let request = try await client.makeStartLiveTrackRequest(
        remoteID: "RID01",
        label: "ALPHA1",
        folderID: "drone-folder",
        now: Date(timeIntervalSince1970: 1_700_000_000)
    )
    let fields = decodeFormBody(try #require(request.httpBody))
    let payload = try #require(fields["json"]?.data(using: .utf8))
    let root = try #require(JSONSerialization.jsonObject(with: payload) as? [String: Any])
    let properties = try #require(root["properties"] as? [String: Any])
    #expect(properties["folderId"] as? String == "drone-folder")
    #expect(properties["stroke"] as? String == "#0000ff")
    #expect(properties["deviceId"] as? String == "FLEET:NCSSAR-UAS-RID01")
}

@Test func caltopoLiveTrackUsesDroneWhenConnectKeyIsEmpty() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let request = try await client.makeStartLiveTrackRequest(
        remoteID: "RID01",
        label: "ALPHA1",
        folderID: nil,
        now: Date(timeIntervalSince1970: 1_700_000_000)
    )
    let fields = decodeFormBody(try #require(request.httpBody))
    let payload = try #require(fields["json"]?.data(using: .utf8))
    let root = try #require(JSONSerialization.jsonObject(with: payload) as? [String: Any])
    let properties = try #require(root["properties"] as? [String: Any])
    #expect(properties["deviceId"] as? String == "FLEET:DRONE-RID01")
}

@Test func caltopoArchiveFolderIsCreatedHiddenLikeAndroid() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let request = try await client.makeCreateFolderRequest(
        title: "Drone Tracks 23Jul",
        visible: false,
        labelVisible: false,
        now: Date(timeIntervalSince1970: 1_700_000_000)
    )
    #expect(request.url?.path == "/api/v1/map/map123/Folder")
    let fields = decodeFormBody(try #require(request.httpBody))
    let payload = try #require(fields["json"]?.data(using: .utf8))
    let root = try #require(JSONSerialization.jsonObject(with: payload) as? [String: Any])
    let properties = try #require(root["properties"] as? [String: Any])
    #expect(properties["title"] as? String == "Drone Tracks 23Jul")
    #expect(properties["visible"] as? Bool == false)
    #expect(properties["labelVisible"] as? Bool == false)
}

@Test func caltopoCompletedTrackBecomesMagentaShapeInArchiveFolder() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let observations = [
        RidObservation(
            source: .trackerRelay, aircraftId: "RID01",
            receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
            latitude: 39.1, longitude: -105.2, altitudeMeters: 1_500
        ),
        RidObservation(
            source: .trackerRelay, aircraftId: "RID01",
            receivedAt: Date(timeIntervalSince1970: 1_700_000_001),
            latitude: 39.2, longitude: -105.1, altitudeMeters: 1_510
        ),
    ]
    let request = try await client.makeArchiveLiveTrackRequest(
        liveTrackID: "track456",
        label: "ALPHA1",
        observations: observations,
        folderID: "archive-folder",
        description: "https://r2c-tracker.com/s/QHkyEQ",
        now: Date(timeIntervalSince1970: 1_700_000_010)
    )
    #expect(request.url?.path == "/api/v1/map/map123/Shape/track456")
    let fields = decodeFormBody(try #require(request.httpBody))
    let payload = try #require(fields["json"]?.data(using: .utf8))
    let root = try #require(JSONSerialization.jsonObject(with: payload) as? [String: Any])
    let properties = try #require(root["properties"] as? [String: Any])
    let geometry = try #require(root["geometry"] as? [String: Any])
    #expect(properties["class"] as? String == "Shape")
    #expect(properties["folderId"] as? String == "archive-folder")
    #expect(properties["stroke"] as? String == "#ff00ff")
    #expect(properties["description"] as? String == "https://r2c-tracker.com/s/QHkyEQ")
    #expect((geometry["coordinates"] as? [Any])?.count == 2)
}

@Test func caltopoDeviceMarkerMatchesAndroidFolderAndMetadataContract() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let request = try await client.makeDeviceMarkerRequest(
        CaltopoDeviceMarker(
            id: "28ADC36D-1111-2222-3333-444444444444",
            title: "R2C: Jerry's iPad Pro",
            deviceName: "Jerry's iPad Pro",
            latitude: 43.615,
            longitude: -116.202,
            description: "Tracker link healthy\nPeers: none",
            color: "#2e7d32"
        ),
        folderID: "drone-folder",
        now: Date(timeIntervalSince1970: 1_700_000_000)
    )
    #expect(request.url?.path == "/api/v1/map/map123/Marker/28adc36d-1111-2222-3333-444444444444")
    let fields = decodeFormBody(try #require(request.httpBody))
    let payload = try #require(fields["json"]?.data(using: .utf8))
    let root = try #require(JSONSerialization.jsonObject(with: payload) as? [String: Any])
    let properties = try #require(root["properties"] as? [String: Any])
    let geometry = try #require(root["geometry"] as? [String: Any])
    let coordinates = try #require(geometry["coordinates"] as? [Double])
    #expect(properties["class"] as? String == "Marker")
    #expect(properties["folderId"] as? String == "drone-folder")
    #expect(properties["marker-symbol"] as? String == "radiotower")
    #expect(properties["r2c-name"] as? String == "Jerry's iPad Pro")
    #expect(properties["r2c-guid"] as? String == "28adc36d-1111-2222-3333-444444444444")
    #expect(properties["r2c-last-seen-epoch-ms"] as? Int64 == 1_700_000_000_000)
    #expect(coordinates == [-116.202, 43.615])
}

@Test func trackerTabletShortLinkMatchesAndroidAndServerContract() throws {
    let url = try #require(TrackerTabletLink.shortURL(
        trackerURLPrefix: "https://r2c-tracker.com/ncssar/",
        tabletName: "Kjt A5 Pro"
    ))
    #expect(url.absoluteString == "https://r2c-tracker.com/t/Bz2DZg")
    #expect(TrackerTabletLink.markerDescription(
        trackerURLPrefix: "https://r2c-tracker.com/ncssar/",
        tabletName: "Kjt A5 Pro"
    ) == "https://r2c-tracker.com/t/Bz2DZg")
    #expect(TrackerTabletLink.markerDescription(
        trackerURLPrefix: "",
        tabletName: "Kjt A5 Pro"
    ).isEmpty)
    #expect(TrackerTabletLink.markerDescription(
        trackerURLPrefix: "https://r2c-tracker.com/ncssar/",
        tabletName: "Kjt A5 Pro",
        trackerConnected: false
    ).isEmpty)
    #expect(TrackerTabletLink.thumbnailURL(
        trackerURLPrefix: "https://r2c-tracker.com/ncssar/",
        tabletName: "Kjt A5 Pro",
        streamSessionID: "00000000-0000-0000-0000-000000000001",
        thumbnailRevision: "frame-42"
    )?.absoluteString == "https://r2c-tracker.com/r2c-thumbnail/Bz2DZg/00000000-0000-0000-0000-000000000001.jpg?timestamp=frame-42")
    #expect(TrackerTabletLink.streamShortURL(
        trackerURLPrefix: "https://r2c-tracker.com/ncssar/",
        tabletName: "Kjt A5 Pro",
        videoStream: "NCS1m3"
    )?.absoluteString == "https://r2c-tracker.com/s/QHkyEQ")
    #expect(TrackerTabletLink.recordingShortURL(
        trackerURLPrefix: "https://r2c-tracker.com/ncssar/",
        tabletName: "Kjt A5 Pro",
        sessionID: "00000000-0000-0000-0000-000000000002"
    )?.absoluteString == "https://r2c-tracker.com/v/8fiw1A")
}

@Test func managedVideoRecordingIdentitySurvivesCatalogRebuild() {
    let path = "/app/Documents/RID2Caltopo/CapturedStreams/1sar7/1sar7_12Aug2026_092051.mp4"
    #expect(
        ManagedVideoRecordingIdentity.sessionID(forPath: path) ==
            ManagedVideoRecordingIdentity.sessionID(forPath: path)
    )
}

@Test func managedVideoRecordingAssociationUsesExactTrackInterval() throws {
    let first = ManagedVideoRecordingIdentity.Candidate(
        sessionID: "first",
        designator: "1SAR7",
        startedAt: Date(timeIntervalSince1970: 1),
        endedAt: Date(timeIntervalSince1970: 11)
    )
    let second = ManagedVideoRecordingIdentity.Candidate(
        sessionID: "second",
        designator: "1SAR7",
        startedAt: Date(timeIntervalSince1970: 101),
        endedAt: Date(timeIntervalSince1970: 111)
    )
    let candidates = [second, first]

    #expect(ManagedVideoRecordingIdentity.recording(
        matching: ["1sar7"],
        trackStartedAt: Date(timeIntervalSince1970: 2),
        trackEndedAt: Date(timeIntervalSince1970: 10),
        candidates: candidates
    )?.sessionID == "first")
    #expect(ManagedVideoRecordingIdentity.recording(
        matching: ["1SAR7"],
        trackStartedAt: Date(timeIntervalSince1970: 102),
        trackEndedAt: Date(timeIntervalSince1970: 110),
        candidates: candidates
    )?.sessionID == "second")
}

@Test func managedVideoRecordingAssociationDoesNotReuseStaleRecording() {
    let stale = ManagedVideoRecordingIdentity.Candidate(
        sessionID: "stale",
        designator: "1SAR7",
        startedAt: Date(timeIntervalSince1970: 1),
        endedAt: Date(timeIntervalSince1970: 11)
    )

    #expect(ManagedVideoRecordingIdentity.recording(
        matching: ["1SAR7"],
        trackStartedAt: Date(timeIntervalSince1970: 101),
        trackEndedAt: Date(timeIntervalSince1970: 111),
        candidates: [stale]
    ) == nil)
}

@Test func managedVideoRecordingIdentityParsesMediaMTXStartTime() throws {
    let date = try #require(ManagedVideoRecordingIdentity.recordingStartedAt(
        forPath: "/CapturedStreams/1SAR7/1SAR7_2026-08-14_17-32-47-123456.mp4"
    ))
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = try #require(TimeZone(secondsFromGMT: 0))
    #expect(calendar.component(.year, from: date) == 2026)
    #expect(calendar.component(.month, from: date) == 8)
    #expect(calendar.component(.day, from: date) == 14)
    #expect(calendar.component(.hour, from: date) == 17)
    #expect(calendar.component(.minute, from: date) == 32)
    #expect(calendar.component(.second, from: date) == 47)
}

@Test func managedVideoRecordingIdentityTreatsMediaMTXTimestampAsUTC() throws {
    let date = try #require(ManagedVideoRecordingIdentity.recordingStartedAt(
        forPath: "/CapturedStreams/1SAR7/1SAR7_2026-08-31_16-16-55-835879.mp4"
    ))
    let expected = try #require(ISO8601DateFormatter().date(from: "2026-08-31T16:16:55Z"))
    // DateFormatter may retain only millisecond precision for the six-digit
    // MediaMTX suffix. This assertion is about the UTC wall-clock convention.
    #expect(abs(date.timeIntervalSince(expected)) < 1)
}

@Test func managedVideoRecordingIdentityLocalizesMediaMTXUTCFileName() throws {
    let pacific = try #require(TimeZone(identifier: "America/Los_Angeles"))
    let source = URL(fileURLWithPath:
        "/CapturedStreams/1SAR7/1SAR7_2026-08-27_04-41-11-123456.mp4"
    )
    let localized = try #require(ManagedVideoRecordingIdentity.localizedMediaMTXRecordingURL(
        for: source,
        timeZone: pacific
    ))
    #expect(localized.lastPathComponent == "1SAR7_26Aug2026_214111_PDT.mp4")
}

@Test func managedVideoRecordingIdentityParsesExplicitLocalZone() throws {
    let date = try #require(ManagedVideoRecordingIdentity.recordingStartedAt(
        forPath: "/CapturedStreams/1SAR7/1SAR7_26Aug2026_214111_PDT-0700-123456.mp4"
    ))
    let expected = try #require(ISO8601DateFormatter().date(from: "2026-08-27T04:41:11Z"))
    #expect(abs(date.timeIntervalSince(expected)) < 1)
}

@Test func managedVideoRecordingIdentityParsesConciseLocalZone() throws {
    let date = try #require(ManagedVideoRecordingIdentity.recordingStartedAt(
        forPath: "/CapturedStreams/1SAR7/1SAR7_26Aug2026_214111_PDT.mp4"
    ))
    let expected = try #require(ISO8601DateFormatter().date(from: "2026-08-27T04:41:11Z"))
    #expect(abs(date.timeIntervalSince(expected)) < 1)
}

@Test func managedVideoRecordingIdentityAvoidsConciseNameCollision() {
    let preferred = URL(fileURLWithPath: "/CapturedStreams/1SAR7_31Aug2026_105601_PDT.mp4")
    let existing = Set([preferred.path, "/CapturedStreams/1SAR7_31Aug2026_105601_PDT-2.mp4"])
    let available = ManagedVideoRecordingIdentity.availableRecordingURL(
        preferred: preferred,
        fileExists: { existing.contains($0) }
    )
    #expect(available.lastPathComponent == "1SAR7_31Aug2026_105601_PDT-3.mp4")
}

@Test func managedVideoRecordingIdentityDoesNotRelocalizeArchiveFileName() throws {
    let pacific = try #require(TimeZone(identifier: "America/Los_Angeles"))
    let source = URL(fileURLWithPath:
        "/CapturedStreams/1SAR7/1SAR7_26Aug2026_214111-123456.mp4"
    )
    #expect(ManagedVideoRecordingIdentity.localizedMediaMTXRecordingURL(
        for: source,
        timeZone: pacific
    ) == nil)
}

@Test func managedVideoRecordingIdentityAdvertisesOnlyFinalizedPath() {
    #expect(!ManagedVideoRecordingIdentity.isCompletedRecordingPath(
        "/CapturedStreams/1SAR7/1SAR7_2026-09-02_18-41-08-123456.mp4"
    ))
    #expect(ManagedVideoRecordingIdentity.isCompletedRecordingPath(
        "/CapturedStreams/1SAR7/1SAR7_02Sep2026_114108_PDT.mp4"
    ))
}

@Test func caltopoArchiveDescriptionIncludesOnlyCapturedVideo() throws {
    let description = CaltopoArchiveDescription.build(
        capturedVideoURL: try #require(URL(string: "https://r2c-tracker.com/s/QHkyEQ"))
    )
    #expect(description == "https://r2c-tracker.com/s/QHkyEQ")
    #expect(CaltopoArchiveDescription.build(capturedVideoURL: nil).isEmpty)
}

@Test func caltopoDeviceMarkerDeleteMatchesAndroidDisconnectContract() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        domainAndPort: "caltopo.com",
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let request = try await client.makeDeleteMarkerRequest(
        markerID: " 28ADC36D-1111-2222-3333-444444444444 ",
        now: Date(timeIntervalSince1970: 1_700_000_000)
    )

    #expect(request.httpMethod == "DELETE")
    #expect(
        request.url?.path
            == "/api/v1/map/map123/Marker/28adc36d-1111-2222-3333-444444444444"
    )
    let requestURL = try #require(request.url)
    let components = try #require(
        URLComponents(url: requestURL, resolvingAgainstBaseURL: false)
    )
    let queryItems = components.queryItems ?? []
    #expect(queryItems.contains { $0.name == "id" && $0.value == "credential" })
    #expect(queryItems.contains { $0.name == "expires" && $0.value == "1700000120000" })
    #expect(queryItems.contains { $0.name == "signature" && !($0.value ?? "").isEmpty })
    #expect(request.httpBody == nil)
}

@Test func caltopoPhotoClueMatchesAndroidFourRequestContract() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        domainAndPort: "caltopo.com",
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let clue = CaltopoPhotoClue(
        markerID: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
        mediaID: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!,
        latitude: 39.1,
        longitude: -105.2,
        title: "Clue One",
        description: "Visible subject",
        createdMilliseconds: 1_700_000_000_000,
        jpegData: Data([0xff, 0xd8, 0xff, 0xd9]),
        teamID: "team-1",
        folderID: "drone-folder"
    )
    let requests = try await client.makePhotoClueRequests(
        clue,
        now: Date(timeIntervalSince1970: 1_700_000_001)
    )
    #expect(requests.count == 4)
    #expect(requests.compactMap(\.url?.path) == [
        "/api/v1/map/map123/Marker/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        "/api/v1/media/11111111-2222-3333-4444-555555555555",
        "/api/v1/media/11111111-2222-3333-4444-555555555555/data",
        "/api/v1/map/map123/MapMediaObject",
    ])
    for request in requests {
        #expect(request.httpMethod == "POST")
        let fields = decodeFormBody(try #require(request.httpBody))
        #expect(fields["id"] == "credential")
        #expect(fields["signature"]?.isEmpty == false)
        #expect(fields["json"]?.isEmpty == false)
    }
    let markerFields = decodeFormBody(try #require(requests[0].httpBody))
    let markerJSON = try #require(markerFields["json"]?.data(using: .utf8))
    let marker = try #require(JSONSerialization.jsonObject(with: markerJSON) as? [String: Any])
    let markerProperties = try #require(marker["properties"] as? [String: Any])
    let markerGeometry = try #require(marker["geometry"] as? [String: Any])
    #expect(marker["id"] as? String == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    #expect(marker["type"] as? String == "Feature")
    #expect(markerGeometry["type"] as? String == "Point")
    #expect(markerGeometry["coordinates"] as? [Double] == [-105.2, 39.1])
    #expect(markerProperties["class"] as? String == "Marker")
    #expect(markerProperties["updated"] as? Int64 == 1_700_000_001_000)
    #expect(markerProperties["title"] as? String == "Clue One")
    #expect(markerProperties["marker-symbol"] as? String == "Drone")
    #expect(markerProperties["marker-color"] as? String == "#FF0000")
    #expect(markerProperties["marker-size"] as? String == "1")
    #expect(markerProperties["marker-visibility"] as? String == "visible")
    #expect(markerProperties["folderId"] as? String == "drone-folder")
    #expect(markerProperties["created"] as? Int64 == 1_700_000_000_000)
    #expect(markerProperties["description"] as? String == "Visible subject")

    let mediaFields = decodeFormBody(try #require(requests[1].httpBody))
    let mediaJSON = try #require(mediaFields["json"]?.data(using: .utf8))
    let media = try #require(JSONSerialization.jsonObject(with: mediaJSON) as? [String: Any])
    let mediaProperties = try #require(media["properties"] as? [String: Any])
    #expect(mediaProperties["creator"] as? String == "team-1")

    let dataFields = decodeFormBody(try #require(requests[2].httpBody))
    let dataJSON = try #require(dataFields["json"]?.data(using: .utf8))
    let mediaData = try #require(JSONSerialization.jsonObject(with: dataJSON) as? [String: Any])
    #expect(mediaData["creator"] as? String == "team-1")
    #expect(mediaData["data"] as? String == clue.jpegData.base64EncodedString())

    let linkFields = decodeFormBody(try #require(requests[3].httpBody))
    let linkJSON = try #require(linkFields["json"]?.data(using: .utf8))
    let link = try #require(JSONSerialization.jsonObject(with: linkJSON) as? [String: Any])
    let linkProperties = try #require(link["properties"] as? [String: Any])
    let linkGeometry = try #require(link["geometry"] as? [String: Any])
    #expect(link["type"] as? String == "Feature")
    #expect(linkGeometry["type"] as? String == "Point")
    #expect(linkGeometry["coordinates"] as? [Double] == [-105.2, 39.1])
    #expect(linkProperties["class"] as? String == "MapMediaObject")
    #expect(linkProperties["title"] as? String == "Clue One")
    #expect(linkProperties["description"] as? String == "Visible subject")
    #expect(linkProperties["parentId"] as? String == "Marker:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    #expect(linkProperties["backendMediaId"] as? String == "11111111-2222-3333-4444-555555555555")
    #expect(linkProperties["heading"] is NSNull)
    #expect(linkProperties["marker-symbol"] as? String == "aperture")
    #expect(linkProperties["marker-color"] as? String == "#FF00FF")
    #expect(linkProperties["marker-size"] as? Int == 1)
}

@Test func mediaServerEventDecoderPreservesPublisherIdentity() throws {
    let event = try MediaServerEventDecoder.decode(
        json: #"{"type":"stream_started","path":"scout1","publisherConnId":"10.0.0.1:12000"}"#
    )

    #expect(event == .streamStarted(path: "scout1", publisherConnectionID: "10.0.0.1:12000"))
}

@Test func mediaMtxLogParserUnwrapsJSONAndTracksCleanPublisherStop() {
    var parser = MediaMTXLogEventParser()

    let started = parser.parse(
        line: #"{"timestamp":"2026-07-18T00:00:00Z","level":"INF","message":"MediaMTX v1.16.2"}"#
    )
    #expect(started == .serverStarted(version: "v1.16.2"))

    let published = parser.parse(
        line: "[RTMP] [conn 10.0.0.1:12000] is publishing to path 'scout1'"
    )
    #expect(published == .streamStarted(
        path: "scout1",
        publisherConnectionID: "10.0.0.1:12000"
    ))

    let command = parser.parse(
        line: "[RTMP] [conn 10.0.0.1:12000] RTMP control: received command 'FCUnpublish' (id=1) during publish on path 'scout1'"
    )
    #expect(command == nil)

    let stopped = parser.parse(line: "[RTMP] [conn 10.0.0.1:12000] closed: terminated")
    #expect(stopped == .streamStopped(
        path: "scout1",
        publisherConnectionID: "10.0.0.1:12000"
    ))
}

@Test func mediaMtxLogParserReportsCompletedRecordingAfterClose() {
    var parser = MediaMTXLogEventParser()
    let event = parser.parse(
        line: "[path scout1] [recorder] record file complete path=/records/scout1/file.mp4 durationMs=177400"
    )
    #expect(event == .recordFileCompleted(
        path: "scout1",
        filePath: "/records/scout1/file.mp4",
        durationMilliseconds: 177_400
    ))
}

@Test func mediaMtxLogParserReportsUnexpectedDisconnect() {
    var parser = MediaMTXLogEventParser()
    _ = parser.parse(line: "[RTMP] [conn 192.168.1.10:5000] is publishing to path 'alpha'")

    let event = parser.parse(line: "[RTMP] [conn 192.168.1.10:5000] closed: unexpected EOF")
    #expect(event == .streamError(
        path: "alpha",
        publisherConnectionID: "192.168.1.10:5000",
        detail: "RTMP closed: publisher disconnected unexpectedly"
    ))
}

private func bluetoothServiceData(message: [UInt8], counter: UInt8) -> Data {
    Data([OpenDroneIDParser.applicationCode, counter] + message)
}

@Test func aircraftIdentityBuildsAndroidCompatibleMappedID() {
    let identity = RidAircraftIdentity(
        remoteID: "1581F8HGX12345678901",
        organization: "NCSSAR",
        pilotCallsign: " Eagle 1! ",
        droneDescription: "DJI Matrice 4TD"
    )

    #expect(identity.isComplete)
    #expect(identity.mappedID == "Eagle1DjMtrc4td")
    #expect(RidAircraftIdentity.modelAbbreviation("Autel Evo 2 Dual 640T") == "lEv2Dl640t")
    #expect(RidAircraftIdentity.modelAbbreviation("Potensic Atom LT") == "PtnscAtm2lt")
}

@Test func aircraftIdentityKeepsImportedTeamDroneMappingWithPreferredPilotCallsign() {
    let identity = RidAircraftIdentity(
        remoteID: "1581F8HGX255W00A0H2W",
        organization: "NCSSAR",
        pilotCallsign: "1SAR7",
        droneDescription: "DJI Matrice 4TD",
        mappedIDOverride: "1sar1001DjMtrc4td-01"
    )

    #expect(identity.pilotCallsign == "1SAR7")
    #expect(identity.mappedID == "1sar1001DjMtrc4td-01")
}

@Test func aircraftIdentityGuessesAndroidCompatiblePilotCallsign() {
    #expect(RidAircraftIdentity.guessPilotCallsign(
        mappedID: "1sar7DjMn4Pr",
        model: "DJI Mini 4 Pro",
        remoteID: "1581F6Z9C24BH0036EJL"
    ) == "1sar7")
    #expect(RidAircraftIdentity.guessPilotCallsign(
        mappedID: "1sar1001DjMn4Pr-01",
        model: "DJI Mini 4 Pro",
        remoteID: "1581F6Z9C2527003BZFX"
    ) == "1sar1001-01")
    #expect(RidAircraftIdentity.guessPilotCallsign(
        mappedID: "1668BR40EA00Z5VX",
        model: "",
        remoteID: "1668BR40EA00Z5VX"
    ).isEmpty)
}

@Test func aircraftIdentityFallsBackToRemoteIDWithoutCallsign() {
    let identity = RidAircraftIdentity(
        remoteID: "DEMOALPHA01",
        organization: "NCSSAR",
        pilotCallsign: "!!!",
        droneDescription: "DJI Mini 4 Pro"
    )

    #expect(identity.isComplete)
    #expect(identity.mappedID == "DEMOALPHA01")
}

@Test func orgConfigTokenRoundTripsAndroidWireFormatAndQrURI() throws {
    let original = OrgConfigJoinToken(
        organizationName: "NCSSAR",
        driveFileID: "1AbCdEfGhIjKlMn",
        isPublic: true,
        version: 2
    )
    let token = try OrgConfigTokenCodec.encode(original)

    #expect(token.hasPrefix("R2C2:"))
    #expect(OrgConfigTokenCodec.decode(token) == original)
    #expect(OrgConfigTokenCodec.decode("r2c2://\(token.dropFirst(5))") == original)
    #expect(OrgConfigTokenCodec.decode("R2C1:UGedORwNSuM8Sk4NMR5dSs25A0m0NDWVPNAFSA0IcrmsQv0MDCPiKRszONFNFvEC") == nil)
    #expect(OrgConfigTokenCodec.decode("R2C2:not-valid") == nil)
}

@Test func orgConfigCredentialPayloadEncryptsForAndroidBundleWrapper() throws {
    let plaintext = #"{"type":"ct_credentials","tracker_api_key":"secret"}"#
    let encrypted = OrgConfigTokenCodec.encryptPayload(plaintext)
    #expect(encrypted != plaintext)
    #expect(try OrgConfigTokenCodec.decryptPayload(encrypted) == plaintext)
}

@Test func canonicalCredentialPayloadMatchesAndroidEncryptedBytes() throws {
    let payload = try OrgConfigTokenCodec.canonicalCredentialPayload([
        "type": "ct_credentials",
        "file_version": "1.0",
        "team_id": "team-7",
        "credential_id": "credential-9",
        "credential_secret": "c2VjcmV0",
        "domain_and_port": "caltopo.com",
        "track_folder": "Drone Tracks",
        "empty_optional_value": "",
    ])

    #expect(payload == #"{"credential_id":"credential-9","credential_secret":"c2VjcmV0","domain_and_port":"caltopo.com","file_version":"1.0","team_id":"team-7","track_folder":"Drone Tracks","type":"ct_credentials"}"#)
    #expect(OrgConfigTokenCodec.encryptPayload(payload) == "KWsnQCYFCRobGQ49DTstZghhAh4RCxUBJTszJWkLYU1OFx0VCzQ8JiAlXhwSCRcdFRtzaHAqdmQpAgEiX1JDczY9JCVbLT4NGgsvHz4gJmt+ECAAAAAAAAB/MT0kZh5hBwUYCi8ZNCAhICtcYVtORUFATX1wJiwlXxwICFZVUhs0Mz9kcxBvQxgGDhMEDjQ9JSBXMUNWVisCAD83ch02UyAKH1ZDUhsoIjdrfhAgFTMXHRULNDwmICVeMEMR")
}

@Test func orgConfigBundleDecryptsCredentialsAndReadsRidmap() throws {
    let credentialObject: [String: Any] = [
        "type": "ct_credentials",
        "org_name": "NCSSAR",
        "team_id": "team-1",
        "credential_id": "credential-1",
        "credential_secret": "c2VjcmV0",
        "domain_and_port": "caltopo.example",
        "connect_key": "NCSSAR-UAS",
        "incident": "Search 42",
        "op_period": "2",
        "tracker_enrollment_url": "https://r2c-tracker.com/ncssar/enroll?token=campaign-token",
        "use_peers": true,
        "predictive_head_enabled": false,
        "proximity_alert_spacing_feet": 40,
    ]
    let credentialData = try JSONSerialization.data(withJSONObject: credentialObject, options: [.sortedKeys])
    let credentialJSON = String(decoding: credentialData, as: UTF8.self)
    let encrypted = try androidCompatibleEncryptPayload(credentialJSON)
    let mutualAidPlaintext: [String: Any] = [
        "type": "ct_mutual_aid_credentials",
        "team_id": "ma-team",
        "credential_id": "ma-credential",
        "credential_secret": "ma-secret",
        "source_label": "Mutual Org",
        "target_folder_hint": "MAI",
        "connect_key": "SHARED-UAS",
    ]
    let mutualAidJSON = String(decoding: try JSONSerialization.data(withJSONObject: mutualAidPlaintext), as: UTF8.self)
    let root: [String: Any] = [
        "format": "rid2caltopo_org_config",
        "version": 2,
        "org_name": "NCSSAR",
        "configs": [
            [
                "type": "ct_ridmap",
                "map": [[
                    "remoteId": "1581F8HGX123",
                    "mappedId": "Eagle1DjMtrc4td",
                    "org": "NCSSAR",
                    "model": "DJI Matrice 4TD",
                    "owner": "Eagle1",
                ]],
            ],
            ["type": "ct_credentials_enc", "enc": encrypted],
            ["type": "ct_credentials_enc", "enc": try androidCompatibleEncryptPayload(mutualAidJSON)],
        ],
    ]
    let bundle = try OrgConfigTokenCodec.parseBundle(JSONSerialization.data(withJSONObject: root))

    #expect(bundle.organizationName == "NCSSAR")
    #expect(bundle.mappings.first?.mappedID == "Eagle1DjMtrc4td")
    #expect(bundle.credentials?.credentialSecret == "c2VjcmV0")
    #expect(bundle.trackerEnrollmentURL == "https://r2c-tracker.com/ncssar/enroll?token=campaign-token")
    #expect(bundle.credentials?.usePeers == true)
    #expect(bundle.credentials?.predictiveHeadEnabled == false)
    #expect(bundle.credentials?.connectKey == "NCSSAR-UAS")
    #expect(bundle.faaConfig == nil)
    #expect(bundle.mutualAidTemplate?.credentialID == "ma-credential")
    #expect(bundle.mutualAidTemplate?.connectKey == "SHARED-UAS")
}

@Test func androidConfigTokenCodecRecognizesAllQrFamilies() throws {
    let org = try sharedConfigToken(prefix: "R2C2:", displayKey: "o", display: "NCSSAR", fileID: "org-file", version: 2)
    let faa = try sharedConfigToken(prefix: "R2CFAA1:", displayKey: "l", display: "FAA Shared", fileID: "faa-file")
    let mutualAid = try sharedConfigToken(prefix: "R2CMA1:", displayKey: "o", display: "Mutual Org", fileID: "ma-file")

    #expect(AndroidConfigTokenCodec.decode(org)?.kind == .organization)
    #expect(AndroidConfigTokenCodec.decode("r2cfaa1://\(faa.dropFirst(8))")?.kind == .faa)
    #expect(AndroidConfigTokenCodec.decode("r2cma1://\(mutualAid.dropFirst(7))")?.kind == .mutualAid)
}

@Test func androidConfigCodecParsesFaaAndMutualAidBundles() throws {
    let faaPlaintext: [String: Any] = [
        "source_label": "Shared FAA",
        "notam_api_base_url": "https://api.example",
        "notam_token_url": "https://token.example",
        "notam_client_id": "client",
        "notam_client_secret": "secret",
        "notam_scope": "scope",
    ]
    let faaJSON = String(decoding: try JSONSerialization.data(withJSONObject: faaPlaintext), as: UTF8.self)
    let faaWrapper = ["type": "ct_faa_credentials_enc", "enc": try androidCompatibleEncryptPayload(faaJSON)]
    let faa = try AndroidConfigTokenCodec.parseFaaBundle(JSONSerialization.data(withJSONObject: faaWrapper))
    #expect(faa.clientID == "client")
    #expect(faa.sourceLabel == "Shared FAA")

    let profile: [String: Any] = [
        "profile_id": "profile-1",
        "display_name": "Mutual Aid 1",
        "team_id": "team",
        "credential_id": "credential",
        "credential_secret": "secret",
        "domain_and_port": "caltopo.com",
        "incident": "Search 42",
        "op_period": "2",
        "target_map_id": "map-42",
        "expires_at_epoch_ms": 1_900_000_000_000 as Int64,
        "quiet_remove_on_expiry": false,
    ]
    let profileJSON = String(decoding: try JSONSerialization.data(withJSONObject: profile), as: UTF8.self)
    let maRoot: [String: Any] = [
        "format": "rid2caltopo_mutual_aid_profile",
        "version": 1,
        "profile": ["type": "caltopo_profile_enc", "enc": try androidCompatibleEncryptPayload(profileJSON)],
    ]
    let mutualAid = try AndroidConfigTokenCodec.parseMutualAidBundle(JSONSerialization.data(withJSONObject: maRoot))
    #expect(mutualAid.profileID == "profile-1")
    #expect(mutualAid.targetMapID == "map-42")
    #expect(mutualAid.quietRemoveOnExpiry == false)
}

@Test func trackerCoordinationWireMatchesAndroidContract() throws {
    let scopedPrefix = TrackerCoordinationEndpoint.organizationScopedPrefix(
        from: "https://r2c-tracker.com",
        organization: " NCSSAR "
    )
    #expect(scopedPrefix == "https://r2c-tracker.com/ncssar")
    #expect(TrackerCoordinationEndpoint.organizationScopedPrefix(
        from: "https://r2c-tracker.com/other-team",
        organization: "NCSSAR"
    ) == "https://r2c-tracker.com/other-team")

    let endpoint = try TrackerCoordinationEndpoint.webSocketURL(from: "https://tracker.example/r2c/")
    #expect(endpoint.absoluteString == "wss://tracker.example/r2c/ws/r2c")

    let client = TrackerCoordinationClient(
        mapID: "MAP1",
        zoneID: "zone-alpha",
        name: "Alpha",
        deviceModel: "iPad Pro",
        platform: "ios",
        appVersion: "0.1(1)",
        appVersionCode: 1
    )
    let hello = try jsonObject(TrackerCoordinationWire.hello(
        client: client,
        position: TrackerCoordinationPosition(latitude: 39.1, longitude: -104.2, caltopoRTTMilliseconds: 750)
    ))
    #expect(hello["type"] as? String == "hello")
    #expect(hello["mapId"] as? String == "MAP1")
    #expect(hello["incidentId"] as? String == "MAP1")
    #expect(hello["zoneId"] as? String == "zone-alpha")
    #expect(hello["guid"] as? String == "zone-alpha")
    #expect(hello["deviceModel"] as? String == "iPad Pro")
    #expect(hello["appPlatform"] as? String == "ios")
    #expect((hello["appVersionCode"] as? NSNumber)?.intValue == 1)
    #expect(
        (hello["trackerFunctionalityRelease"] as? NSNumber)?.intValue
            == TrackerCoordinationClient.trackerFunctionalityRelease
    )

    let identity = TrackerCoordinationIdentity(
        remoteID: "DRONE1",
        mappedID: "1sar7DjMn4Pr",
        organization: "NCSSAR",
        model: "DJI Mini 4 Pro",
        ownerName: "1sar7"
    )
    let first = try jsonObject(TrackerCoordinationWire.firstSighting(
        client: client,
        sighting: TrackerCoordinationSighting(
            identity: identity,
            droneTimestampMilliseconds: 1_710_000_001_000,
            latitude: 39.2,
            longitude: -104.3,
            altitudeMeters: 1_620,
            distanceFromZoneMeters: 50,
            headingDegrees: 92,
            groundSpeedKnots: 20
        )
    ))
    #expect(first["type"] as? String == "first_sighting")
    #expect(first["remoteId"] as? String == "DRONE1")
    #expect(first["trackLabel"] as? String == "1sar7DjMn4Pr")
    #expect(first["ownerName"] as? String == "1sar7")
    let telemetry = first["telemetry"] as? [String: Any]
    #expect((telemetry?["headingDeg"] as? NSNumber)?.doubleValue == 92)
}

@Test func currentFlightConfirmationPromptsAgainAfterDefinitiveFlightEnd() {
    var lifecycle = CurrentFlightConfirmationLifecycle()

    let first = lifecycle.reconcile(
        orderedRemoteIDs: ["DRONE1"],
        confirmedRemoteIDs: [],
        ignoredRemoteIDs: []
    )
    #expect(first.candidateRemoteID == "DRONE1")
    #expect(first.endedRemoteIDs.isEmpty)

    let saved = lifecycle.reconcile(
        orderedRemoteIDs: ["DRONE1"],
        confirmedRemoteIDs: ["DRONE1"],
        ignoredRemoteIDs: []
    )
    #expect(saved.candidateRemoteID == nil)

    let ended = lifecycle.reconcile(
        orderedRemoteIDs: [],
        confirmedRemoteIDs: ["DRONE1"],
        ignoredRemoteIDs: []
    )
    #expect(ended.endedRemoteIDs == ["DRONE1"])

    let nextFlight = lifecycle.reconcile(
        orderedRemoteIDs: ["DRONE1"],
        confirmedRemoteIDs: [],
        ignoredRemoteIDs: []
    )
    #expect(nextFlight.candidateRemoteID == "DRONE1")
}

@Test func currentFlightConfirmationDoesNotRepeatWithinContinuousFlight() {
    var lifecycle = CurrentFlightConfirmationLifecycle()

    #expect(lifecycle.reconcile(
        orderedRemoteIDs: ["DRONE1"],
        confirmedRemoteIDs: [],
        ignoredRemoteIDs: []
    ).candidateRemoteID == "DRONE1")
    #expect(lifecycle.reconcile(
        orderedRemoteIDs: ["DRONE1"],
        confirmedRemoteIDs: [],
        ignoredRemoteIDs: []
    ).candidateRemoteID == nil)
}

@Test func trackerOwnershipRequiresLocalLeaseAndLocalSave() throws {
    var state = TrackerCoordinationProtocolState(localZoneID: "zone-alpha")
    state.transportOpened(helloSentAtMilliseconds: 1_000)

    let ownerEvent = try state.handleIncoming(
        Data(#"{"type":"owner_assigned","remoteId":"DRONE1","ownerGuid":"zone-alpha","leaseSeq":4}"#.utf8),
        receivedAtMilliseconds: 1_100
    )
    #expect(ownerEvent == [
        .ownershipChanged(remoteID: "DRONE1", ownerZoneID: "zone-alpha", localOwner: true, alertEligible: false),
    ])
    #expect(state.isLocalOwner(remoteID: "DRONE1"))
    #expect(!state.isLocalAlertEligible(remoteID: "DRONE1"))

    #expect(state.confirmLocally(remoteID: "DRONE1") ==
        .ownershipChanged(remoteID: "DRONE1", ownerZoneID: "zone-alpha", localOwner: true, alertEligible: true))
    #expect(state.isLocalAlertEligible(remoteID: "DRONE1"))

    let peerConfirmation = try state.handleIncoming(
        Data(#"{"type":"drone_confirmed","remoteId":"DRONE1","confirmedByGuid":"zone-bravo","mappedId":"Bravo1","org":"NCSSAR","model":"Mavic 3","ownerName":"Bravo"}"#.utf8),
        receivedAtMilliseconds: 1_200
    )
    #expect(peerConfirmation.contains(
        .ownershipChanged(remoteID: "DRONE1", ownerZoneID: "zone-bravo", localOwner: false, alertEligible: false)
    ))
    #expect(!state.isLocalOwner(remoteID: "DRONE1"))
    #expect(!state.isLocalAlertEligible(remoteID: "DRONE1"))
}

@Test func trackerCoordinationRejectsStaleLeasesAndSelfRelay() throws {
    var state = TrackerCoordinationProtocolState(localZoneID: "zone-alpha")
    _ = try state.handleIncoming(
        Data(#"{"type":"owner_assigned","remoteId":"DRONE1","ownerGuid":"zone-alpha","leaseSeq":7}"#.utf8),
        receivedAtMilliseconds: 1_000
    )
    let stale = try state.handleIncoming(
        Data(#"{"type":"owner_assigned","remoteId":"DRONE1","ownerGuid":"zone-bravo","leaseSeq":6}"#.utf8),
        receivedAtMilliseconds: 1_100
    )
    #expect(stale == [.ignored(messageType: "stale owner_assigned")])
    #expect(state.isLocalOwner(remoteID: "DRONE1"))

    let selfRelay = try state.handleIncoming(
        Data(#"{"type":"relay_sighting","remoteId":"DRONE1","fromZoneId":"zone-alpha","lat":39.2,"lng":-104.3,"droneTs":1234}"#.utf8),
        receivedAtMilliseconds: 1_200
    )
    #expect(selfRelay == [.ignored(messageType: "relay_sighting")])

    let peerRelay = try state.handleIncoming(
        Data(#"{"type":"relay_sighting","remoteId":"DRONE1","fromZoneId":"zone-bravo","lat":39.2,"lng":-104.3,"altM":1620,"droneTs":1234}"#.utf8),
        receivedAtMilliseconds: 1_300
    )
    guard case let .relaySighting(relay) = peerRelay.first else {
        Issue.record("Expected peer relay sighting")
        return
    }
    #expect(relay.sourceZoneID == "zone-bravo")
    #expect(relay.altitudeMeters == 1_620)
}

@Test func trackerReconnectClearsStaleHeartbeatWatchdogState() throws {
    var state = TrackerCoordinationProtocolState(localZoneID: "zone-alpha")
    state.transportOpened(helloSentAtMilliseconds: 1_000)
    let firstSequence = state.nextHeartbeatSequence(sentAtMilliseconds: 2_000)
    #expect(firstSequence == 1)
    #expect(state.requiresReconnectForMissingAcknowledgement(nowMilliseconds: 12_001) == "missed hello_ack")

    state.transportOpened(helloSentAtMilliseconds: 20_000)
    let helloAck = try state.handleIncoming(
        Data(#"{"type":"hello_ack"}"#.utf8),
        receivedAtMilliseconds: 20_100
    )
    #expect(helloAck == [.helloAcknowledged(recommendedAppVersionCode: nil, updateURL: nil)])
    #expect(state.requiresReconnectForMissingAcknowledgement(nowMilliseconds: 20_200) == nil)

    let secondSequence = state.nextHeartbeatSequence(sentAtMilliseconds: 21_000)
    #expect(secondSequence == 1)
    let heartbeatAck = try state.handleIncoming(
        Data(#"{"type":"heartbeat_ack","clientSeq":1,"ownerLeaseExpireTs":30000}"#.utf8),
        receivedAtMilliseconds: 21_050
    )
    #expect(heartbeatAck == [.heartbeatAcknowledged(sequence: 1, ownerLeaseExpiresAtMilliseconds: 30_000)])
    #expect(state.requiresReconnectForMissingAcknowledgement(nowMilliseconds: 40_000) == nil)
}

private func jsonObject(_ data: Data) throws -> [String: Any] {
    try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
}

private func decodeFormBody(_ data: Data) -> [String: String] {
    Dictionary(uniqueKeysWithValues: String(decoding: data, as: UTF8.self)
        .split(separator: "&")
        .compactMap { field -> (String, String)? in
            let parts = field.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2 else { return nil }
            let key = String(parts[0]).removingPercentEncoding ?? String(parts[0])
            let value = String(parts[1]).removingPercentEncoding ?? String(parts[1])
            return (key, value)
        })
}

private func sharedConfigToken(
    prefix: String,
    displayKey: String,
    display: String,
    fileID: String,
    version: Int = 1
) throws -> String {
    let object: [String: Any] = [displayKey: display, "f": fileID, "p": 1, "v": version]
    let json = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    let key = Array("RID2CaltopoQR".utf8)
    let encrypted = Array(json).enumerated().map { index, value in value ^ key[index % key.count] }
    let standard = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
    let custom = Array("r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/=")
    let remapped = Data(encrypted).base64EncodedString().map { character -> Character in
        guard let index = standard.firstIndex(of: character) else { return character }
        return custom[index]
    }
    return prefix + String(remapped)
}

private func androidCompatibleEncryptPayload(_ plaintext: String) throws -> String {
    let key = Array("RID2CaltopoQR".utf8)
    let encrypted = Array(plaintext.utf8).enumerated().map { index, value in
        value ^ key[index % key.count]
    }
    return Data(encrypted).base64EncodedString()
}

private func makeFloatGeoTiff() -> Data {
    let entryCount = 14
    let scaleOffset = 8 + 2 + entryCount * 12 + 4
    let tieOffset = scaleOffset + 3 * 8
    let pixelOffset = tieOffset + 6 * 8
    var bytes = [UInt8](repeating: 0, count: pixelOffset + 4 * 4)
    func put16(_ value: UInt16, _ offset: Int) {
        bytes[offset] = UInt8(truncatingIfNeeded: value)
        bytes[offset + 1] = UInt8(truncatingIfNeeded: value >> 8)
    }
    func put32(_ value: UInt32, _ offset: Int) {
        for index in 0 ..< 4 { bytes[offset + index] = UInt8(truncatingIfNeeded: value >> UInt32(index * 8)) }
    }
    func put64(_ value: UInt64, _ offset: Int) {
        for index in 0 ..< 8 { bytes[offset + index] = UInt8(truncatingIfNeeded: value >> UInt64(index * 8)) }
    }
    func putDouble(_ value: Double, _ offset: Int) { put64(value.bitPattern, offset) }
    func entry(_ index: Int, _ tag: UInt16, _ type: UInt16, _ count: UInt32, _ value: UInt32) {
        let offset = 10 + index * 12
        put16(tag, offset); put16(type, offset + 2); put32(count, offset + 4); put32(value, offset + 8)
    }
    bytes[0] = 0x49; bytes[1] = 0x49; put16(42, 2); put32(8, 4); put16(UInt16(entryCount), 8)
    entry(0, 256, 4, 1, 2); entry(1, 257, 4, 1, 2); entry(2, 258, 3, 1, 32)
    entry(3, 259, 3, 1, 1); entry(4, 262, 3, 1, 1); entry(5, 273, 4, 1, UInt32(pixelOffset))
    entry(6, 277, 3, 1, 1); entry(7, 278, 4, 1, 2); entry(8, 279, 4, 1, 16)
    entry(9, 284, 3, 1, 1); entry(10, 317, 3, 1, 1); entry(11, 339, 3, 1, 3)
    entry(12, 33550, 12, 3, UInt32(scaleOffset)); entry(13, 33922, 12, 6, UInt32(tieOffset))
    [1.0, 1.0, 0.0].enumerated().forEach { putDouble($0.element, scaleOffset + $0.offset * 8) }
    [0.0, 0.0, 0.0, -105.0, 40.0, 0.0].enumerated().forEach { putDouble($0.element, tieOffset + $0.offset * 8) }
    [Float(100), 200, 300, 400].enumerated().forEach { put32($0.element.bitPattern, pixelOffset + $0.offset * 4) }
    return Data(bytes)
}

private func trackObservation(
    id: String,
    at date: Date,
    latitude: Double,
    longitude: Double,
    source: RidObservation.Source = .bluetoothLegacy,
    signalStrengthDbm: Int = -55,
    headingDegrees: Double? = nil
) -> RidObservation {
    RidObservation(
        source: source,
        aircraftId: id,
        receivedAt: date,
        latitude: latitude,
        longitude: longitude,
        altitudeMeters: 1_600,
        headingDegrees: headingDegrees,
        signalStrengthDbm: signalStrengthDbm
    )
}

private func basicIDMessage(_ uasID: String) -> [UInt8] {
    var message = [UInt8](repeating: 0, count: OpenDroneIDParser.messageSize)
    message[0] = 0x02
    message[1] = 0x12
    for (index, byte) in uasID.utf8.prefix(20).enumerated() {
        message[index + 2] = byte
    }
    return message
}

private func locationMessage(
    direction: UInt8 = 90,
    eastWest: Bool = false,
    horizontalAccuracyCode: UInt8 = 10,
    horizontalSpeed: UInt8 = 40,
    verticalSpeed: Int8 = -4
) -> [UInt8] {
    var message = [UInt8](repeating: 0, count: OpenDroneIDParser.messageSize)
    message[0] = 0x12
    message[1] = eastWest ? 0x02 : 0x00
    message[2] = direction
    message[3] = horizontalSpeed
    message[4] = UInt8(bitPattern: verticalSpeed)
    writeInt32(397_392_000, into: &message, at: 5)
    writeInt32(-1_049_903_000, into: &message, at: 9)
    writeUInt16(2_200, into: &message, at: 13)
    writeUInt16(2_400, into: &message, at: 15)
    writeUInt16(2_100, into: &message, at: 17)
    message[19] = horizontalAccuracyCode & 0x0F
    writeUInt16(123, into: &message, at: 21)
    return message
}

private func selfIDMessage(_ description: String) -> [UInt8] {
    var message = [UInt8](repeating: 0, count: OpenDroneIDParser.messageSize)
    message[0] = 0x32
    message[1] = 0
    for (index, byte) in description.utf8.prefix(23).enumerated() {
        message[index + 2] = byte
    }
    return message
}

private func messagePack(_ messages: [[UInt8]]) -> [UInt8] {
    [0xF2, UInt8(OpenDroneIDParser.messageSize), UInt8(messages.count)]
        + messages.flatMap { $0 }
}

private func writeUInt16(_ value: UInt16, into bytes: inout [UInt8], at offset: Int) {
    bytes[offset] = UInt8(truncatingIfNeeded: value)
    bytes[offset + 1] = UInt8(truncatingIfNeeded: value >> 8)
}

private func writeInt32(_ value: Int32, into bytes: inout [UInt8], at offset: Int) {
    let bits = UInt32(bitPattern: value)
    for index in 0 ..< 4 {
        bytes[offset + index] = UInt8(truncatingIfNeeded: bits >> UInt32(index * 8))
    }
}
@Test func liveVideoLagEstimatorTracksDelayFromBestObservedSourceOffset() {
    var estimator = LiveVideoLagEstimator()
    #expect(estimator.observe(
        sourceTimestampMicroseconds: 9_500_000,
        observedAtMilliseconds: 10_000
    ) == 0)
    #expect(estimator.observe(
        sourceTimestampMicroseconds: 10_500_000,
        observedAtMilliseconds: 12_000
    ) == 1_000)
}

@Test func liveVideoLagEstimatorResetsAfterSourceClockJumpsBackward() {
    var estimator = LiveVideoLagEstimator()
    _ = estimator.observe(sourceTimestampMicroseconds: 10_500_000, observedAtMilliseconds: 11_000)
    #expect(estimator.observe(
        sourceTimestampMicroseconds: 200_000,
        observedAtMilliseconds: 20_000
    ) == 0)
}

@Test func liveVideoLagLabelsMatchAndroid() {
    #expect(LiveVideoLagEstimator.quantize(milliseconds: 649) == 600)
    #expect(LiveVideoLagEstimator.quantize(milliseconds: 650) == 700)
    #expect(LiveVideoLagEstimator.quantize(milliseconds: 2_350) == 2_250)
    #expect(LiveVideoLagEstimator.label(milliseconds: nil) == "Starting")
    #expect(LiveVideoLagEstimator.label(milliseconds: 700) == "lag:700ms")
    #expect(LiveVideoLagEstimator.label(milliseconds: 2_250) == "lag:2.2s")
    #expect(LiveVideoLagEstimator.label(milliseconds: 5_000) == "lag:5.0s")
}

@Test func liveVideoSessionLagIncludesDelayBeforeFirstDecodedFrame() {
    var estimator = LiveVideoSessionLagEstimator()
    estimator.publisherStarted(atMilliseconds: 10_000)
    #expect(estimator.observe(
        sourceTimestampMicroseconds: 500_000,
        observedAtMilliseconds: 13_000
    ) == 2_500)
    #expect(estimator.observe(
        sourceTimestampMicroseconds: 1_500_000,
        observedAtMilliseconds: 14_100
    ) == 2_600)
}

@Test func liveVideoSessionLagNormalizesAnUnrelatedSourceClock() {
    var estimator = LiveVideoSessionLagEstimator()
    estimator.publisherStarted(atMilliseconds: 10_000)
    #expect(estimator.observe(
        sourceTimestampMicroseconds: 500_000_000,
        observedAtMilliseconds: 13_000
    ) == 3_000)
    #expect(estimator.observe(
        sourceTimestampMicroseconds: 501_000_000,
        observedAtMilliseconds: 14_100
    ) == 3_100)
}

@Test func operationalVideoViewportMatchesZoomedClueGeometry() {
    let viewport = OperationalVideoViewport(
        scale: 2,
        normalizedPanX: 0.125,
        normalizedPanY: -0.25
    )
    #expect(viewport.needsTransform)
    #expect(viewport.translationX(width: 800) == -300)
    #expect(viewport.translationY(height: 400) == -100)
}

@Test func operationalVideoViewportClampsUnsafeGestureInputs() {
    let viewport = OperationalVideoViewport(
        scale: .infinity,
        normalizedPanX: 9,
        normalizedPanY: -.infinity
    )
    #expect(viewport.scale == 1)
    #expect(viewport.normalizedPanX == 1.5)
    #expect(viewport.normalizedPanY == 0)
    #expect(viewport.needsTransform)
}

@Test func applicationIdleTimeoutStartsAtLaunchAndCanBeDisabled() {
    let startedAt = Date(timeIntervalSince1970: 1_000)
    #expect(ApplicationIdleTimeoutPolicy.deadline(
        appStartedAt: startedAt,
        lastRIDMessageAt: nil,
        maximumIdleMinutes: 2
    ) == Date(timeIntervalSince1970: 1_120))
    #expect(ApplicationIdleTimeoutPolicy.deadline(
        appStartedAt: startedAt,
        lastRIDMessageAt: nil,
        maximumIdleMinutes: 0
    ) == nil)
}

@Test func applicationLaunchDisclaimerUsesApprovedSafetyLanguage() {
    var hash: UInt64 = 14_695_981_039_346_656_037
    for byte in ApplicationLaunchDisclaimer.text.utf8 {
        hash = (hash ^ UInt64(byte)) &* 1_099_511_628_211
    }

    #expect(hash == 0xa459680b79193637)
    #expect(ApplicationLaunchDisclaimer.text.contains("accept full responsibility"))
    #expect(ApplicationLaunchDisclaimer.text.contains("hold harmless UAS4SAR LLC"))
    #expect(ApplicationLaunchDisclaimer.text.contains("California Civil Code section 1542"))
    #expect(ApplicationLaunchDisclaimer.text.contains("expressly waive all rights and benefits"))
    #expect(ApplicationLaunchDisclaimer.text.contains("unknown or unsuspected"))
}

@Test func applicationIdleTimeoutUsesRemainingTimeSinceLatestRIDMessage() {
    let startedAt = Date(timeIntervalSince1970: 1_000)
    let lastUpdateAt = Date(timeIntervalSince1970: 1_075)
    #expect(ApplicationIdleTimeoutPolicy.deadline(
        appStartedAt: startedAt,
        lastRIDMessageAt: lastUpdateAt,
        maximumIdleMinutes: 2
    ) == Date(timeIntervalSince1970: 1_195))
    #expect(!ApplicationIdleTimeoutPolicy.isExpired(
        appStartedAt: startedAt,
        lastRIDMessageAt: lastUpdateAt,
        maximumIdleMinutes: 2,
        now: Date(timeIntervalSince1970: 1_194)
    ))
    #expect(ApplicationIdleTimeoutPolicy.isExpired(
        appStartedAt: startedAt,
        lastRIDMessageAt: lastUpdateAt,
        maximumIdleMinutes: 2,
        now: Date(timeIntervalSince1970: 1_195)
    ))
    #expect(ApplicationIdleTimeoutPolicy.remainingDelay(
        appStartedAt: startedAt,
        lastRIDMessageAt: lastUpdateAt,
        maximumIdleMinutes: 2,
        now: Date(timeIntervalSince1970: 1_135)
    ) == 60)
}

@Test func applicationShutdownCleansOnceAndAllowsDismissalRetry() {
    var state = ApplicationShutdownState()

    let automaticQuit = state.request(dismissWindow: true)
    #expect(automaticQuit.shouldStartCleanup)
    #expect(!automaticQuit.shouldDismissWindow)
    #expect(state.phase == .cleaning)
    let shouldDismissAfterCleanup = state.cleanupCompleted()
    #expect(shouldDismissAfterCleanup)
    #expect(state.phase == .cleaned)

    let manualRetry = state.request(dismissWindow: true)
    #expect(!manualRetry.shouldStartCleanup)
    #expect(manualRetry.shouldDismissWindow)
}

@Test func applicationShutdownQueuesDismissalRequestedDuringCleanup() {
    var state = ApplicationShutdownState()

    let backgroundCleanup = state.request(dismissWindow: false)
    #expect(backgroundCleanup.shouldStartCleanup)
    #expect(!backgroundCleanup.shouldDismissWindow)

    let operatorQuit = state.request(dismissWindow: true)
    #expect(!operatorQuit.shouldStartCleanup)
    #expect(!operatorQuit.shouldDismissWindow)
    let shouldDismissAfterCleanup = state.cleanupCompleted()
    #expect(shouldDismissAfterCleanup)
}

@Test func applicationShutdownResetStartsANewOperationalSession() {
    var state = ApplicationShutdownState()
    _ = state.request(dismissWindow: true)
    _ = state.cleanupCompleted()
    state.reset()

    #expect(state.phase == .running)
    #expect(!state.isShutdownRequested)
    #expect(state.request(dismissWindow: false).shouldStartCleanup)
}
@Test func managedVideoApprovalWaitsUntilPreflightIsReady() {
    #expect(!ManagedVideoQualityPolicy.shouldPresentApproval(
        routeKind: nil,
        failure: nil
    ))
    #expect(ManagedVideoQualityPolicy.shouldPresentApproval(
        routeKind: "routed",
        failure: nil
    ))
}

@Test func managedVideoPreflightAcceptsOperatorAndRemoteControlledRequests() {
    #expect(ManagedVideoPreflightRequestPolicy.shouldAcceptOffer(
        requestID: "operator-request",
        pendingOperatorRequestID: "operator-request",
        remoteControlledRequestIDs: []
    ))
    #expect(ManagedVideoPreflightRequestPolicy.shouldAcceptOffer(
        requestID: "remote-request",
        pendingOperatorRequestID: nil,
        remoteControlledRequestIDs: ["remote-request"]
    ))
    #expect(!ManagedVideoPreflightRequestPolicy.shouldAcceptOffer(
        requestID: "unknown-request",
        pendingOperatorRequestID: "operator-request",
        remoteControlledRequestIDs: ["remote-request"]
    ))
}

@Test func managedVideoIncidentScopeMigrationRecoversTodaysMapRecordings() {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: -7 * 60 * 60)!
    let now = Date(timeIntervalSince1970: 1_786_578_340)
    let resolution = ManagedVideoIncidentScopePolicy.resolve(
        scopeKey: "map:4J0LF02",
        startsByScope: [
            "incident:training": 1_786_518_000,
            "map:4J0LF02": now.timeIntervalSince1970,
        ],
        migrationCompleted: false,
        now: now,
        calendar: calendar
    )

    #expect(resolution.startedAt == calendar.startOfDay(for: now))
    #expect(
        resolution.startsByScope["map:4J0LF02"] ==
            resolution.startedAt.timeIntervalSince1970
    )
    #expect(resolution.migrationCompleted)
}

@Test func managedVideoIncidentScopeKeepsLaterMapChangesBounded() {
    let now = Date(timeIntervalSince1970: 1_786_600_000)
    let resolution = ManagedVideoIncidentScopePolicy.resolve(
        scopeKey: "map:NEW",
        startsByScope: ["map:OLD": 1_786_500_000],
        migrationCompleted: true,
        now: now
    )

    #expect(resolution.startedAt == now)
    #expect(resolution.migrationCompleted)
}

@Test func managedVideoSenderStartsBelowSelectedCeiling() {
    let high = ManagedVideoQualityPolicy.senderBitrates(targetBps: 3_000_000)
    #expect(high.minimumBps == 100_000)
    #expect(high.startupBps == 600_000)
    #expect(high.maximumBps == 3_000_000)

    let emergency = ManagedVideoQualityPolicy.senderBitrates(targetBps: 200_000)
    #expect(emergency.startupBps == emergency.maximumBps)
}

@Test func interruptedCaltopoPublicationJournalSurvivesRelaunchAndClearsAfterRecovery() async throws {
    let root = FileManager.default.temporaryDirectory
        .appendingPathComponent(UUID().uuidString, isDirectory: true)
    let fileURL = root.appendingPathComponent("caltopo-interrupted-publications.json")
    defer { try? FileManager.default.removeItem(at: root) }
    let observation = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "RID-RECOVERY",
        receivedAt: Date(timeIntervalSince1970: 1_786_503_975),
        latitude: 39.153080,
        longitude: -121.132828,
        altitudeMeters: 527
    )
    let entry = CaltopoInterruptedPublication(
        mapID: "map-a",
        remoteID: "RID-RECOVERY",
        liveTrackID: "live-recovery",
        label: "200615Aug11",
        observations: [observation]
    )

    let firstProcess = CaltopoInterruptedPublicationJournal(fileURL: fileURL)
    try await firstProcess.upsert(entry)

    let relaunchedProcess = CaltopoInterruptedPublicationJournal(fileURL: fileURL)
    let recovered = await relaunchedProcess.entries(mapID: "map-a")
    #expect(recovered == [entry])
    #expect(recovered[0].observations == [observation])

    try await relaunchedProcess.remove(liveTrackID: "live-recovery")
    let afterSuccessfulRecovery = CaltopoInterruptedPublicationJournal(fileURL: fileURL)
    #expect(await afterSuccessfulRecovery.entries(mapID: "map-a").isEmpty)
}
@Test func appleClueCaptureUsesValidatedSEIContinuationInsteadOfPerReadRIDAnchoring() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let source = try String(
        contentsOf: appleRoot.appendingPathComponent("App/RIDTrackMapView.swift"),
        encoding: .utf8
    )
    #expect(source.contains("freshValidatedDJIPositionByAircraftID(tracks: model.tracks)"))
    #expect(!source.contains(".anchoredToRID("))
}

@Test func cluePositionSourcePolicyBlocksSilentRIDFallbackForPositionalSEIStreams() {
    #expect(!OperationalCluePositionSourcePolicy.shouldBlockRIDFallback(
        seiPositionAuthorityEstablished: false,
        freshRawSEIPositionAvailable: false,
        validatedSEIPositionAvailable: false
    ))
    #expect(OperationalCluePositionSourcePolicy.shouldBlockRIDFallback(
        seiPositionAuthorityEstablished: false,
        freshRawSEIPositionAvailable: true,
        validatedSEIPositionAvailable: false
    ))
    #expect(OperationalCluePositionSourcePolicy.shouldBlockRIDFallback(
        seiPositionAuthorityEstablished: true,
        freshRawSEIPositionAvailable: false,
        validatedSEIPositionAvailable: false
    ))
    #expect(!OperationalCluePositionSourcePolicy.shouldBlockRIDFallback(
        seiPositionAuthorityEstablished: true,
        freshRawSEIPositionAvailable: true,
        validatedSEIPositionAvailable: true
    ))
}
