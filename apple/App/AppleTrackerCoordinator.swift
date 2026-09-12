import Combine
import CoreLocation
import CryptoKit
import Foundation
import R2CCore
import UIKit

enum AppleTrackerCoordinationStatus: String, Sendable {
    case unconfigured = "Not configured"
    case unavailable = "Unavailable"
    case standalone = "Standalone"
    case connecting = "Connecting"
    case healthy = "Verified"
    case standby = "Standby"
    case degraded = "Degraded"
}

struct AppleVideoStreamViewRequest: Codable, Identifiable, Equatable, Sendable {
    let requestId: String
    let requesterEmail: String
    let streamSessionId: String
    let incidentName: String
    let droneDesignator: String
    let sourceWidth: Int?
    let sourceHeight: Int?
    let sourceFps: Double?
    let sourceBitrateBps: Int64?
    let sourceCodec: String?
    let expiresAt: String
    let consentRequired: Bool

    var id: String { requestId }
}

struct AppleRecordingDownloadRequest: Codable, Identifiable, Equatable, Sendable {
    let requestId: String
    let requesterEmail: String
    let streamSessionId: String
    let droneDesignator: String
    let uploadPath: String
    let expiresAt: String
    let consentRequired: Bool
    var id: String { requestId }
}

struct AppleVideoQualityChoice: Identifiable, Equatable, Sendable {
    let id: String
    let label: String
    let width: Int
    let height: Int
    let fps: Double
    let bitrateBps: Int64
    let capacity: String
}

private struct AppleManagedVideoStreamAdvertisement: Codable, Equatable {
    let sessionId: String
    let droneDesignator: String
    let sourceWidth: Int
    let sourceHeight: Int
    let sourceFps: Double
    let sourceBitrateBps: Int64
    let sourceCodec: String
    let mediaKind: String
    let recordedAt: String?
    let durationMs: Int64
    let thumbnailRevision: String
    let thumbnailJpegBase64: String?

    func withThumbnail(revision: String, jpegBase64: String) -> Self {
        .init(
            sessionId: sessionId,
            droneDesignator: droneDesignator,
            sourceWidth: sourceWidth,
            sourceHeight: sourceHeight,
            sourceFps: sourceFps,
            sourceBitrateBps: sourceBitrateBps,
            sourceCodec: sourceCodec,
            mediaKind: mediaKind,
            recordedAt: recordedAt,
            durationMs: durationMs,
            thumbnailRevision: revision,
            thumbnailJpegBase64: jpegBase64
        )
    }
}

private struct AppleManagedVideoRecording: Equatable {
    let sessionId: String
    let droneDesignator: String
    let url: URL
    let startedAt: Date
    let recordedAt: Date
    let durationMs: Int64
}

private enum AppleManagedVideoRecordingCatalog {
    static func snapshot(sessionStartedAt: Date, now: Date = Date()) -> [AppleManagedVideoRecording] {
        guard UserDefaults.standard.bool(forKey: "video.captureStreams"),
              let documents = try? FileManager.default.url(
                  for: .documentDirectory,
                  in: .userDomainMask,
                  appropriateFor: nil,
                  create: false
              )
        else { return [] }
        let root = documents.appendingPathComponent(
            "RID2Caltopo/CapturedStreams",
            isDirectory: true
        )
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [
                .isRegularFileKey,
                .contentModificationDateKey,
                .fileSizeKey,
            ],
            options: [.skipsHiddenFiles]
        ) else { return [] }
        return enumerator.compactMap { element -> AppleManagedVideoRecording? in
            guard let url = element as? URL,
                  ["mp4", "fmp4"].contains(url.pathExtension.lowercased()),
                  let values = try? url.resourceValues(forKeys: [
                      .isRegularFileKey,
                      .contentModificationDateKey,
                      .fileSizeKey,
                  ]),
                  values.isRegularFile == true,
                  (values.fileSize ?? 0) > 0,
                  ManagedVideoRecordingIdentity.isCompletedRecordingPath(url.path),
                  let modified = values.contentModificationDate,
                  modified >= sessionStartedAt,
                  now.timeIntervalSince(modified) >= 3
            else { return nil }
            let designator = url.deletingLastPathComponent().lastPathComponent
            let startedAt = min(
                ManagedVideoRecordingIdentity.recordingStartedAt(forPath: url.path) ?? modified,
                modified
            )
            return AppleManagedVideoRecording(
                sessionId: ManagedVideoRecordingIdentity.sessionID(forPath: url.path),
                droneDesignator: designator.isEmpty ? "Recording" : designator,
                url: url,
                startedAt: startedAt,
                recordedAt: modified,
                durationMs: max(0, Int64(modified.timeIntervalSince(startedAt) * 1_000))
            )
        }
        .sorted { $0.recordedAt > $1.recordedAt }
        .prefix(20)
        .map { $0 }
    }

}

private struct AppleVideoPreflightOffer: Decodable {
    let requestId: String
    let sdp: String
    let iceServers: [AppleVideoICEServer]
}

private struct AppleVideoMediaOffer: Decodable {
    let requestId: String
    let streamSessionId: String
    let requesterEmail: String?
    let routeKind: String?
    let selectedWidth: Int?
    let selectedHeight: Int?
    let selectedFps: Double?
    let selectedBitrateBps: Int64?
    let sdp: String
    let iceServers: [AppleVideoICEServer]
}

private struct AppleApprovedVideoStream {
    let request: AppleVideoStreamViewRequest
    let quality: AppleVideoQualityChoice
}

@MainActor
private final class AppleBackgroundTransferLease {
    private var identifier = UIBackgroundTaskIdentifier.invalid

    init(name: String) {
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            Task { @MainActor [weak self] in self?.end() }
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}

@MainActor
enum AppleManagedOrganizationConfig {
    static let versionDefaultsKey = "org.managedConfigVersionMs"

    static func snapshot(
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) throws -> [String: Any] {
        let credentials = try OrgConfigTokenCodec.canonicalCredentialPayload([
            "type": "ct_credentials", "file_version": "1.0",
            "team_id": caltopo.teamID,
            "credential_id": caltopo.credentialID,
            "credential_secret": caltopo.credentialSecret,
            "domain_and_port": caltopo.domainAndPort,
            "connect_key": caltopo.connectKey,
            "track_folder": organization.trackFolder,
        ])
        let mutualAidEncoded: String
        if let template = organization.mutualAidTemplate {
            let mutualAid = try OrgConfigTokenCodec.canonicalCredentialPayload([
                "type": "ct_mutual_aid_credentials", "file_version": "1.0",
                "team_id": template.teamID, "credential_id": template.credentialID,
                "credential_secret": template.credentialSecret,
                "domain_and_port": template.domainAndPort,
                "source_label": template.sourceLabel,
                "target_folder_hint": template.targetFolderHint,
                "connect_key": template.connectKey,
            ])
            mutualAidEncoded = OrgConfigTokenCodec.encryptPayload(mutualAid)
        } else {
            mutualAidEncoded = ""
        }
        return [
            "configSchemaVersion": 1, "aircraftSchemaVersion": 1, "sourcePlatform": "ios",
            "sourceAppVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
            "sourceAppBuild": Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0,
            "organizationCaltopoEnc": OrgConfigTokenCodec.encryptPayload(credentials),
            "mutualAidCaltopoEnc": mutualAidEncoded,
            "droneSpecs": identities.importedMappings.map { identity in
                let source = (AppleAircraftOrganizationAccess.cachedState["aircraft"] as? [[String: Any]] ?? []).first { ($0["remoteId"] as? String)?.caseInsensitiveCompare(identity.remoteID) == .orderedSame }
                let clean = source.map { identity.preservingBlankPublishedOwnerFields($0) } ?? identity
                return ["remoteId": clean.remoteID, "mappedId": clean.mappedID,
                 "org": clean.organization, "model": clean.droneDescription,
                 "owner": clean.pilotCallsign, "ownerName": clean.ownerName, "ownerCallsign": clean.pilotCallsign,
                 "readiness": clean.readiness.dictionary] as [String: Any]
            },
        ]
    }

    static func apply(
        snapshot: [String: Any], versionMs: Int64,
        caltopo: AppleCaltopoSettings, organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore, defaults: UserDefaults = .standard
    ) throws {
        guard (snapshot["configSchemaVersion"] as? NSNumber)?.intValue == 1,
              versionMs > 0,
              let orgEncoded = snapshot["organizationCaltopoEnc"] as? String,
              let orgData = try OrgConfigTokenCodec.decryptPayload(orgEncoded).data(using: .utf8),
              let credentials = try JSONSerialization.jsonObject(with: orgData) as? [String: Any],
              let droneSpecs = snapshot["droneSpecs"] as? [[String: Any]]
        else { throw OrgConfigInteropError.invalidBundle }
        let mutualAid: MutualAidTemplateCredentials?
        if let encoded = snapshot["mutualAidCaltopoEnc"] as? String, !encoded.isEmpty,
           let data = try OrgConfigTokenCodec.decryptPayload(encoded).data(using: .utf8),
           let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            mutualAid = .init(
                teamID: value["team_id"] as? String ?? "",
                credentialID: value["credential_id"] as? String ?? "",
                credentialSecret: value["credential_secret"] as? String ?? "",
                domainAndPort: value["domain_and_port"] as? String ?? "caltopo.com",
                sourceLabel: value["source_label"] as? String ?? "",
                targetFolderHint: value["target_folder_hint"] as? String ?? "MAI",
                connectKey: value["connect_key"] as? String ?? "")
        } else { mutualAid = nil }
        try caltopo.applyManagedCredentials(credentials)
        try organization.applyManagedOrganization(
            organizationName: organization.organizationName,
            trackFolder: credentials["track_folder"] as? String ?? organization.trackFolder,
            mutualAidTemplate: mutualAid)
        try identities.applyImportedMappings(droneSpecs.compactMap { value in
            guard let remoteID = value["remoteId"] as? String, !remoteID.isEmpty else { return nil }
            return OrgConfigRIDMapping(
                remoteID: remoteID, mappedID: value["mappedId"] as? String ?? "",
                organization: value["org"] as? String ?? "",
                model: value["model"] as? String ?? "",
                owner: value["owner"] as? String ?? "",
                ownerName: value["ownerName"] as? String ?? "",
                ownerCallsign: value["ownerCallsign"] as? String ?? "",
                readiness: AircraftReadiness.decode(value["readiness"]))
        }, managedDownload: true)
        defaults.set(versionMs, forKey: versionDefaultsKey)
        defaults.set((snapshot["aircraftSchemaVersion"] as? NSNumber)?.intValue ?? 0, forKey: "org.managedReadinessSchemaVersion")
        AppleLog.info("OrgConfig", "Applied managed organization configuration \(versionMs)")
    }

}

@MainActor
final class AppleTrackerCoordinator: ObservableObject {
    static let reenrollmentRequiredStatusDetail =
        "Tracker authorization rejected; re-enrollment required"

    nonisolated let events: AsyncStream<TrackerCoordinationEvent>
    private nonisolated let eventContinuation: AsyncStream<TrackerCoordinationEvent>.Continuation

    @Published private(set) var status: AppleTrackerCoordinationStatus = .unconfigured
    @Published private(set) var peers: [TrackerPeerZone] = []
    @Published private(set) var statusDetail = "Tracker coordination not configured"
    @Published private(set) var helloAcknowledgedAtMilliseconds: Int64 = 0
    @Published private(set) var heartbeatAcknowledgedAtMilliseconds: Int64 = 0
    @Published private(set) var heartbeatSequenceAcknowledged: Int64 = 0
    @Published private(set) var trackerSilenceGeneration = 0
    @Published private(set) var recommendedAppVersionCode = 0
    @Published private(set) var reauthenticationRequiredGeneration = 0
    @Published private(set) var reauthenticationURL: URL?
    @Published private(set) var recommendedUpdateURL: URL?
    @Published private(set) var pendingVideoStreamRequest: AppleVideoStreamViewRequest?
    @Published private(set) var pendingRecordingDownloadRequest: AppleRecordingDownloadRequest?
    private var approvedRecordingUploadsByRequestID: [String: AppleRecordingDownloadRequest] = [:]
    private var pendingVideoRequestExpiryTask: Task<Void, Never>?
    private var pendingRecordingRequestExpiryTask: Task<Void, Never>?
    @Published private(set) var videoPreflightRouteKind: String?
    @Published private(set) var videoPreflightEstimatedUplinkBps: Int64?
    @Published private(set) var videoPreflightFailure: String?
    @Published private(set) var selectedVideoQualityID: String?
    @Published private(set) var activeRemoteVideoConnectionCount = 0
    @Published private(set) var remoteVideoBytesSent: Int64 = 0
    @Published private(set) var remoteVideoEffectiveWidth = 0
    @Published private(set) var remoteVideoEffectiveHeight = 0
    @Published private(set) var remoteVideoEffectiveFPS = 0.0
    @Published private(set) var remoteVideoEffectiveBitrateBps: Int64 = 0
    @Published private(set) var remoteVideoMicrophoneEnabled = false
    @Published private(set) var remoteVideoMicrophoneError: String?
    @Published private(set) var remoteVideoAudioBytesSent: Int64 = 0
    @Published private(set) var remoteVideoAudioBytesReceived: Int64 = 0

    var videoQualityChoices: [AppleVideoQualityChoice] {
        guard let request = pendingVideoStreamRequest else { return [] }
        return ManagedVideoQualityPolicy.options(
            sourceWidth: request.sourceWidth ?? 0,
            sourceHeight: request.sourceHeight ?? 0,
            sourceFps: request.sourceFps ?? 0,
            sourceBitrateBps: request.sourceBitrateBps ?? 0,
            usableUplinkBps: videoPreflightEstimatedUplinkBps ?? 0
        ).map { option in
                let capacity = option.capacity.rawValue
                return AppleVideoQualityChoice(
                    id: option.id,
                    label: String(
                        format: "%@ • %d×%d • %.1f fps • est. %.1f Mbps • %@",
                        option.preset,
                        option.width,
                        option.height,
                        option.fps,
                        Double(option.estimatedBitrateBps) / 1_000_000,
                        capacity == "enough"
                            ? "enough bandwidth"
                            : capacity == "fallback"
                                ? "fallback — try lowest quality"
                                : capacity
                    ),
                    width: option.width,
                    height: option.height,
                    fps: option.fps,
                    bitrateBps: option.estimatedBitrateBps,
                    capacity: capacity
                )
            }
    }

    var selectedVideoQualityIsStartable: Bool {
        videoQualityChoices.contains {
            $0.id == selectedVideoQualityID && $0.capacity != "insufficient"
        }
    }

    var videoStreamRequestReadyForApproval: AppleVideoStreamViewRequest? {
        guard ManagedVideoQualityPolicy.shouldPresentApproval(
            routeKind: videoPreflightRouteKind,
            failure: videoPreflightFailure
        ) else {
            return nil
        }
        return pendingVideoStreamRequest
    }

    private var usePeers = false
    private var remoteControlledVideoRequests: [String: AppleVideoStreamViewRequest] = [:]
    private var standaloneR2CCoordinationEnabled = false
    private var trackerURLPrefix = ""
    private var trackerAPIKey = ""
    private let defaults: UserDefaults
    private var organizationConfigSnapshotProvider: (() throws -> [String: Any])?
    private var organizationConfigApplyHandler: (([String: Any], Int64) throws -> Void)?
    private var managedVideoThumbnailUpdatedHandler: ((String) -> Void)?
    private var mapID = ""
    private let zoneID: String
    private var zoneName: String
    private var client: TrackerCoordinationClient
    private var position = TrackerCoordinationPosition(latitude: 0, longitude: 0)
    private var protocolState: TrackerCoordinationProtocolState
    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var socketDelegate: AppleTrackerWebSocketDelegate?
    private var generation = UUID()
    private var connected = false
    private var helloAcknowledged = false
    private var trackerKnownUnavailable = false
    private var reconnectDelay: Duration = .seconds(2)
    private var receiveTask: Task<Void, Never>?
    private var receiveFailureTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var trackerSilenceTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var standbyTask: Task<Void, Never>?
    private var authorizationRecoveryTask: Task<Void, Never>?
    private var standaloneStandbyEligible = false
    private var coordinationAttemptStartedAtMilliseconds: Int64 = 0
    private var organizationConfigSyncInProgress = false
    private var standbyParkDelay: Duration = .seconds(30)
    private var observedSightings: [String: TrackerCoordinationSighting] = [:]
    private var firstSightingsSent: Set<String> = []
    private var seenVideoStreamRequestIDs: [String] = []
    private var pendingConfirmations: [String: TrackerCoordinationIdentity] = [:]
    private var lastSightingSentAt: [String: Date] = [:]
    private let trafficSourceEpoch = UUID().uuidString
    private var trafficSequenceByKey: [String: Int64] = [:]
    private var lastTrafficSentAtByKey: [String: Date] = [:]
    private var trafficSendIntervalByKey: [String: TimeInterval] = [:]
    private var lastTrafficSentLogAtByKey: [String: Date] = [:]
    private var lastTrafficShadowLogAtByKey: [String: Date] = [:]
    private var trafficAltitudeCalibrationByRemoteID: [String: TrackerTrafficAltitudeCalibration] = [:]
    private var proximityAlertDistanceFeet: Double?
    private var fallbackTasks: [String: Task<Void, Never>] = [:]
    private var managedVideoIncidentName = ""
    private var managedVideoStreams: [AppleManagedVideoStreamAdvertisement] = []
    private var managedVideoSourcesBySessionID: [String: AppleVideoFrameSource] = [:]
    private var managedVideoIncidentScopeKey = ""
    private var managedVideoIncidentStartedAt = Date()
    private var managedVideoRecordingsBySessionID: [String: AppleManagedVideoRecording] = [:]
    private var managedVideoThumbnailTasks: [String: Task<Void, Never>] = [:]
    private var managedVideoThumbnailPreviewUntil = Date.distantPast
    private var managedVideoThumbnailPreviewTask: Task<Void, Never>?
    private var caltopoThumbnailRefreshTask: Task<Void, Never>?
    private var caltopoLiveTrackDesignators: Set<String> = []
    private var caltopoThumbnailRefreshGeneration = 0
    private var managedVideoRecordingSourceRequestIDs: Set<String> = []
    private var managedSessionIDBySourcePath: [String: String] = [:]
    private var lastVideoPreflightOfferByRequestID: [String: String] = [:]
    private var approvedVideoStreamRequests: [String: AppleApprovedVideoStream] = [:]
    private var mediaPeersByRequestID: [String: AppleManagedVideoMediaPeer] = [:]
    private var mediaOfferSDPByRequestID: [String: String] = [:]
    private var mediaAttemptIDByRequestID: [String: UUID] = [:]
    private var mediaSourcesByRequestID: [String: AppleVideoFrameSource] = [:]
    private var mediaRouteKindByRequestID: [String: String] = [:]
    private var sourceEndGraceTasks: [String: Task<Void, Never>] = [:]
    private var videoPreflightWatchdogTask: Task<Void, Never>?
    private lazy var videoPreflightPeer = AppleManagedVideoPreflightPeer(
        answerSink: { [weak self] requestID, sdp in
            Task { @MainActor [weak self] in
                self?.sendVideoPreflightAnswer(requestID: requestID, sdp: sdp)
            }
        },
        resultSink: { [weak self] requestID, routeKind, estimatedUplinkBps in
            Task { @MainActor [weak self] in
                self?.recordVideoPreflightResult(
                    requestID: requestID,
                    routeKind: routeKind,
                    estimatedUplinkBps: estimatedUplinkBps
                )
            }
        },
        failureSink: { [weak self] requestID, reason in
            Task { @MainActor [weak self] in
                self?.recordVideoPreflightFailure(
                    requestID: requestID,
                    reason: reason
                )
            }
        }
    )

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let pair = AsyncStream<TrackerCoordinationEvent>.makeStream(bufferingPolicy: .bufferingNewest(256))
        events = pair.stream
        eventContinuation = pair.continuation
        zoneID = AppleDeviceIdentity.installationID(defaults: defaults)
        zoneName = AppleDeviceIdentity.displayName
        client = Self.makeClient(mapID: "", zoneID: zoneID, zoneName: zoneName)
        protocolState = TrackerCoordinationProtocolState(localZoneID: zoneID)
    }

    deinit {
        eventContinuation.finish()
    }

    var coordinationRequired: Bool {
        usePeers && !trackerURLPrefix.isEmpty && !trackerAPIKey.isEmpty
            && (!mapID.isEmpty || standaloneR2CCoordinationEnabled)
    }

    var localZoneID: String { zoneID }

    var hasOperationalActivityPreventingIncidentDisconnect: Bool {
        let videoOrTransferActive = pendingVideoStreamRequest != nil ||
            pendingRecordingDownloadRequest != nil ||
            !approvedRecordingUploadsByRequestID.isEmpty ||
            hasLiveManagedVideoStream ||
            !mediaPeersByRequestID.isEmpty
        if videoOrTransferActive || hasTrackerAcknowledgementSilence {
            return videoOrTransferActive
        }
        return !pendingConfirmations.isEmpty || !observedSightings.isEmpty
    }

    var hasTrackerAcknowledgementSilence: Bool {
        guard coordinationRequired, status != .standby else { return false }
        let lastAcknowledgement = max(
            helloAcknowledgedAtMilliseconds,
            heartbeatAcknowledgedAtMilliseconds
        )
        let anchor = max(lastAcknowledgement, coordinationAttemptStartedAtMilliseconds)
        return anchor > 0 && Self.nowMilliseconds - anchor >= 60_000
    }

    func requireReauthentication(at url: URL) {
        reauthenticationURL = url
        reauthenticationRequiredGeneration &+= 1
        status = .unavailable
        statusDetail = "Tracker reauthentication required"
        stopTransport()
    }

    func configureOrganizationConfig(
        snapshotProvider: @escaping () throws -> [String: Any],
        applyHandler: @escaping ([String: Any], Int64) throws -> Void
    ) {
        organizationConfigSnapshotProvider = snapshotProvider
        organizationConfigApplyHandler = applyHandler
    }

    var activeRemoteVideoRequesterSummary: String {
        let emails = Set(mediaPeersByRequestID.keys.compactMap {
            approvedVideoStreamRequests[$0]?.request.requesterEmail
        }).sorted()
        return emails.isEmpty ? "Remote viewer" : emails.joined(separator: ", ")
    }

    var currentRemoteVideoRequesterEmail: String? {
        let activeRequestID = mediaPeersByRequestID.keys.sorted().first
        if let activeRequestID,
           let email = approvedVideoStreamRequests[activeRequestID]?
            .request.requesterEmail {
            return email
        }
        return approvedVideoStreamRequests.values
            .map(\.request)
            .sorted { $0.requestId < $1.requestId }
            .first?.requesterEmail
    }

    func activeRemoteVideoRequesterEmail(forStreamDesignator designator: String) -> String? {
        let normalizedDesignator = designator
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard !normalizedDesignator.isEmpty else { return nil }
        return mediaPeersByRequestID.keys
            .sorted()
            .compactMap { approvedVideoStreamRequests[$0]?.request }
            .first { request in
                request.droneDesignator
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .lowercased() == normalizedDesignator
            }?
            .requesterEmail
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var activeRemoteVideoRouteSummary: String {
        let routes = Set(mediaPeersByRequestID.keys.compactMap {
            mediaRouteKindByRequestID[$0]
        })
        if routes.count == 1, let route = routes.first {
            return route == "direct" ? "Direct" : "Routed"
        }
        return routes.isEmpty ? "Negotiating" : "Mixed routes"
    }

    var localDeviceStatusLines: [String] {
        var lines = [statusDetail]
        if coordinationRequired && status != .unavailable {
            lines.append(
                helloAcknowledgedAtMilliseconds > 0
                    ? "Hello ack \(Self.elapsedText(since: helloAcknowledgedAtMilliseconds)) ago"
                    : "Hello ack waiting"
            )
            lines.append(
                heartbeatAcknowledgedAtMilliseconds > 0
                    ? "Heartbeat ack \(Self.elapsedText(since: heartbeatAcknowledgedAtMilliseconds)) ago (seq \(heartbeatSequenceAcknowledged))"
                    : "Heartbeat ack waiting"
            )
        }
        lines.append(peers.isEmpty ? "Peers: none" : "Peers: \(peers.count)")
        return lines
    }

    func configure(
        usePeers: Bool,
        standaloneR2CCoordinationEnabled: Bool,
        trackerURLPrefix: String,
        trackerAPIKey: String,
        mapID: String,
        forceReconnect: Bool = false
    ) {
        let normalizedURL = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedKey = trackerAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedMapID = mapID.trimmingCharacters(in: .whitespacesAndNewlines)
        AppleAircraftOrganizationAccess.operatingIncidentID = normalizedMapID
        AppleAircraftOrganizationAccess.operatingAssignment.setScope(organization: normalizedURL, incident: normalizedMapID)
        let trackerConfigurationChanged = self.trackerURLPrefix != normalizedURL
            || self.trackerAPIKey != normalizedKey
        let previouslyConfigured = !self.trackerURLPrefix.isEmpty && !self.trackerAPIKey.isEmpty
        let unchanged = self.usePeers == usePeers
            && self.standaloneR2CCoordinationEnabled == standaloneR2CCoordinationEnabled
            && self.trackerURLPrefix == normalizedURL
            && self.trackerAPIKey == normalizedKey
            && self.mapID == normalizedMapID
        guard !unchanged || forceReconnect else { return }
        stopTransport()
        self.usePeers = usePeers
        self.standaloneR2CCoordinationEnabled = standaloneR2CCoordinationEnabled
        self.trackerURLPrefix = normalizedURL
        if self.trackerAPIKey != normalizedKey { AppleAircraftOrganizationAccess.operatingAssignment.end() }
        self.trackerAPIKey = normalizedKey
        self.mapID = normalizedMapID
        standaloneStandbyEligible = normalizedMapID.isEmpty
            && standaloneR2CCoordinationEnabled
        if (trackerConfigurationChanged || forceReconnect)
            && !normalizedURL.isEmpty && !normalizedKey.isEmpty {
            trackerKnownUnavailable = false
        } else if previouslyConfigured && (normalizedURL.isEmpty || normalizedKey.isEmpty) {
            trackerKnownUnavailable = true
        }
        client = Self.makeClient(mapID: normalizedMapID, zoneID: zoneID, zoneName: zoneName)
        reauthenticationURL = nil
        protocolState = TrackerCoordinationProtocolState(localZoneID: zoneID)
        resetAcknowledgements()
        observedSightings.removeAll()
        pendingConfirmations.removeAll()
        fallbackTasks.values.forEach { $0.cancel() }
        fallbackTasks.removeAll()

        guard usePeers else {
            status = .standalone
            statusDetail = "Peer coordination disabled by configuration"
            return
        }
        guard coordinationRequired else {
            if trackerKnownUnavailable || normalizedURL.isEmpty || normalizedKey.isEmpty {
                status = .unavailable
                statusDetail = trackerKnownUnavailable
                    ? "Tracker unavailable"
                    : "Tracker unavailable: URL or API key missing"
            } else {
                status = .unconfigured
                statusDetail = missingConfigurationDescription
            }
            return
        }
        if normalizedMapID.isEmpty {
            statusDetail = "Connecting to tracker without an active CalTopo map"
        }
        if forceReconnect {
            AppleLog.info("TrackerPeer", "Reconnecting tracker after credential refresh")
        }
        coordinationAttemptStartedAtMilliseconds = Self.nowMilliseconds
        startTrackerSilenceWatchdog()
        connect()
    }

    private func startTrackerSilenceWatchdog() {
        trackerSilenceTask?.cancel()
        trackerSilenceTask = Task { @MainActor [weak self] in
            while let self, !Task.isCancelled, self.coordinationRequired {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled else { return }
                if self.hasTrackerAcknowledgementSilence {
                    self.trackerSilenceGeneration &+= 1
                    try? await Task.sleep(for: .seconds(15))
                }
            }
        }
    }

    func updatePosition(_ location: CLLocation?) {
        guard let location else { return }
        position = TrackerCoordinationPosition(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            caltopoRTTMilliseconds: position.caltopoRTTMilliseconds
        )
    }

    func updateCaltopoRTT(milliseconds: Int64?) {
        guard let milliseconds, milliseconds >= 0 else { return }
        position = TrackerCoordinationPosition(
            latitude: position.latitude,
            longitude: position.longitude,
            caltopoRTTMilliseconds: milliseconds
        )
    }

    func updateProximityAlertDistanceFeet(_ value: Double) {
        proximityAlertDistanceFeet = value.isFinite && value > 0 ? value : nil
    }

    func beginPeerTrafficAltitudeCalibration(remoteID: String, flightEpoch: String) {
        trafficAltitudeCalibrationByRemoteID[remoteID] = TrackerTrafficAltitudeCalibration(
            flightEpoch: flightEpoch,
            state: "pending"
        )
    }

    func finishPeerTrafficAltitudeCalibration(
        remoteID: String,
        flightEpoch: String,
        calibration: TrackerTrafficAltitudeCalibration?
    ) {
        guard trafficAltitudeCalibrationByRemoteID[remoteID]?.flightEpoch == flightEpoch else { return }
        trafficAltitudeCalibrationByRemoteID[remoteID] = calibration
            ?? TrackerTrafficAltitudeCalibration(flightEpoch: flightEpoch, state: "unavailable")
    }

    func observe(track: RidAircraftTrack, identity: RidAircraftIdentity?) {
        guard coordinationRequired else { return }
        let resolved = identity.map(Self.trackerIdentity) ?? TrackerCoordinationIdentity(
            remoteID: track.aircraftID,
            mappedID: track.aircraftID,
            organization: "",
            model: "",
            ownerName: ""
        )
        let range = position.latitude == 0 && position.longitude == 0
            ? nil
            : RidGeometry.relativePosition(
                fromLatitude: position.latitude,
                longitude: position.longitude,
                toLatitude: track.lastObservation.latitude,
                longitude: track.lastObservation.longitude
            )?.distanceMeters
        let sighting = TrackerCoordinationSighting(
            identity: resolved,
            droneTimestampMilliseconds: Int64(track.lastObservation.receivedAt.timeIntervalSince1970 * 1_000),
            latitude: track.lastObservation.latitude,
            longitude: track.lastObservation.longitude,
            altitudeMeters: track.lastObservation.altitudeMeters,
            distanceFromZoneMeters: range,
            headingDegrees: track.lastObservation.headingDegrees,
            groundSpeedKnots: track.lastObservation.speedMetersPerSecond.map { $0 * 1.943_844 }
        )
        let isFirst = observedSightings[track.aircraftID] == nil
        observedSightings[track.aircraftID] = sighting
        sendTrafficPositionIfEligible(sighting, source: "rid")
        if isFirst {
            wakeForCoordinationActivity(reason: "first sighting")
            scheduleFallbackOwnership(remoteID: track.aircraftID)
            sendFirstSightingIfNeeded(remoteID: track.aircraftID)
        } else {
            sendSightingIfEligible(sighting)
        }
    }

    func confirm(_ identity: RidAircraftIdentity) {
        guard coordinationRequired else { return }
        let trackerIdentity = Self.trackerIdentity(identity)
        pendingConfirmations[identity.remoteID] = trackerIdentity
        wakeForCoordinationActivity(reason: "drone confirmation")
        if let event = protocolState.confirmLocally(remoteID: identity.remoteID) {
            publish(event)
        }
        flushPendingConfirmations()
    }

    func droneLost(remoteID: String) {
        observedSightings.removeValue(forKey: remoteID)
        firstSightingsSent.remove(remoteID)
        pendingConfirmations.removeValue(forKey: remoteID)
        lastSightingSentAt.removeValue(forKey: remoteID)
        trafficAltitudeCalibrationByRemoteID.removeValue(forKey: remoteID)
        trafficSequenceByKey = trafficSequenceByKey.filter { !$0.key.hasSuffix("|\(remoteID)") }
        lastTrafficSentAtByKey = lastTrafficSentAtByKey.filter { !$0.key.hasSuffix("|\(remoteID)") }
        trafficSendIntervalByKey = trafficSendIntervalByKey.filter { !$0.key.hasSuffix("|\(remoteID)") }
        lastTrafficSentLogAtByKey = lastTrafficSentLogAtByKey.filter { !$0.key.hasSuffix("|\(remoteID)") }
        fallbackTasks.removeValue(forKey: remoteID)?.cancel()
        scheduleStandbyIfEligible()
        guard coordinationRequired,
              let data = try? TrackerCoordinationWire.droneLost(client: client, remoteID: remoteID)
        else { return }
        send(data)
    }

    private var hasLiveManagedVideoStream: Bool {
        managedVideoStreams.contains { $0.mediaKind == "live" }
    }

    private var canEnterStandaloneStandby: Bool {
        let activeTrackerInteractions = (pendingVideoStreamRequest == nil ? 0 : 1)
            + (pendingRecordingDownloadRequest == nil ? 0 : 1)
            + remoteControlledVideoRequests.count
            + approvedVideoStreamRequests.count
            + approvedRecordingUploadsByRequestID.count
        return TrackerStandbyPolicy.isEligible(
            standalone: standaloneStandbyEligible,
            connected: connected,
            helloAcknowledged: helloAcknowledged,
            configurationSyncInProgress: organizationConfigSyncInProgress,
            activeSightings: observedSightings.count,
            pendingConfirmations: pendingConfirmations.count,
            hasLiveVideo: hasLiveManagedVideoStream,
            activeMediaConnections: mediaPeersByRequestID.count,
            activeTrackerInteractions: activeTrackerInteractions
        )
    }

    private func scheduleStandbyIfEligible() {
        guard canEnterStandaloneStandby else {
            standbyTask?.cancel()
            standbyTask = nil
            return
        }
        guard standbyTask == nil else { return }
        let delay = standbyParkDelay
        standbyTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self, self.canEnterStandaloneStandby else { return }
            self.sendStandaloneStandbyNotice()
            try? await Task.sleep(for: .milliseconds(150))
            guard !Task.isCancelled, self.canEnterStandaloneStandby else { return }
            self.standbyTask = nil
            self.stopSocketOnly()
            self.peers = []
            self.status = .standby
            self.statusDetail = "Tracker standby"
            AppleLog.info("TrackerPeer", "Standalone tracker websocket entered standby")
        }
    }

    private func sendStandaloneStandbyNotice() {
        let payload: [String: Any] = [
            "type": "idle",
            "mapId": mapID,
            "zoneId": zoneID,
            "guid": zoneID,
            "reason": "standalone_standby",
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        send(data)
    }

    private func wakeForCoordinationActivity(reason: String) {
        guard status == .standby else {
            standbyTask?.cancel()
            standbyTask = nil
            return
        }
        standbyTask?.cancel()
        standbyTask = nil
        AppleLog.info("TrackerPeer", "Waking tracker websocket for \(reason)")
        connect()
    }

    func publicationAllowed(remoteID: String) -> Bool {
        !coordinationRequired || protocolState.isLocalAlertEligible(remoteID: remoteID)
    }

    func isLocalAlertEligible(remoteID: String) -> Bool {
        coordinationRequired && protocolState.isLocalAlertEligible(remoteID: remoteID)
    }

    private func connect() {
        guard coordinationRequired,
              let url = try? TrackerCoordinationEndpoint.webSocketURL(from: trackerURLPrefix)
        else { return }
        stopSocketOnly()
        status = .connecting
        statusDetail = "Connecting to tracker"
        connected = false
        helloAcknowledged = false
        generation = UUID()
        let currentGeneration = generation
        let delegate = AppleTrackerWebSocketDelegate(
            onOpen: { [weak self] in
                Task { @MainActor [weak self] in self?.socketOpened(generation: currentGeneration) }
            },
            onClose: { [weak self] code, reason in
                Task { @MainActor [weak self] in
                    self?.socketClosed(generation: currentGeneration, detail: "Closed \(code): \(reason)")
                }
            },
            onFailure: { [weak self] responseCode, detail in
                Task { @MainActor [weak self] in
                    self?.socketFailed(
                        generation: currentGeneration,
                        responseCode: responseCode,
                        detail: detail
                    )
                }
            }
        )
        socketDelegate = delegate
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 20
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        self.session = session
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue("RID2Caltopo/coordination", forHTTPHeaderField: "User-Agent")
        request.setValue(trackerAPIKey, forHTTPHeaderField: "X-SAR-Token")
        request.setValue(
            String(TrackerCoordinationClient.trackerFunctionalityRelease),
            forHTTPHeaderField: "X-R2C-Functionality-Release"
        )
        let socket = session.webSocketTask(with: request)
        self.socket = socket
        socket.resume()
        // Queue the first receive immediately. Tracker can accept, send a
        // reauthentication challenge, and close before URLSession delivers
        // didOpen; waiting for didOpen loses that device-specific challenge.
        startReceiveLoop(socket: socket, generation: currentGeneration)
    }

    private func startReceiveLoop(
        socket: URLSessionWebSocketTask,
        generation currentGeneration: UUID
    ) {
        receiveTask?.cancel()
        receiveTask = Task { [weak self, weak socket] in
            guard let socket else { return }
            do {
                while !Task.isCancelled {
                    let message = try await socket.receive()
                    guard let self else { return }
                    self.received(message, generation: currentGeneration)
                }
            } catch is CancellationError {
                return
            } catch {
                guard let self else { return }
                self.receiveFailed(error, generation: currentGeneration)
            }
        }
    }

    private func receiveFailed(_ error: Error, generation currentGeneration: UUID) {
        guard currentGeneration == generation, coordinationRequired else { return }
        let detail = "Receive failed: \(Self.errorDescription(error))"
        status = .degraded
        statusDetail = detail
        AppleLog.error("TrackerPeer", "\(detail); awaiting WebSocket close reason")
        receiveFailureTask?.cancel()
        receiveFailureTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(750))
            guard !Task.isCancelled, let self, currentGeneration == self.generation else { return }
            self.receiveFailureTask = nil
            self.socketClosed(generation: currentGeneration, detail: detail)
        }
    }

    private func socketOpened(generation: UUID) {
        guard generation == self.generation, socket != nil else { return }
        connected = true
        lastTrafficSentAtByKey.removeAll()
        trafficSendIntervalByKey.removeAll()
        lastTrafficSentLogAtByKey.removeAll()
        lastTrafficShadowLogAtByKey.removeAll()
        let now = Self.nowMilliseconds
        protocolState.transportOpened(helloSentAtMilliseconds: now)
        firstSightingsSent.removeAll()
        status = .connecting
        statusDetail = "Waiting for tracker hello"
        if let hello = try? TrackerCoordinationWire.hello(client: client, position: position) {
            send(hello)
        }
        replayFirstSightings()
        flushPendingConfirmations()
        sendManagedVideoPresence()
        startLivenessTasks(generation: generation)
        AppleLog.info("TrackerPeer", "WebSocket opened mapId='\(mapID)' zoneId='\(zoneID)'")
    }

    private func received(_ message: URLSessionWebSocketTask.Message, generation: UUID) {
        guard generation == self.generation else { return }
        let data: Data
        switch message {
        case let .data(value): data = value
        case let .string(value): data = Data(value.utf8)
        @unknown default: return
        }
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let type = object["type"] as? String {
            if type == "hello_ack" {
                if let canonicalName = (object["canonicalDeviceName"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines), !canonicalName.isEmpty {
                    AppleDeviceIdentity.applyManagedDisplayName(canonicalName)
                    zoneName = canonicalName
                    client = Self.makeClient(mapID: mapID, zoneID: zoneID, zoneName: zoneName)
                }
                let seconds = (object["standbyParkSec"] as? NSNumber)?.intValue
                    ?? (object["idleParkSec"] as? NSNumber)?.intValue
                    ?? 30
                standbyParkDelay = .seconds(
                    TrackerStandbyPolicy.normalizedDelaySeconds(seconds)
                )
            }
            if type == "reauthentication_required" {
                if let url = (object["reauthenticationUrl"] as? String)
                    .flatMap(URL.init(string:)) {
                    requireReauthentication(at: url)
                }
                return
            }
            if type == "upgrade_required" {
                status = .unavailable
                statusDetail = object["message"] as? String
                    ?? "Tracker requires a newer functionality release"
                stopTransport()
                return
            }
        }
        if handleOrganizationConfigMessage(data) || handleManagedVideoMessage(data) {
            return
        }
        do {
            let events = try protocolState.handleIncoming(data, receivedAtMilliseconds: Self.nowMilliseconds)
            for event in events {
                switch event {
                case let .helloAcknowledged(recommendedAppVersionCode, updateURL):
                    helloAcknowledged = true
                    reconnectDelay = .seconds(2)
                    helloAcknowledgedAtMilliseconds = Self.nowMilliseconds
                    self.recommendedAppVersionCode = max(0, recommendedAppVersionCode ?? 0)
                    self.recommendedUpdateURL = updateURL.flatMap {
                        URL(string: $0.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                    status = .healthy
                    statusDetail = "Tracker verified"
                    flushPendingConfirmations()
                    scheduleStandbyIfEligible()
                case let .heartbeatAcknowledged(sequence, _):
                    heartbeatAcknowledgedAtMilliseconds = Self.nowMilliseconds
                    heartbeatSequenceAcknowledged = sequence
                case let .peersUpdated(updatedPeers):
                    peers = updatedPeers
                case let .reconnectRequired(reason):
                    reconnect(reason: reason)
                default:
                    break
                }
                if case let .droneConfirmed(_, confirmedByZoneID, confirmedLocally) = event,
                   confirmedLocally,
                   confirmedByZoneID == zoneID {
                    if case let .droneConfirmed(identity, _, _) = event {
                        pendingConfirmations.removeValue(forKey: identity.remoteID)
                    }
                }
                publish(event)
                scheduleStandbyIfEligible()
            }
        } catch {
            AppleLog.error("TrackerPeer", "Invalid tracker message: \(error.localizedDescription)")
        }
    }

    private func handleOrganizationConfigMessage(_ data: Data) -> Bool {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String else { return false }
        if type == "aircraft_readiness_changed" {
            Task { _ = await AppleAircraftOrganizationAccess.refresh(baseURL: AppleAircraftOrganizationAccess.scope) }
            return true
        }
        if type == "organization_config_snapshot_request" {
            let requestID = (object["requestId"] as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !requestID.isEmpty, let provider = organizationConfigSnapshotProvider else {
                return true
            }
            do {
                let response: [String: Any] = [
                    "type": "organization_config_snapshot_response",
                    "requestId": requestID,
                    "config": try provider(),
                ]
                send(try JSONSerialization.data(withJSONObject: response, options: [.sortedKeys]))
            } catch {
                AppleLog.error("OrgConfig", "Could not return organization configuration: \(error.localizedDescription)")
            }
            return true
        }
        if type == "organization_config_snapshot_ack" {
            if object["accepted"] as? Bool != true {
                AppleLog.warning("OrgConfig", "Tracker rejected organization configuration snapshot")
            }
            return true
        }
        if type == "hello_ack",
           let version = (object["organizationConfigVersionMs"] as? NSNumber)?.int64Value,
           version != 0,
           (version != Int64(defaults.integer(forKey: AppleManagedOrganizationConfig.versionDefaultsKey)) ||
            defaults.integer(forKey: "org.managedReadinessSchemaVersion") < 1) {
            organizationConfigSyncInProgress = true
            synchronizeOrganizationConfig(advertisedVersionMs: version)
        }
        return false
    }

    private func synchronizeOrganizationConfig(advertisedVersionMs: Int64) {
        guard let baseURL = URL(string: trackerURLPrefix),
              !trackerAPIKey.isEmpty,
              let applyHandler = organizationConfigApplyHandler else { return }
        let url = baseURL.appendingPathComponent("api/v1/organization-config/current")
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 60
        request.setValue(trackerAPIKey, forHTTPHeaderField: "X-SAR-Token")
        request.setValue(
            String(TrackerCoordinationClient.trackerFunctionalityRelease),
            forHTTPHeaderField: "X-R2C-Functionality-Release"
        )
        Task { @MainActor [weak self] in
            defer {
                self?.organizationConfigSyncInProgress = false
                self?.scheduleStandbyIfEligible()
            }
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse,
                      (200 ..< 300).contains(http.statusCode),
                      let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let version = (root["versionMs"] as? NSNumber)?.int64Value,
                      let snapshot = root["config"] as? [String: Any]
                else { throw URLError(.badServerResponse) }
                guard version != Int64(self?.defaults.integer(forKey: AppleManagedOrganizationConfig.versionDefaultsKey) ?? 0) else { return }
                try applyHandler(snapshot, version)
            } catch {
                AppleLog.error("OrgConfig", "Managed configuration sync failed: \(error.localizedDescription)")
            }
        }
    }

    func acknowledgeVideoStreamRequest() {
        guard videoPreflightRouteKind != nil || videoPreflightFailure != nil else {
            AppleLog.warning(
                "TrackerPeer",
                "Ignoring dismissal while video link preflight is still active"
            )
            return
        }
        clearVideoStreamRequest()
    }

    func selectVideoQuality(_ choiceID: String) {
        guard videoQualityChoices.contains(where: { $0.id == choiceID }) else {
            return
        }
        selectedVideoQualityID = choiceID
    }

    func approveVideoStreamRequest() {
        guard
            let request = pendingVideoStreamRequest,
            let choice = videoQualityChoices.first(where: {
                $0.id == selectedVideoQualityID && $0.capacity != "insufficient"
            })
        else { return }
        AppleLog.info(
            "VideoApproval",
            "Start selected request=\(request.requestId) "
                + "quality=\(choice.width)x\(choice.height)@\(choice.fps) "
                + "bitrate=\(choice.bitrateBps)"
        )
        redirectRemoteVideoStreams(to: request.requesterEmail)
        sendVideoStreamDecision(
            requestID: request.requestId,
            approved: true,
            selectedWidth: choice.width,
            selectedHeight: choice.height,
            selectedFPS: choice.fps,
            selectedBitrateBps: choice.bitrateBps
        )
        approvedVideoStreamRequests[request.requestId] = AppleApprovedVideoStream(
            request: request,
            quality: choice
        )
        mediaRouteKindByRequestID[request.requestId] = videoPreflightRouteKind ?? "unknown"
        clearVideoStreamRequest()
    }

    func declineVideoStreamRequest() {
        guard let request = pendingVideoStreamRequest else { return }
        sendVideoStreamDecision(
            requestID: request.requestId,
            approved: false,
            selectedWidth: 0,
            selectedHeight: 0,
            selectedFPS: 0,
            selectedBitrateBps: 0
        )
        clearVideoStreamRequest()
    }

    func approveRecordingDownloadRequest() {
        guard let request = pendingRecordingDownloadRequest else { return }
        approvedRecordingUploadsByRequestID[request.requestId] = request
        sendRecordingDownloadDecision(requestID: request.requestId, approved: true)
        AppleLog.info(
            "TrackerPeer",
            "Starting operator-approved recording transfer request=\(request.requestId)"
        )
        // The authenticated upload endpoint atomically authorizes an
        // awaiting-approval request before accepting its first chunk.  Do not
        // make an already-approved transfer depend on a websocket ack.
        uploadRecording(request)
        pendingRecordingRequestExpiryTask?.cancel()
        pendingRecordingRequestExpiryTask = nil
        pendingRecordingDownloadRequest = nil
    }

    func declineRecordingDownloadRequest() {
        guard let request = pendingRecordingDownloadRequest else { return }
        sendRecordingDownloadDecision(requestID: request.requestId, approved: false)
        pendingRecordingRequestExpiryTask?.cancel()
        pendingRecordingRequestExpiryTask = nil
        pendingRecordingDownloadRequest = nil
    }

    private func sendRecordingDownloadDecision(requestID: String, approved: Bool) {
        let payload: [String: Any] = [
            "type": "recording_download_decision",
            "requestId": requestID,
            "decision": approved ? "approve" : "decline",
        ]
        if let data = try? JSONSerialization.data(withJSONObject: payload) { send(data) }
    }

    private func uploadRecording(_ request: AppleRecordingDownloadRequest) {
        guard let recording = managedVideoRecordingsBySessionID[request.streamSessionId] else {
            approvedRecordingUploadsByRequestID.removeValue(forKey: request.requestId)
            AppleLog.warning("TrackerPeer", "Unable to attach requested recording \(request.streamSessionId)")
            return
        }
        guard let configured = URL(string: trackerURLPrefix),
              var components = URLComponents(url: configured, resolvingAgainstBaseURL: false)
        else {
            approvedRecordingUploadsByRequestID.removeValue(forKey: request.requestId)
            AppleLog.error("TrackerPeer", "Recording transfer URL is invalid")
            return
        }
        if components.scheme?.lowercased() == "wss" { components.scheme = "https" }
        if components.scheme?.lowercased() == "ws" { components.scheme = "http" }
        components.path = request.uploadPath
        components.query = nil
        guard let url = components.url else { return }
        let apiKey = trackerAPIKey
        let fileURL = recording.url
        let backgroundLease = AppleBackgroundTransferLease(
            name: "Recording transfer \(request.requestId)"
        )
        Task.detached(priority: .utility) { [weak self] in
            do {
                let values = try fileURL.resourceValues(forKeys: [.fileSizeKey])
                let total = Int64(values.fileSize ?? 0)
                guard total > 0 else { throw CocoaError(.fileReadCorruptFile) }
                let handle = try FileHandle(forReadingFrom: fileURL)
                defer { try? handle.close() }
                let chunkSize: Int64 = 8 * 1024 * 1024
                var start: Int64 = 0
                while start < total {
                    try handle.seek(toOffset: UInt64(start))
                    let length = Int(min(chunkSize, total - start))
                    guard let data = try handle.read(upToCount: length), data.count == length else {
                        throw CocoaError(.fileReadCorruptFile)
                    }
                    var upload = URLRequest(url: url)
                    upload.httpMethod = "PUT"
                    upload.timeoutInterval = 60 * 60
                    upload.setValue(apiKey, forHTTPHeaderField: "X-SAR-Token")
                    upload.setValue(
                        String(TrackerCoordinationClient.trackerFunctionalityRelease),
                        forHTTPHeaderField: "X-R2C-Functionality-Release"
                    )
                    upload.setValue(fileURL.lastPathComponent, forHTTPHeaderField: "X-R2C-Filename")
                    upload.setValue("video/mp4", forHTTPHeaderField: "Content-Type")
                    upload.setValue(
                        "bytes \(start)-\(start + Int64(length) - 1)/\(total)",
                        forHTTPHeaderField: "Content-Range"
                    )
                    let (_, response) = try await URLSession.shared.upload(for: upload, from: data)
                    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                    guard (200..<300).contains(code) else {
                        throw URLError(.badServerResponse, userInfo: ["status": code])
                    }
                    start += Int64(length)
                }
                AppleLog.info("TrackerPeer", "Recording transfer completed request=\(request.requestId)")
            } catch {
                AppleLog.error("TrackerPeer", "Recording transfer failed: \(error.localizedDescription)")
            }
            await MainActor.run {
                self?.approvedRecordingUploadsByRequestID.removeValue(forKey: request.requestId)
                backgroundLease.end()
            }
        }
    }

    private func sendVideoStreamDecision(
        requestID: String,
        approved: Bool,
        selectedWidth: Int,
        selectedHeight: Int,
        selectedFPS: Double,
        selectedBitrateBps: Int64
    ) {
        let payload: [String: Any] = [
            "type": "video_stream_decision",
            "requestId": requestID,
            "decision": approved ? "approve" : "decline",
            "selectedWidth": selectedWidth,
            "selectedHeight": selectedHeight,
            "selectedFps": selectedFPS,
            "selectedBitrateBps": selectedBitrateBps,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else {
            return
        }
        send(data)
    }

    private func clearVideoStreamRequest() {
        pendingVideoStreamRequest = nil
        pendingVideoRequestExpiryTask?.cancel()
        pendingVideoRequestExpiryTask = nil
        videoPreflightWatchdogTask?.cancel()
        videoPreflightWatchdogTask = nil
        videoPreflightPeer.cancel()
        resetVideoPreflightState()
    }

    private func trackerRequestExpiryDelay(_ value: String) -> TimeInterval {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let deadline = formatter.date(from: value)
            ?? ISO8601DateFormatter().date(from: value)
        guard let deadline else {
            AppleLog.warning(
                "TrackerPeer",
                "Invalid tracker request expiry '\(value)'; applying 60-second bound"
            )
            return 60
        }
        return max(0, deadline.timeIntervalSinceNow)
    }

    private func scheduleVideoRequestExpiry(_ request: AppleVideoStreamViewRequest) {
        pendingVideoRequestExpiryTask?.cancel()
        let delay = trackerRequestExpiryDelay(request.expiresAt)
        pendingVideoRequestExpiryTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: .seconds(delay))
            } catch {
                return
            }
            guard self?.pendingVideoStreamRequest?.requestId == request.requestId else {
                return
            }
            self?.clearVideoStreamRequest()
            AppleLog.info(
                "VideoApproval",
                "Video request timed out request=\(request.requestId)"
            )
        }
    }

    private func scheduleRecordingRequestExpiry(
        _ request: AppleRecordingDownloadRequest
    ) {
        pendingRecordingRequestExpiryTask?.cancel()
        let delay = trackerRequestExpiryDelay(request.expiresAt)
        pendingRecordingRequestExpiryTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: .seconds(delay))
            } catch {
                return
            }
            guard self?.pendingRecordingDownloadRequest?.requestId == request.requestId else {
                return
            }
            self?.pendingRecordingDownloadRequest = nil
            self?.pendingRecordingRequestExpiryTask = nil
            AppleLog.info(
                "TrackerPeer",
                "Recording request timed out request=\(request.requestId)"
            )
        }
    }

    private func redirectRemoteVideoStreams(to replacementRequesterEmail: String) {
        let reason = "Stream redirected to \(replacementRequesterEmail)"
        let displacedRequestIDs = approvedVideoStreamRequests.keys.sorted()
        for requestID in displacedRequestIDs {
            terminateRemoteVideoRequest(requestID: requestID, reason: reason)
        }
    }

    private func updateManagedVideoIncidentScope(_ incidentKey: String) {
        let normalizedKey = incidentKey.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        guard normalizedKey != managedVideoIncidentScopeKey else { return }
        let defaults = UserDefaults.standard
        let scopesPreference = "managedVideo.incidentStartedAtByScope"
        let migrationPreference = "managedVideo.mapScopedIncidentStartMigrationV2"
        let startsByScope = defaults.dictionary(
            forKey: scopesPreference
        ) as? [String: TimeInterval] ?? [:]
        let resolution = ManagedVideoIncidentScopePolicy.resolve(
            scopeKey: normalizedKey,
            startsByScope: startsByScope,
            migrationCompleted: defaults.bool(forKey: migrationPreference),
            now: Date()
        )
        managedVideoIncidentStartedAt = resolution.startedAt
        defaults.set(resolution.startsByScope, forKey: scopesPreference)
        defaults.set(resolution.migrationCompleted, forKey: migrationPreference)
        managedVideoRecordingsBySessionID.removeAll()
        managedVideoIncidentScopeKey = normalizedKey
    }

    func updateManagedVideoStreams(
        incidentName: String,
        incidentKey: String,
        sessions: [AppleLiveStreamSession],
        droneDesignatorProvider: (AppleLiveStreamSession) -> String = { $0.id }
    ) {
        // Stream inventory is intentionally independent of telemetry binding. A camera
        // commonly starts publishing before its drone emits Remote ID; the tablet-level
        // R2C link must expose it immediately, while track metadata is added only after
        // a matching telemetry identity becomes available.
        let live = sessions
            .filter {
                $0.state == .live &&
                $0.id != "demo"
            }
            .sorted {
                $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending
            }
            .prefix(4)
        let livePaths = Set(live.map(\.sourcePath))
        let liveSessionIDs = Set(livePaths.compactMap { managedSessionIDBySourcePath[$0] })
        let approvedSessionIDs = Set(approvedVideoStreamRequests.values.map {
            $0.request.streamSessionId
        })
        var currentSources: [String: AppleVideoFrameSource] = [:]
        managedVideoIncidentName = incidentName.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        updateManagedVideoIncidentScope(incidentKey)
        var updatedStreams = live.map { session in
            let sessionID = managedSessionIDBySourcePath[session.sourcePath]
                ?? UUID().uuidString.lowercased()
            let sourceIsReady = ManagedVideoPresencePolicy.hasRecentDecodedFrame(
                frameCount: session.model.frameCount,
                decodedFrameAge: session.model.decodedFrameAgeSeconds
            )
            managedSessionIDBySourcePath[session.sourcePath] = sessionID
            currentSources[sessionID] = session.model
            sourceEndGraceTasks.removeValue(forKey: sessionID)?.cancel()
            for (requestID, approval) in approvedVideoStreamRequests
            where approval.request.streamSessionId == sessionID {
                guard let peer = mediaPeersByRequestID[requestID] else { continue }
                if let previousSource = mediaSourcesByRequestID[requestID],
                   previousSource !== session.model {
                    previousSource.setManagedVideoFrameConsumer(nil)
                    session.model.setManagedVideoFrameConsumer(peer)
                    mediaSourcesByRequestID[requestID] = session.model
                    AppleLog.info(
                        "TrackerPeer",
                        "Reattached active media sender after decoder source replacement request=\(requestID)"
                    )
                }
            }
            return AppleManagedVideoStreamAdvertisement(
                sessionId: sessionID,
                droneDesignator: {
                    let designator = droneDesignatorProvider(session)
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    return designator.isEmpty ? session.id : designator
                }(),
                sourceWidth: sourceIsReady ? session.model.sourceWidth : 0,
                sourceHeight: sourceIsReady ? session.model.sourceHeight : 0,
                sourceFps: sourceIsReady ? session.model.sourceFrameRate : 0,
                sourceBitrateBps: 0,
                sourceCodec: sourceIsReady && session.model.sourceWidth > 0 ? "H264" : "",
                mediaKind: "live",
                recordedAt: nil,
                durationMs: 0,
                thumbnailRevision: managedVideoStreams.first(where: {
                    $0.sessionId == sessionID
                })?.thumbnailRevision ?? "",
                thumbnailJpegBase64: managedVideoStreams.first(where: {
                    $0.sessionId == sessionID
                })?.thumbnailJpegBase64
            )
        }
        // Keep an actively viewed stream advertised during the same bounded
        // decoder-recovery grace. Otherwise the tracker could cancel the
        // preserved peer after observing a transient empty advertisement.
        for sessionID in approvedSessionIDs.subtracting(liveSessionIDs) {
            if let advertisement = managedVideoStreams.first(where: {
                $0.sessionId == sessionID
            }) {
                updatedStreams.append(advertisement)
            }
            if let source = mediaSourcesByRequestID.first(where: { requestID, _ in
                approvedVideoStreamRequests[requestID]?.request.streamSessionId == sessionID
            })?.value {
                currentSources[sessionID] = source
            }
        }
        var recordings = AppleManagedVideoRecordingCatalog.snapshot(
            sessionStartedAt: managedVideoIncidentStartedAt
        )
        let recordingIDs = Set(recordings.map(\.sessionId))
        let now = Date()
        recordings += managedVideoRecordingsBySessionID.values.filter { recording in
            guard !recordingIDs.contains(recording.sessionId),
                  let values = try? recording.url.resourceValues(forKeys: [
                      .isRegularFileKey,
                      .contentModificationDateKey,
                  ]),
                  values.isRegularFile == true,
                  let modified = values.contentModificationDate
            else { return false }
            return modified >= managedVideoIncidentStartedAt
                && now.timeIntervalSince(modified) < 10
        }
        recordings.sort { $0.recordedAt > $1.recordedAt }
        recordings = Array(recordings.prefix(20))
        managedVideoRecordingsBySessionID = Dictionary(
            uniqueKeysWithValues: recordings.map { ($0.sessionId, $0) }
        )
        let unavailableApprovedSessionIDs = ManagedVideoPresencePolicy
            .unavailableApprovedSessionIDs(
                approvedSessionIDs: approvedSessionIDs,
                liveSessionIDs: liveSessionIDs,
                recordingSessionIDs: Set(managedVideoRecordingsBySessionID.keys)
            )
        for sessionID in approvedSessionIDs {
            if unavailableApprovedSessionIDs.contains(sessionID) {
                scheduleSourceEndAfterRecoveryGrace(sessionID: sessionID)
            } else {
                sourceEndGraceTasks.removeValue(forKey: sessionID)?.cancel()
            }
        }
        updatedStreams += recordings.map { recording in
            let previous = managedVideoStreams.first {
                $0.sessionId == recording.sessionId
            }
            return AppleManagedVideoStreamAdvertisement(
                sessionId: recording.sessionId,
                droneDesignator: recording.droneDesignator,
                sourceWidth: 0,
                sourceHeight: 0,
                sourceFps: 0,
                sourceBitrateBps: 0,
                sourceCodec: "h264",
                mediaKind: "recording",
                recordedAt: ISO8601DateFormatter().string(from: recording.recordedAt),
                durationMs: recording.durationMs,
                thumbnailRevision: previous?.thumbnailRevision ?? "",
                thumbnailJpegBase64: previous?.thumbnailJpegBase64
            )
        }
        updatedStreams.sort {
            $0.sessionId.localizedCaseInsensitiveCompare($1.sessionId) == .orderedAscending
        }
        managedVideoSourcesBySessionID = currentSources
        let presenceChanged = managedVideoStreams != updatedStreams
        managedVideoStreams = updatedStreams
        if hasLiveManagedVideoStream {
            wakeForCoordinationActivity(reason: "live video")
        } else {
            scheduleStandbyIfEligible()
        }
        if presenceChanged {
            sendManagedVideoPresence()
        }
        refreshManagedVideoThumbnails()
    }

    private func refreshManagedVideoThumbnails(forceSessionIDs: Set<String> = []) {
        let candidates = managedVideoStreams.filter { advertisement in
            (advertisement.thumbnailRevision.isEmpty ||
             (advertisement.mediaKind == "live" &&
              forceSessionIDs.contains(advertisement.sessionId))) &&
            managedVideoThumbnailTasks[advertisement.sessionId] == nil
        }.sorted { left, right in
            let leftIsLive = left.mediaKind == "live"
            let rightIsLive = right.mediaKind == "live"
            if leftIsLive != rightIsLive { return leftIsLive }
            return left.sessionId.localizedCaseInsensitiveCompare(right.sessionId)
                == .orderedAscending
        }
        for advertisement in candidates.prefix(8) {
            let sessionID = advertisement.sessionId
            managedVideoThumbnailTasks[sessionID] = Task { @MainActor [weak self] in
                guard let self else { return }
                let recording = self.managedVideoRecordingsBySessionID[sessionID]
                let ownedSource: AppleVideoFrameSource?
                let source: AppleVideoFrameSource?
                if let recording {
                    let playback = AppleVideoFrameSource()
                    playback.startPlayback(url: recording.url)
                    ownedSource = playback
                    source = playback
                    try? await Task.sleep(for: .milliseconds(900))
                } else {
                    ownedSource = nil
                    source = self.managedVideoSourcesBySessionID[sessionID]
                }
                defer {
                    ownedSource?.stop()
                    self.managedVideoThumbnailTasks.removeValue(forKey: sessionID)
                }
                guard let snapshot = try? await source?.captureSnapshot(),
                      let jpeg = Self.catalogThumbnailJPEG(snapshot.jpegData),
                      jpeg.count <= 256 * 1024,
                      let index = self.managedVideoStreams.firstIndex(where: {
                          $0.sessionId == sessionID
                      })
                else { return }
                // A capture timestamp guarantees a new CalTopo URL even when
                // a stationary frame compresses to identical JPEG bytes.
                let revision = String(Int64(Date().timeIntervalSince1970 * 1_000))
                self.managedVideoStreams[index] = self.managedVideoStreams[index]
                    .withThumbnail(
                        revision: revision,
                        jpegBase64: jpeg.base64EncodedString()
                    )
                let droneDesignator = self.managedVideoStreams[index].droneDesignator
                AppleLog.debug(
                    "TrackerPeer",
                    "Managed video thumbnail captured session=\(sessionID) drone=\(droneDesignator) revision=\(revision)"
                )
                self.sendManagedVideoPresence()
                self.managedVideoThumbnailUpdatedHandler?(droneDesignator)
            }
        }
    }

    private var managedVideoThumbnailRefreshDuration: Duration {
        let defaults = UserDefaults.standard
        let storedSeconds: Double? = defaults.object(
            forKey: OperationalThumbnailRefreshInterval.storageKey
        ) == nil ? nil : defaults.double(
            forKey: OperationalThumbnailRefreshInterval.storageKey
        )
        let milliseconds = Int64(
            OperationalThumbnailRefreshInterval.normalized(storedSeconds) * 1_000
        )
        return .milliseconds(milliseconds)
    }

    private func startManagedVideoThumbnailPreviewTaskIfNeeded() {
        guard managedVideoThumbnailPreviewTask == nil else { return }
        managedVideoThumbnailPreviewTask = Task { @MainActor [weak self] in
            while let self,
                  !Task.isCancelled,
                  Date() < self.managedVideoThumbnailPreviewUntil {
                if self.mediaPeersByRequestID.isEmpty {
                    let liveSessionIDs = Set(self.managedVideoStreams.compactMap {
                        $0.mediaKind == "live" ? $0.sessionId : nil
                    })
                    self.refreshManagedVideoThumbnails(forceSessionIDs: liveSessionIDs)
                }
                try? await Task.sleep(for: self.managedVideoThumbnailRefreshDuration)
            }
            self?.managedVideoThumbnailPreviewTask = nil
        }
    }

    func setCaltopoLiveTrackDesignators(_ designators: Set<String>) {
        let normalized = Set(designators.compactMap { value -> String? in
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed.lowercased()
        })
        guard normalized != caltopoLiveTrackDesignators else { return }
        caltopoLiveTrackDesignators = normalized
        caltopoThumbnailRefreshGeneration += 1
        let generation = caltopoThumbnailRefreshGeneration
        caltopoThumbnailRefreshTask?.cancel()
        caltopoThumbnailRefreshTask = nil
        guard !normalized.isEmpty else { return }
        caltopoThumbnailRefreshTask = Task { @MainActor [weak self] in
            while let self, !Task.isCancelled,
                  self.caltopoThumbnailRefreshGeneration == generation,
                  !self.caltopoLiveTrackDesignators.isEmpty {
                let sessionIDs = Set(self.managedVideoStreams.compactMap { stream -> String? in
                    guard stream.mediaKind == "live",
                          self.caltopoLiveTrackDesignators.contains(
                            stream.droneDesignator.trimmingCharacters(
                                in: .whitespacesAndNewlines
                            ).lowercased()
                          )
                    else { return nil }
                    return stream.sessionId
                })
                self.refreshManagedVideoThumbnails(forceSessionIDs: sessionIDs)
                try? await Task.sleep(for: self.managedVideoThumbnailRefreshDuration)
            }
            if self?.caltopoThumbnailRefreshGeneration == generation {
                self?.caltopoThumbnailRefreshTask = nil
            }
        }
    }

    func setManagedVideoThumbnailUpdatedHandler(_ handler: ((String) -> Void)?) {
        managedVideoThumbnailUpdatedHandler = handler
    }

    private static func catalogThumbnailJPEG(_ source: Data) -> Data? {
        guard let image = UIImage(data: source) else { return nil }
        let size = CGSize(width: 320, height: 180)
        let renderer = UIGraphicsImageRenderer(size: size)
        let rendered = renderer.image { _ in
            UIColor.black.setFill()
            UIRectFill(CGRect(origin: .zero, size: size))
            let scale = min(size.width / image.size.width, size.height / image.size.height)
            let drawn = CGSize(
                width: image.size.width * scale,
                height: image.size.height * scale
            )
            image.draw(in: CGRect(
                x: (size.width - drawn.width) / 2,
                y: (size.height - drawn.height) / 2,
                width: drawn.width,
                height: drawn.height
            ))
        }
        return rendered.jpegData(compressionQuality: 0.72)
    }

    private func scheduleSourceEndAfterRecoveryGrace(sessionID: String) {
        guard sourceEndGraceTasks[sessionID] == nil else { return }
        AppleLog.warning(
            "TrackerPeer",
            "Managed video source temporarily unavailable session=\(sessionID); preserving active WebRTC for decoder recovery"
        )
        sourceEndGraceTasks[sessionID] = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: .seconds(12))
            } catch {
                return
            }
            guard let self else { return }
            self.sourceEndGraceTasks.removeValue(forKey: sessionID)
            let endedRequestIDs = self.approvedVideoStreamRequests.compactMap {
                requestID, approval in
                approval.request.streamSessionId == sessionID ? requestID : nil
            }
            for requestID in endedRequestIDs {
                self.terminateRemoteVideoRequest(requestID: requestID, reason: "source_ended")
            }
            self.managedVideoStreams.removeAll { $0.sessionId == sessionID }
            self.managedVideoSourcesBySessionID.removeValue(forKey: sessionID)
            self.sendManagedVideoPresence()
        }
    }

    private func handleManagedVideoMessage(_ data: Data) -> Bool {
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let type = object["type"] as? String
        else {
            return false
        }
        let resetsStandaloneTimer = [
            "video_preflight_offer",
            "video_media_offer",
            "recording_download_request",
            "recording_download_decision_ack",
            "video_stream_request_cancelled",
            "video_stream_request",
        ].contains(type)
        if resetsStandaloneTimer {
            standbyTask?.cancel()
            standbyTask = nil
        }
        defer {
            if resetsStandaloneTimer {
                scheduleStandbyIfEligible()
            }
        }
        if type == "video_preflight_offer" {
            handleVideoPreflightOffer(data)
            return true
        }
        if type == "video_thumbnail_preview" {
            let ttlSeconds = max(10, min(object["ttlSec"] as? Int ?? 25, 60))
            managedVideoThumbnailPreviewUntil = max(
                managedVideoThumbnailPreviewUntil,
                Date().addingTimeInterval(TimeInterval(ttlSeconds))
            )
            startManagedVideoThumbnailPreviewTaskIfNeeded()
            return true
        }
        if type == "video_media_offer" {
            handleVideoMediaOffer(data)
            return true
        }
        if type == "recording_download_request" {
            do {
                let request = try JSONDecoder().decode(AppleRecordingDownloadRequest.self, from: data)
                guard managedVideoRecordingsBySessionID[request.streamSessionId] != nil else {
                    AppleLog.warning("TrackerPeer", "Requested recording is unavailable")
                    return true
                }
                if request.consentRequired {
                    pendingRecordingDownloadRequest = request
                    scheduleRecordingRequestExpiry(request)
                    AppleSpokenWarningCenter.shared.speak(
                        "Recording Download Request from, " + Self.spokenEmailAddress(request.requesterEmail)
                    )
                } else {
                    uploadRecording(request)
                }
            } catch {
                AppleLog.error("TrackerPeer", "Invalid recording download request")
            }
            return true
        }
        if type == "recording_download_decision_ack" {
            let requestID = (object["requestId"] as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard let request = approvedRecordingUploadsByRequestID.removeValue(forKey: requestID) else {
                return true
            }
            guard object["accepted"] as? Bool == true else {
                AppleLog.warning(
                    "TrackerPeer",
                    "Tracker rejected recording transfer request=\(requestID) error=\(object["error"] as? String ?? "unknown")"
                )
                return true
            }
            AppleLog.info("TrackerPeer", "Tracker confirmed recording transfer authorization request=\(requestID)")
            return true
        }
        if type == "video_stream_request_cancelled" {
            let requestID = (object["requestId"] as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            lastVideoPreflightOfferByRequestID.removeValue(forKey: requestID)
            approvedVideoStreamRequests.removeValue(forKey: requestID)
            remoteControlledVideoRequests.removeValue(forKey: requestID)
            stopLocalMediaSession(requestID: requestID)
            if pendingVideoStreamRequest?.requestId == requestID {
                clearVideoStreamRequest()
                AppleLog.info(
                    "TrackerPeer",
                    "Video request cancelled by requester id=\(requestID)"
                )
            }
            return true
        }
        if type == "video_stream_advertisement_ack" {
            let accepted = object["accepted"] as? Bool ?? false
            let sessionIDs = object["sessionIds"] as? [String] ?? []
            if accepted {
                AppleLog.debug(
                    "TrackerPeer",
                    "Managed video presence accepted sessions=\(sessionIDs.count)"
                )
            } else {
                let error = object["error"] as? String ?? "Tracker rejected presence"
                AppleLog.warning("TrackerPeer", "Managed video presence rejected: \(error)")
            }
            return true
        }
        guard type == "video_stream_request" else { return false }
        do {
            let request = try JSONDecoder().decode(
                AppleVideoStreamViewRequest.self,
                from: data
            )
            guard
                !request.requestId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                !request.requesterEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                !request.streamSessionId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                AppleLog.warning("TrackerPeer", "Ignoring incomplete video stream request")
                return true
            }
            if seenVideoStreamRequestIDs.contains(request.requestId) {
                AppleLog.debug(
                    "TrackerPeer",
                    "Ignoring replayed video stream request \(request.requestId)"
                )
                return true
            }
            seenVideoStreamRequestIDs.append(request.requestId)
            if seenVideoStreamRequestIDs.count > 50 {
                seenVideoStreamRequestIDs.removeFirst(
                    seenVideoStreamRequestIDs.count - 50
                )
            }
            guard managedVideoStreams.contains(where: {
                $0.sessionId == request.streamSessionId
            }), (
                managedVideoSourcesBySessionID[request.streamSessionId] != nil ||
                managedVideoRecordingsBySessionID[request.streamSessionId] != nil
            )
            else {
                sendVideoStreamUnavailable(
                    requestID: request.requestId,
                    streamSessionID: request.streamSessionId
                )
                AppleLog.warning(
                    "TrackerPeer",
                    "Rejected video request id=\(request.requestId) error=e_nosuch_stream session=\(request.streamSessionId)"
                )
                return true
            }
            if !request.consentRequired {
                remoteControlledVideoRequests[request.requestId] = request
                AppleLog.info(
                    "VideoApproval",
                    "Remote-controlled request=\(request.requestId); requester selects quality"
                )
                return true
            }
            pendingVideoStreamRequest = request
            scheduleVideoRequestExpiry(request)
            videoPreflightPeer.cancel()
            resetVideoPreflightState()
            startVideoPreflightWatchdog(requestID: request.requestId)
            AppleSpokenWarningCenter.shared.speak(
                "Video Stream Request from, "
                    + Self.spokenEmailAddress(request.requesterEmail)
            )
            AppleLog.info(
                "VideoApproval",
                "Preparing routed request=\(request.requestId); confirmation deferred"
            )
            AppleLog.info(
                "TrackerPeer",
                "Video request id=\(request.requestId) incident='\(request.incidentName)' drone='\(request.droneDesignator)'"
            )
        } catch {
            AppleLog.error(
                "TrackerPeer",
                "Invalid video stream request: \(error.localizedDescription)"
            )
        }
        return true
    }

    private func sendVideoStreamUnavailable(
        requestID: String,
        streamSessionID: String
    ) {
        let payload: [String: Any] = [
            "type": "video_stream_unavailable",
            "requestId": requestID,
            "streamSessionId": streamSessionID,
            "errorCode": "e_nosuch_stream",
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else {
            return
        }
        send(data)
    }

    private func handleVideoMediaOffer(_ data: Data) {
        do {
            let offer = try JSONDecoder().decode(AppleVideoMediaOffer.self, from: data)
            let offerDecision = ManagedVideoMediaOfferPolicy.decision(
                activeOfferSDP: mediaOfferSDPByRequestID[offer.requestId],
                incomingOfferSDP: offer.sdp
            )
            if offerDecision == .ignoreReplay {
                AppleLog.info(
                    "TrackerPeer",
                    "Ignoring replayed media offer for active request=\(offer.requestId)"
                )
                return
            }
            if offerDecision == .replaceActivePeer {
                AppleLog.info(
                    "TrackerPeer",
                    "Replacing media peer after browser reconnect request=\(offer.requestId)"
                )
            }
            let remoteApproval = remoteControlledVideoRequests[offer.requestId].flatMap {
                request -> AppleApprovedVideoStream? in
                let width = offer.selectedWidth ?? 0
                let height = offer.selectedHeight ?? 0
                let fps = offer.selectedFps ?? 0
                let bitrateBps = offer.selectedBitrateBps ?? 0
                guard request.streamSessionId == offer.streamSessionId,
                      width > 0, height > 0, fps > 0, bitrateBps > 0
                else { return nil }
                return AppleApprovedVideoStream(
                    request: request,
                    quality: AppleVideoQualityChoice(
                        id: "requester-selected",
                        label: "Requester selected",
                        width: width,
                        height: height,
                        fps: fps,
                        bitrateBps: bitrateBps,
                        capacity: "enough"
                    )
                )
            }
            guard let approval = approvedVideoStreamRequests[offer.requestId]
                    ?? remoteApproval,
                  approval.request.streamSessionId == offer.streamSessionId
            else {
                AppleLog.warning(
                    "TrackerPeer",
                    "Ignoring media offer without matching pilot approval request=\(offer.requestId)"
                )
                return
            }
            let recording = managedVideoRecordingsBySessionID[offer.streamSessionId]
            let liveSource = recording == nil
                ? managedVideoSourcesBySessionID[offer.streamSessionId]
                : nil
            guard recording != nil || liveSource != nil else {
                AppleLog.warning(
                    "TrackerPeer",
                    "Approved media source disappeared request=\(offer.requestId)"
                )
                return
            }
            // Remote Control skips the on-device approval sheet, so retain the
            // synthesized approval once media starts. This keeps requester
            // attribution available to the Stream Tile for the session's life.
            approvedVideoStreamRequests[offer.requestId] = approval
            let approvedRoute = mediaRouteKindByRequestID[offer.requestId]
                ?? offer.routeKind
                ?? "unknown"
            stopLocalMediaSession(requestID: offer.requestId)
            // A recording source is owned by one media attempt and is stopped
            // when that attempt is replaced. Always open a fresh decoder for
            // browser reconnects instead of reusing the just-stopped source
            // retained briefly by the presence snapshot.
            let source: AppleVideoFrameSource
            if let recording {
                let playback = AppleVideoFrameSource()
                playback.startPlayback(url: recording.url)
                source = playback
            } else if let liveSource {
                source = liveSource
            } else {
                return
            }
            let mediaAttemptID = UUID()
            let peer = AppleManagedVideoMediaPeer(
                answerSink: { [weak self] requestID, sdp in
                    Task { @MainActor [weak self] in
                        guard let self,
                              self.mediaAttemptIDByRequestID[requestID] == mediaAttemptID
                        else { return }
                        self.sendVideoMediaAnswer(requestID: requestID, sdp: sdp)
                    }
                },
                metricsSink: { [weak self] requestID, metrics in
                    Task { @MainActor [weak self] in
                        guard let self,
                              self.mediaAttemptIDByRequestID[requestID] == mediaAttemptID
                        else { return }
                        self.recordRemoteVideoMetrics(requestID: requestID, metrics: metrics)
                    }
                },
                failureSink: { [weak self] requestID, reason in
                    Task { @MainActor [weak self] in
                        guard let self,
                              self.mediaAttemptIDByRequestID[requestID] == mediaAttemptID
                        else { return }
                        self.terminateRemoteVideoRequest(requestID: requestID, reason: reason)
                    }
                },
                microphoneStateSink: { [weak self] requestID, enabled, error in
                    Task { @MainActor [weak self] in
                        guard let self,
                              self.mediaAttemptIDByRequestID[requestID] == mediaAttemptID
                        else { return }
                        self.remoteVideoMicrophoneEnabled = enabled
                        self.remoteVideoMicrophoneError = error
                    }
                }
            )
            mediaPeersByRequestID[offer.requestId] = peer
            mediaOfferSDPByRequestID[offer.requestId] = offer.sdp
            mediaAttemptIDByRequestID[offer.requestId] = mediaAttemptID
            mediaSourcesByRequestID[offer.requestId] = source
            if recording != nil {
                managedVideoRecordingSourceRequestIDs.insert(offer.requestId)
            }
            mediaRouteKindByRequestID[offer.requestId] = approvedRoute
            remoteControlledVideoRequests.removeValue(forKey: offer.requestId)
            source.setManagedVideoFrameConsumer(peer)
            AppleSpokenWarningCenter.shared.speak(
                "Now sharing video stream with "
                    + Self.spokenEmailAddress(approval.request.requesterEmail)
            )
            peer.start(
                requestID: offer.requestId,
                offerSDP: offer.sdp,
                iceServers: offer.iceServers,
                width: approval.quality.width,
                height: approval.quality.height,
                fps: approval.quality.fps,
                bitrateBps: approval.quality.bitrateBps
            )
            AppleLog.info(
                "TrackerPeer",
                "App-owned media sender starting request=\(offer.requestId) "
                    + "quality=\(approval.quality.width)x\(approval.quality.height) "
                    + "fps=\(approval.quality.fps) bitrate=\(approval.quality.bitrateBps)"
            )
        } catch {
            AppleLog.error("TrackerPeer", "Invalid video media offer: \(error.localizedDescription)")
        }
    }

    private func sendVideoMediaAnswer(requestID: String, sdp: String) {
        let payload: [String: Any] = [
            "type": "video_media_answer",
            "requestId": requestID,
            "sdp": sdp,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        send(data)
    }

    private func stopLocalMediaSession(requestID: String) {
        let peer = mediaPeersByRequestID.removeValue(forKey: requestID)
        mediaOfferSDPByRequestID.removeValue(forKey: requestID)
        mediaAttemptIDByRequestID.removeValue(forKey: requestID)
        let source = mediaSourcesByRequestID.removeValue(forKey: requestID)
        source?.setManagedVideoFrameConsumer(nil)
        if managedVideoRecordingSourceRequestIDs.remove(requestID) != nil {
            source?.stop()
        }
        peer?.close()
        mediaRouteKindByRequestID.removeValue(forKey: requestID)
        activeRemoteVideoConnectionCount = mediaPeersByRequestID.count
        if mediaPeersByRequestID.isEmpty {
            remoteVideoMicrophoneEnabled = false
            remoteVideoMicrophoneError = nil
            remoteVideoAudioBytesSent = 0
            remoteVideoAudioBytesReceived = 0
            remoteVideoBytesSent = 0
            remoteVideoEffectiveWidth = 0
            remoteVideoEffectiveHeight = 0
            remoteVideoEffectiveFPS = 0
            remoteVideoEffectiveBitrateBps = 0
        }
    }

    func terminateAllRemoteVideoStreams() {
        for requestID in Array(mediaPeersByRequestID.keys) {
            terminateRemoteVideoRequest(requestID: requestID, reason: "operator_terminated")
        }
    }

    func shutdown() async {
        terminateAllRemoteVideoStreams()
        managedVideoThumbnailPreviewTask?.cancel()
        managedVideoThumbnailPreviewTask = nil
        managedVideoThumbnailPreviewUntil = .distantPast
        caltopoThumbnailRefreshTask?.cancel()
        caltopoThumbnailRefreshTask = nil
        caltopoThumbnailRefreshGeneration += 1
        caltopoLiveTrackDesignators.removeAll()
        for task in sourceEndGraceTasks.values { task.cancel() }
        sourceEndGraceTasks.removeAll()
        for task in managedVideoThumbnailTasks.values { task.cancel() }
        managedVideoThumbnailTasks.removeAll()
        managedVideoStreams = []
        managedVideoSourcesBySessionID.removeAll()
        managedSessionIDBySourcePath.removeAll()
        sendManagedVideoPresence()
        if connected {
            try? await Task.sleep(for: .milliseconds(150))
        }
        stopTransport()
        status = .unconfigured
        statusDetail = "Application session closed"
    }

    func toggleRemoteVideoMicrophone() {
        guard !mediaPeersByRequestID.isEmpty else { return }
        let enabled = !remoteVideoMicrophoneEnabled
        remoteVideoMicrophoneError = nil
        for peer in mediaPeersByRequestID.values {
            peer.setMicrophoneEnabled(enabled)
        }
    }

    private func terminateRemoteVideoRequest(requestID: String, reason: String) {
        stopLocalMediaSession(requestID: requestID)
        approvedVideoStreamRequests.removeValue(forKey: requestID)
        let payload: [String: Any] = [
            "type": "video_stream_terminated",
            "requestId": requestID,
            "reason": reason,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        send(data)
        AppleLog.info("TrackerPeer", "Remote video terminated request=\(requestID) reason=\(reason)")
    }

    private func recordRemoteVideoMetrics(
        requestID: String,
        metrics: AppleManagedVideoMediaMetrics
    ) {
        guard mediaPeersByRequestID[requestID] != nil else { return }
        activeRemoteVideoConnectionCount = metrics.connected ? mediaPeersByRequestID.count : 0
        remoteVideoBytesSent = metrics.bytesSent
        remoteVideoEffectiveWidth = metrics.width
        remoteVideoEffectiveHeight = metrics.height
        remoteVideoEffectiveFPS = metrics.framesPerSecond
        remoteVideoEffectiveBitrateBps = metrics.bitrateBps
        remoteVideoAudioBytesSent = metrics.audioBytesSent
        remoteVideoAudioBytesReceived = metrics.audioBytesReceived
        mediaRouteKindByRequestID[requestID] = metrics.routeKind
    }

    private func handleVideoPreflightOffer(_ data: Data) {
        do {
            let offer = try JSONDecoder().decode(
                AppleVideoPreflightOffer.self,
                from: data
            )
            guard
                !offer.requestId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                !offer.sdp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                AppleLog.warning("TrackerPeer", "Ignoring incomplete video preflight offer")
                return
            }
            guard ManagedVideoPreflightRequestPolicy.shouldAcceptOffer(
                requestID: offer.requestId,
                pendingOperatorRequestID: pendingVideoStreamRequest?.requestId,
                remoteControlledRequestIDs: Set(remoteControlledVideoRequests.keys)
            ) else {
                AppleLog.warning(
                    "TrackerPeer",
                    "Ignoring video preflight offer without matching request="
                        + offer.requestId
                )
                return
            }
            if lastVideoPreflightOfferByRequestID[offer.requestId] == offer.sdp {
                AppleLog.debug(
                    "TrackerPeer",
                    "Ignoring replayed video preflight offer request=\(offer.requestId)"
                )
                return
            }
            lastVideoPreflightOfferByRequestID[offer.requestId] = offer.sdp
            if lastVideoPreflightOfferByRequestID.count > 32,
               let oldestRequestID = lastVideoPreflightOfferByRequestID.keys.first {
                lastVideoPreflightOfferByRequestID.removeValue(forKey: oldestRequestID)
            }
            if let pendingVideoStreamRequest,
               pendingVideoStreamRequest.requestId == offer.requestId {
                guard videoPreflightFailure == nil else {
                    AppleLog.warning(
                        "TrackerPeer",
                        "Ignoring late video preflight offer request=\(offer.requestId)"
                    )
                    return
                }
                resetVideoPreflightState()
            }
            videoPreflightPeer.start(
                requestID: offer.requestId,
                offerSDP: offer.sdp,
                iceServers: offer.iceServers
            )
            AppleLog.info(
                "TrackerPeer",
                "Starting consent-safe video link preflight request=\(offer.requestId)"
            )
        } catch {
            AppleLog.error(
                "TrackerPeer",
                "Invalid video preflight offer: \(error.localizedDescription)"
            )
        }
    }

    private func sendVideoPreflightAnswer(requestID: String, sdp: String) {
        let payload: [String: Any] = [
            "type": "video_preflight_answer",
            "requestId": requestID,
            "sdp": sdp,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else {
            return
        }
        send(data)
    }

    private func recordVideoPreflightResult(
        requestID: String,
        routeKind: String,
        estimatedUplinkBps: Int64
    ) {
        videoPreflightWatchdogTask?.cancel()
        videoPreflightWatchdogTask = nil
        if pendingVideoStreamRequest?.requestId == requestID {
            videoPreflightRouteKind = routeKind
            videoPreflightEstimatedUplinkBps = estimatedUplinkBps
            videoPreflightFailure = nil
            selectedVideoQualityID = videoQualityChoices.first(where: {
                $0.capacity != "insufficient"
            })?.id
            AppleLog.info(
                "VideoApproval",
                "Approval ready request=\(requestID) route=\(routeKind) "
                    + "usableBps=\(estimatedUplinkBps) choices=\(videoQualityChoices.count) "
                    + "selected=\(selectedVideoQualityID ?? "none")"
            )
        }
        let payload: [String: Any] = [
            "type": "video_preflight_result",
            "requestId": requestID,
            "routeKind": routeKind,
            "estimatedUplinkBps": estimatedUplinkBps,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else {
            return
        }
        send(data)
        AppleLog.info(
            "TrackerPeer",
            "Video preflight request=\(requestID) route=\(routeKind) usableBps=\(estimatedUplinkBps)"
        )
    }

    private func recordVideoPreflightFailure(requestID: String, reason: String) {
        videoPreflightWatchdogTask?.cancel()
        videoPreflightWatchdogTask = nil
        if pendingVideoStreamRequest?.requestId == requestID {
            videoPreflightFailure = reason
            videoPreflightRouteKind = nil
            videoPreflightEstimatedUplinkBps = nil
            selectedVideoQualityID = nil
        }
        AppleLog.warning(
            "TrackerPeer",
            "Video preflight failed request=\(requestID): \(reason)"
        )
    }

    private func resetVideoPreflightState() {
        videoPreflightRouteKind = nil
        videoPreflightEstimatedUplinkBps = nil
        videoPreflightFailure = nil
        selectedVideoQualityID = nil
    }

    private func startVideoPreflightWatchdog(requestID: String) {
        videoPreflightWatchdogTask?.cancel()
        videoPreflightWatchdogTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard
                !Task.isCancelled,
                let self,
                self.pendingVideoStreamRequest?.requestId == requestID,
                self.videoPreflightRouteKind == nil,
                self.videoPreflightFailure == nil
            else { return }
            self.videoPreflightPeer.cancel()
            self.recordVideoPreflightFailure(
                requestID: requestID,
                reason: "Link measurement did not start or complete within 12 seconds."
            )
        }
    }

    static func spokenEmailAddress(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).map { character in
            switch character {
            case "@": return "at"
            case ".": return "dot"
            case "-": return "dash"
            case "_": return "underscore"
            case "+": return "plus"
            default: return String(character).uppercased()
            }
        }.joined(separator: " ")
    }

    private func startLivenessTasks(generation: UUID) {
        heartbeatTask?.cancel()
        watchdogTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                guard !Task.isCancelled, let self, generation == self.generation, self.connected else { return }
                let sequence = self.protocolState.nextHeartbeatSequence(sentAtMilliseconds: Self.nowMilliseconds)
                if let data = try? TrackerCoordinationWire.heartbeat(
                    client: self.client,
                    position: self.position,
                    sequence: sequence
                ) {
                    self.send(data)
                }
                self.sendManagedVideoPresence()
            }
        }
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled, let self, generation == self.generation, self.connected else { return }
                if let reason = self.protocolState.requiresReconnectForMissingAcknowledgement(
                    nowMilliseconds: Self.nowMilliseconds
                ) {
                    self.reconnect(reason: reason)
                    return
                }
            }
        }
    }

    private func socketClosed(generation: UUID, detail: String) {
        guard generation == self.generation, coordinationRequired else { return }
        receiveFailureTask?.cancel()
        receiveFailureTask = nil
        connected = false
        helloAcknowledged = false
        heartbeatTask?.cancel()
        watchdogTask?.cancel()
        status = .degraded
        statusDetail = detail
        AppleLog.error("TrackerPeer", detail)
        scheduleReconnect()
    }

    private func socketFailed(generation: UUID, responseCode: Int?, detail: String) {
        guard generation == self.generation, coordinationRequired else { return }
        if responseCode == 401 || responseCode == 403 {
            trackerKnownUnavailable = true
            stopSocketOnly()
            status = .unavailable
            statusDetail = Self.reenrollmentRequiredStatusDetail
            AppleLog.error("TrackerPeer", statusDetail)
            recoverReauthenticationChallenge()
            return
        }
        socketClosed(generation: generation, detail: detail)
    }

    private func reconnect(reason: String) {
        guard coordinationRequired else { return }
        status = .degraded
        statusDetail = reason
        stopSocketOnly()
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        guard coordinationRequired, reconnectTask == nil else { return }
        let delay = reconnectDelay
        reconnectDelay = min(reconnectDelay * 2, .seconds(10))
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            self.reconnectTask = nil
            self.connect()
        }
    }

    private func recoverReauthenticationChallenge() {
        authorizationRecoveryTask?.cancel()
        guard let baseURL = URL(string: trackerURLPrefix), !trackerAPIKey.isEmpty else { return }
        let url = baseURL.appendingPathComponent("api/v1/organization-config/current")
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 20
        request.setValue(trackerAPIKey, forHTTPHeaderField: "X-SAR-Token")
        request.setValue(
            String(TrackerCoordinationClient.trackerFunctionalityRelease),
            forHTTPHeaderField: "X-R2C-Functionality-Release"
        )
        authorizationRecoveryTask = Task { @MainActor [weak self] in
            defer { self?.authorizationRecoveryTask = nil }
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                guard !Task.isCancelled,
                      let http = response as? HTTPURLResponse,
                      let url = TrackerReauthenticationChallenge.url(
                          fromHTTPError: data,
                          statusCode: http.statusCode
                      )
                else { return }
                self?.requireReauthentication(at: url)
                AppleLog.info(
                    "TrackerPeer",
                    "Recovered reauthentication challenge after HTTP \(http.statusCode) WebSocket rejection"
                )
            } catch {
                guard !Task.isCancelled else { return }
                AppleLog.error(
                    "TrackerPeer",
                    "Reauthentication challenge recovery failed: \(error.localizedDescription)"
                )
            }
        }
    }

    private func scheduleFallbackOwnership(remoteID: String) {
        fallbackTasks[remoteID]?.cancel()
        fallbackTasks[remoteID] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard !Task.isCancelled, let self,
                  self.observedSightings[remoteID] != nil,
                  !self.helloAcknowledged
            else { return }
            if let event = self.protocolState.assignFallbackOwnership(remoteID: remoteID) {
                self.publish(event)
                AppleLog.info("TrackerPeer", "Fallback local ownership remoteId=\(remoteID)")
            }
        }
    }

    private func sendFirstSightingIfNeeded(remoteID: String) {
        guard connected, !firstSightingsSent.contains(remoteID),
              let sighting = observedSightings[remoteID],
              let data = try? TrackerCoordinationWire.firstSighting(client: client, sighting: sighting)
        else { return }
        send(data)
        firstSightingsSent.insert(remoteID)
    }

    private func replayFirstSightings() {
        for remoteID in observedSightings.keys.sorted() { sendFirstSightingIfNeeded(remoteID: remoteID) }
    }

    private func sendSightingIfEligible(_ sighting: TrackerCoordinationSighting) {
        guard connected else { return }
        let remoteID = sighting.identity.remoteID
        if !protocolState.isLocalOwner(remoteID: remoteID),
           let lastSent = lastSightingSentAt[remoteID],
           Date().timeIntervalSince(lastSent) < 3 {
            return
        }
        guard let data = try? TrackerCoordinationWire.sighting(client: client, sighting: sighting) else { return }
        send(data)
        lastSightingSentAt[remoteID] = Date()
    }

    func observeSEITraffic(
        remoteID: String,
        mappedID: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
        relativeUpMeters: Double?,
        headingDegrees: Double?,
        sampledAt: Date,
        altitudeSampledAt: Date
    ) {
        guard coordinationRequired else { return }
        let identity = observedSightings[remoteID]?.identity ?? TrackerCoordinationIdentity(
            remoteID: remoteID,
            mappedID: mappedID,
            organization: "",
            model: "",
            ownerName: ""
        )
        let calibration = trafficAltitudeCalibrationByRemoteID[remoteID]
        let seiReportedAltitude = calibration?.reportedAltitudeMeters(
            relativeUpMeters: relativeUpMeters
        )
        sendTrafficPositionIfEligible(
            TrackerCoordinationSighting(
                identity: identity,
                droneTimestampMilliseconds: Int64(sampledAt.timeIntervalSince1970 * 1_000),
                latitude: latitude,
                longitude: longitude,
                altitudeMeters: seiReportedAltitude ?? altitudeMeters,
                headingDegrees: headingDegrees
            ),
            source: "sei",
            altitudeSampleTimestampMilliseconds: Int64(
                (seiReportedAltitude == nil ? altitudeSampledAt : sampledAt)
                    .timeIntervalSince1970 * 1_000
            )
        )
    }

    private func sendTrafficPositionIfEligible(
        _ sighting: TrackerCoordinationSighting,
        source: String,
        altitudeSampleTimestampMilliseconds: Int64? = nil
    ) {
        guard connected else { return }
        let key = "\(source)|\(sighting.identity.remoteID)"
        let now = Date()
        let sendInterval = trafficSendIntervalByKey[key] ?? 1
        if let lastSent = lastTrafficSentAtByKey[key], now.timeIntervalSince(lastSent) < sendInterval {
            return
        }
        let sequence = (trafficSequenceByKey[key] ?? 0) + 1
        guard let data = try? TrackerCoordinationWire.trafficPosition(
            client: client,
            sighting: sighting,
            source: source,
            sourceEpoch: trafficSourceEpoch,
            sequence: sequence,
            altitudeSampleTimestampMilliseconds: altitudeSampleTimestampMilliseconds,
            altitudeCalibration: trafficAltitudeCalibrationByRemoteID[sighting.identity.remoteID],
            proximityAlertDistanceFeet: proximityAlertDistanceFeet
        ) else { return }
        send(data)
        trafficSequenceByKey[key] = sequence
        lastTrafficSentAtByKey[key] = now
        let calibration = trafficAltitudeCalibrationByRemoteID[sighting.identity.remoteID]
        let mslAltitude = calibration?.normalizedMSLMeters(rawAltitudeMeters: sighting.altitudeMeters)
        let altitudeSample = altitudeSampleTimestampMilliseconds ?? sighting.droneTimestampMilliseconds
        let altitudeState = calibration?.state ?? "unconfirmed"
        let mslAltitudeText = mslAltitude.map { String($0) } ?? "null"
        let mslAltitudeAgeText = mslAltitude == nil
            ? "null"
            : String(max(0, Self.nowMilliseconds - altitudeSample))
        let correctionText = calibration?.correctionMeters.map { String($0) } ?? "null"
        let flightEpochText = calibration?.flightEpoch ?? "null"
        guard lastTrafficSentLogAtByKey[key].map({ now.timeIntervalSince($0) >= 5 }) ?? true else {
            return
        }
        lastTrafficSentLogAtByKey[key] = now
        let headingText = sighting.headingDegrees.map { String($0) } ?? "null"
        let speedText = sighting.groundSpeedKnots.map { String($0) } ?? "null"
        AppleLog.info(
            "TrackerPeer",
            "Peer traffic sent remoteId=\(sighting.identity.remoteID) source=\(source) seq=\(sequence) sourceAgeMs=\(max(0, Self.nowMilliseconds - sighting.droneTimestampMilliseconds)) headingDeg=\(headingText) speedKt=\(speedText) altAgeMs=\(max(0, Self.nowMilliseconds - altitudeSample)) altState=\(altitudeState) mslAltM=\(mslAltitudeText) mslAltAgeMs=\(mslAltitudeAgeText) correctionM=\(correctionText) flightEpoch=\(flightEpochText) intervalMs=\(Int64(sendInterval * 1_000))"
        )
    }

    private func flushPendingConfirmations() {
        guard connected else { return }
        for (remoteID, identity) in pendingConfirmations {
            guard let data = try? TrackerCoordinationWire.droneConfirmed(client: client, identity: identity) else { continue }
            send(data)
            AppleLog.info("TrackerPeer", "Sent pending drone confirmation remoteId=\(remoteID)")
        }
    }

    private func send(_ data: Data) {
        guard connected, let socket, let text = String(data: data, encoding: .utf8) else { return }
        let currentGeneration = generation
        socket.send(.string(text)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor [weak self] in
                guard let self, currentGeneration == self.generation else { return }
                self.socketClosed(
                    generation: currentGeneration,
                    detail: "Send failed: \(Self.errorDescription(error))"
                )
            }
        }
    }

    private func sendManagedVideoPresence() {
        guard
            connected,
            !managedVideoIncidentName.isEmpty
        else {
            return
        }
        let payload: [String: Any] = [
            "type": "video_stream_advertisement",
            "remoteControlEnabled": UserDefaults.standard.bool(
                forKey: "video.remoteControlEnabled"
            ),
            "incidentName": managedVideoIncidentName,
            "deviceName": AppleDeviceIdentity.displayName,
            "timeZone": TimeZone.current.identifier,
            "streams": managedVideoStreams.map {
                let source = managedVideoSourcesBySessionID[$0.sessionId]
                let width = source?.sourceWidth ?? $0.sourceWidth
                let height = source?.sourceHeight ?? $0.sourceHeight
                let frameRate = source?.sourceFrameRate ?? $0.sourceFps
                var advertised = [
                    "sessionId": $0.sessionId,
                    "droneDesignator": $0.droneDesignator,
                    "sourceWidth": width,
                    "sourceHeight": height,
                    "sourceFps": frameRate,
                    "sourceBitrateBps": $0.sourceBitrateBps,
                    "sourceCodec": width > 0 ? "H264" : $0.sourceCodec,
                    "mediaKind": $0.mediaKind,
                    "durationMs": $0.durationMs,
                    "thumbnailRevision": $0.thumbnailRevision,
                ] as [String: Any]
                if let recordedAt = $0.recordedAt {
                    advertised["recordedAt"] = recordedAt
                }
                if let thumbnailJpegBase64 = $0.thumbnailJpegBase64 {
                    advertised["thumbnailJpegBase64"] = thumbnailJpegBase64
                }
                return advertised
            },
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else {
            return
        }
        send(data)
    }

    func caltopoCameraMetadata(droneDesignator: String) -> CaltopoCameraMetadata? {
        let normalized = droneDesignator.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty,
              let stream = managedVideoStreams.first(where: {
                  $0.mediaKind == "live" &&
                  $0.droneDesignator.trimmingCharacters(in: .whitespacesAndNewlines)
                      .localizedCaseInsensitiveCompare(normalized) == .orderedSame
              }),
              let tabletURL = TrackerTabletLink.shortURL(
                  trackerURLPrefix: trackerURLPrefix,
                  tabletName: AppleDeviceIdentity.displayName
              )
        else { return nil }
        let thumbnailURL = stream.thumbnailRevision.isEmpty ? nil :
            TrackerTabletLink.thumbnailURL(
                trackerURLPrefix: trackerURLPrefix,
                tabletName: AppleDeviceIdentity.displayName,
                streamSessionID: stream.sessionId,
                thumbnailRevision: stream.thumbnailRevision
            )
        if let thumbnailURL,
           let timestamp = URLComponents(url: thumbnailURL, resolvingAgainstBaseURL: false)?
               .queryItems?.first(where: { $0.name == "timestamp" })?.value {
            AppleLog.debug(
                "CalTopo",
                "positionReport(GET) camera:thumbnail_url=present(scheme=https,host=tracker,cacheBust=timestamp:\(timestamp))"
            )
        }
        let cameraTelemetry = managedVideoSourcesBySessionID[stream.sessionId]?
            .freshDJICameraTelemetry()
        return CaltopoCameraMetadata(
            externalURL: tabletURL,
            thumbnailURL: thumbnailURL,
            azimuthDegrees: cameraTelemetry?.cameraAzimuthDegrees,
            tiltDegrees: cameraTelemetry?.tiltDegrees,
            horizontalFovDegrees: cameraTelemetry?.horizontalFovDegrees,
            verticalFovDegrees: cameraTelemetry?.verticalFovDegrees
        )
    }

    func djiCameraTelemetry(droneDesignator: String) -> AppleDJICameraTelemetry? {
        let normalized = droneDesignator.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty,
              let stream = managedVideoStreams.first(where: {
                  $0.mediaKind == "live" &&
                  $0.droneDesignator.trimmingCharacters(in: .whitespacesAndNewlines)
                      .localizedCaseInsensitiveCompare(normalized) == .orderedSame
              })
        else { return nil }
        return managedVideoSourcesBySessionID[stream.sessionId]?.freshDJICameraTelemetry()
    }

    func capturedVideoURL(
        matching designators: [String],
        trackStartedAt: Date,
        trackEndedAt: Date
    ) -> URL? {
        var recordings = Array(managedVideoRecordingsBySessionID.values)
        let knownSessionIDs = Set(recordings.map(\.sessionId))
        recordings += AppleManagedVideoRecordingCatalog.snapshot(
            sessionStartedAt: managedVideoIncidentStartedAt
        ).filter { !knownSessionIDs.contains($0.sessionId) }
        guard let match = ManagedVideoRecordingIdentity.recording(
            matching: designators,
            trackStartedAt: trackStartedAt,
            trackEndedAt: trackEndedAt,
            candidates: recordings.map {
                ManagedVideoRecordingIdentity.Candidate(
                    sessionID: $0.sessionId,
                    designator: $0.droneDesignator,
                    startedAt: $0.startedAt,
                    endedAt: $0.recordedAt
                )
            }
        ) else { return nil }
        return TrackerTabletLink.recordingShortURL(
            trackerURLPrefix: trackerURLPrefix,
            tabletName: AppleDeviceIdentity.displayName,
            sessionID: match.sessionID
        )
    }

    private func publish(_ event: TrackerCoordinationEvent) {
        switch event {
        case let .ownershipChanged(remoteID, ownerZoneID, localOwner, alertEligible):
            AppleLog.info(
                "TrackerPeer",
                "Ownership remoteId=\(remoteID) ownerZoneId=\(ownerZoneID) local=\(localOwner) alertEligible=\(alertEligible)"
            )
        case let .ownerExpired(remoteID):
            AppleLog.info("TrackerPeer", "Owner expired remoteId=\(remoteID)")
        case let .peersUpdated(peers):
            AppleLog.info("TrackerPeer", "Peer zones updated count=\(peers.count)")
        case let .relaySighting(relay):
            AppleLog.info(
                "TrackerPeer",
                "Relay sighting remoteId=\(relay.remoteID) fromZoneId=\(relay.sourceZoneID)"
            )
        case let .peerTrafficPosition(traffic):
            let now = Self.nowMilliseconds
            let logKey = "\(traffic.sourceZoneID)|\(traffic.source)|\(traffic.remoteID)"
            let logDate = Date()
            guard lastTrafficShadowLogAtByKey[logKey].map({ logDate.timeIntervalSince($0) >= 5 }) ?? true else {
                break
            }
            lastTrafficShadowLogAtByKey[logKey] = logDate
            let mslAltitudeText = traffic.mslAltitudeMeters.map { String($0) } ?? "null"
            let mslAltitudeAgeText = traffic.mslAltitudeSampleTimestampMilliseconds
                .map { String(max(0, now - $0)) } ?? "null"
            let correctionText = traffic.altitudeCorrectionMeters.map { String($0) } ?? "null"
            let demResolutionText = traffic.demResolutionMeters.map { String($0) } ?? "null"
            let incidentPadText = traffic.incidentPadFeet.map { String($0) } ?? "null"
            let nearestDistanceText = traffic.shadowNearestDistanceMeters.map { String($0) } ?? "null"
            let schedulingPadText = traffic.shadowSchedulingPadFeet.map { String($0) } ?? "null"
            let headingText = traffic.headingDegrees.map { String($0) } ?? "null"
            let speedText = traffic.groundSpeedKnots.map { String($0) } ?? "null"
            AppleLog.info(
                "TrackerPeer",
                "Peer traffic shadow remoteId=\(traffic.remoteID) source=\(traffic.source) fromZoneId=\(traffic.sourceZoneID) seq=\(traffic.sequence) sourceAgeMs=\(max(0, now - traffic.sampleTimestampMilliseconds)) trackerAgeMs=\(max(0, now - traffic.trackerReceivedTimestampMilliseconds)) headingDeg=\(headingText) speedKt=\(speedText) altAgeMs=\(max(0, now - (traffic.altitudeSampleTimestampMilliseconds ?? traffic.sampleTimestampMilliseconds))) altState=\(traffic.altitudeCalibrationState) mslAltM=\(mslAltitudeText) mslAltAgeMs=\(mslAltitudeAgeText) correctionM=\(correctionText) demSource=\(traffic.demSource) demResolutionM=\(demResolutionText) incidentPadFt=\(incidentPadText) nearestDistanceM=\(nearestDistanceText) schedulingPadFt=\(schedulingPadText) shadowIntervalMs=\(traffic.shadowIntervalMilliseconds ?? 0)"
            )
        case let .trafficSchedule(schedule):
            guard schedule.sourceEpoch == trafficSourceEpoch else { break }
            let key = "\(schedule.source)|\(schedule.remoteID)"
            let previousInterval = trafficSendIntervalByKey[key]
            trafficSendIntervalByKey[key] = TimeInterval(schedule.intervalMilliseconds) / 1_000
            guard previousInterval != trafficSendIntervalByKey[key] else { break }
            let incidentPadText = schedule.incidentPadFeet.map { String($0) } ?? "null"
            let nearestDistanceText = schedule.nearestDistanceMeters.map { String($0) } ?? "null"
            let schedulingPadText = schedule.schedulingPadFeet.map { String($0) } ?? "null"
            AppleLog.info(
                "TrackerPeer",
                "Peer traffic schedule remoteId=\(schedule.remoteID) source=\(schedule.source) seq=\(schedule.sequence) intervalMs=\(schedule.intervalMilliseconds) incidentPadFt=\(incidentPadText) nearestDistanceM=\(nearestDistanceText) schedulingPadFt=\(schedulingPadText)"
            )
        default:
            break
        }
        eventContinuation.yield(event)
    }

    private func stopTransport() {
        standbyTask?.cancel()
        standbyTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        authorizationRecoveryTask?.cancel()
        authorizationRecoveryTask = nil
        trackerSilenceTask?.cancel()
        trackerSilenceTask = nil
        coordinationAttemptStartedAtMilliseconds = 0
        for requestID in Array(mediaPeersByRequestID.keys) {
            stopLocalMediaSession(requestID: requestID)
        }
        approvedVideoStreamRequests.removeAll()
        for task in sourceEndGraceTasks.values { task.cancel() }
        sourceEndGraceTasks.removeAll()
        stopSocketOnly()
        peers = []
        organizationConfigSyncInProgress = false
        resetAcknowledgements()
    }

    private func stopSocketOnly() {
        videoPreflightPeer.cancel()
        generation = UUID()
        connected = false
        helloAcknowledged = false
        receiveTask?.cancel()
        receiveFailureTask?.cancel()
        heartbeatTask?.cancel()
        watchdogTask?.cancel()
        receiveTask = nil
        receiveFailureTask = nil
        heartbeatTask = nil
        watchdogTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
        socketDelegate = nil
    }

    private var missingConfigurationDescription: String {
        if trackerURLPrefix.isEmpty || trackerAPIKey.isEmpty { return "Tracker URL or API key missing" }
        if mapID.isEmpty { return "Select the incident Map ID for coordination" }
        return "Tracker coordination not configured"
    }

    private func resetAcknowledgements() {
        helloAcknowledgedAtMilliseconds = 0
        heartbeatAcknowledgedAtMilliseconds = 0
        heartbeatSequenceAcknowledged = 0
    }

    private static func elapsedText(since milliseconds: Int64) -> String {
        let seconds = max(0, (nowMilliseconds - milliseconds) / 1_000)
        if seconds < 60 { return "\(seconds) sec" }
        let minutes = seconds / 60
        if minutes < 60 { return "\(minutes) min" }
        return "\(minutes / 60) hr"
    }

    private static func errorDescription(_ error: Error) -> String {
        let value = error as NSError
        return "\(value.localizedDescription) [\(value.domain) \(value.code)]"
    }

    private static var nowMilliseconds: Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    private static func trackerIdentity(_ identity: RidAircraftIdentity) -> TrackerCoordinationIdentity {
        TrackerCoordinationIdentity(
            remoteID: identity.remoteID,
            mappedID: identity.mappedID,
            organization: identity.organization,
            model: identity.droneDescription,
            ownerName: identity.pilotCallsign
        )
    }

    private static func makeClient(mapID: String, zoneID: String, zoneName: String) -> TrackerCoordinationClient {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let build = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0
        return TrackerCoordinationClient(
            mapID: mapID,
            zoneID: zoneID,
            name: zoneName,
            deviceModel: AppleDeviceIdentity.modelName,
            platform: "ios",
            appVersion: "\(version)(\(build))",
            appVersionCode: build
        )
    }
}

private final class AppleTrackerWebSocketDelegate: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let onOpen: @Sendable () -> Void
    private let onClose: @Sendable (Int, String) -> Void
    private let onFailure: @Sendable (Int?, String) -> Void
    private let terminalLock = NSLock()
    private var terminalDelivered = false

    init(
        onOpen: @escaping @Sendable () -> Void,
        onClose: @escaping @Sendable (Int, String) -> Void,
        onFailure: @escaping @Sendable (Int?, String) -> Void
    ) {
        self.onOpen = onOpen
        self.onClose = onClose
        self.onFailure = onFailure
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        onOpen()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        guard claimTerminalCallback() else { return }
        onClose(Int(closeCode.rawValue), reason.flatMap { String(data: $0, encoding: .utf8) } ?? "")
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        let responseCode = (task.response as? HTTPURLResponse)?.statusCode
        guard error != nil || (responseCode != nil && responseCode != 101),
              claimTerminalCallback()
        else { return }
        let detail: String
        if let responseCode {
            let reason = HTTPURLResponse.localizedString(forStatusCode: responseCode)
            detail = "HTTP \(responseCode) \(reason)"
        } else if let error {
            let value = error as NSError
            detail = "\(value.localizedDescription) [\(value.domain) \(value.code)]"
        } else {
            detail = "Tracker connection failed"
        }
        onFailure(responseCode, detail)
    }

    private func claimTerminalCallback() -> Bool {
        terminalLock.lock()
        defer { terminalLock.unlock() }
        guard !terminalDelivered else { return false }
        terminalDelivered = true
        return true
    }
}
