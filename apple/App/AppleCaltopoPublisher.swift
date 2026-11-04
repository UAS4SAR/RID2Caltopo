import Foundation
import R2CCore

enum AppleCaltopoPublisherEvent: Sendable, Equatable {
    case disabled
    case ready
    case trackStarted(String)
    case pointPublished(String, rttMilliseconds: Int64)
    case trackStopped(String)
    /// Shape archive confirmed (live finish or interrupted recovery).
    case trackArchived(liveTrackID: String, remoteID: String, folderTitle: String)
    case failed(String)
}

struct AppleArchivedCaltopoTrack: Sendable {
    let liveTrackID: String
    let label: String
    let observations: [RidObservation]
    let folderID: String
    let folderTitle: String
    let configurationGeneration: Int
    /// Description written with the archive (empty until a captured-video link is attached).
    var description: String = ""
    /// Aircraft remote id the Shape was archived for.
    var remoteID: String = ""

    var hasVideoLink: Bool { CaltopoArchiveDescription.alreadyContainsVideoLink(description) }
}

actor AppleCaltopoPublisher {
    private static let staleDeviceMarkerAge: TimeInterval = 180
    nonisolated let events: AsyncStream<AppleCaltopoPublisherEvent>
    private nonisolated let continuation: AsyncStream<AppleCaltopoPublisherEvent>.Continuation
    private var client: CaltopoLiveClient?
    private var liveTrackIDs: [String: String] = [:]
    private var requestedLiveTrackIDs: [String: String] = [:]
    private var nextStartAttemptAt: [String: Date] = [:]
    private var labels: [String: String] = [:]
    private var observations: [String: [RidObservation]] = [:]
    private var startTasks: [String: Task<String, Error>] = [:]
    private var finishingRemoteIDs: Set<String> = []
    private var trackFolderName = "Drone Tracks"
    private var trackFolderID: String?
    private var archiveFolderID: String?
    private var folderResolver = CaltopoTrackFolderResolver()
    private var configurationGeneration = 0
    private var configuredConfiguration: AppleCaltopoConfiguration?
    private var configurationTransitionInFlight = false
    private var configurationIdleWaiters: [CheckedContinuation<Void, Never>] = []
    private var lastDeviceMarkerPublishedAt: Date?
    private var publishedDeviceMarkerID: String?
    private var ownedDeviceMarkerID = ""
    /// Set when the first live track starts for this map session. Connect/open must not publish.
    private var deviceMarkerAllowed = false
    private var mapOpenRetry: Task<Void, Never>?
    private var mapOpenRetryAttempt = 0
    private var deviceMarkerGeneration = 0
    private var deviceMarkerPublishingSuspended = false
    private var deviceMarkerPublishInFlight = false
    private var pendingDeviceMarkerPublication: (marker: CaltopoDeviceMarker, force: Bool, consent: AppleLocationConsent, consentGeneration: UInt)?
    private var deviceMarkerIdleWaiters: [CheckedContinuation<Void, Never>] = []
    private var lastInterruptedJournalWriteAt: [String: Date] = [:]
    private var pendingInterruptedRecoveries: [CaltopoInterruptedPublication] = []
    /// Shapes archived by this process (live finish or recovery), newest last. In-memory and
    /// bounded; lets a later recording link patch the archive without a map read.
    private var recentArchivedTracks: [AppleArchivedCaltopoTrack] = []
    private static let recentArchivedTrackLimit = 32
    private let interruptedJournal: CaltopoInterruptedPublicationJournal
    private var recoveryTask: Task<Void, Never>?
    private var recoveryInFlight = false
    private let markerJournal = DeviceMarkerJournal(file: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("device-marker-cleanup.json"))
    private var markerCleanupInFlight = false
    private var publishedMarkerOwnership: DeviceMarkerJournal.Entry?

    init(interruptedJournal: CaltopoInterruptedPublicationJournal? = nil) {
        let pair = AsyncStream<AppleCaltopoPublisherEvent>.makeStream(bufferingPolicy: .bufferingNewest(128))
        events = pair.stream
        continuation = pair.continuation
        if let interruptedJournal {
            self.interruptedJournal = interruptedJournal
        } else {
            let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            self.interruptedJournal = CaltopoInterruptedPublicationJournal(
                fileURL: root.appendingPathComponent("caltopo-interrupted-publications.json")
            )
        }
    }

    deinit {
        recoveryTask?.cancel()
        mapOpenRetry?.cancel()
        continuation.finish()
    }

    func configure(_ configuration: AppleCaltopoConfiguration, trackFolderName: String, deviceMarkerID: String = "") async {
        await waitForConfigurationTransitionToFinish()
        let normalizedFolderName = Self.normalizedTrackFolderName(trackFolderName)
        let normalizedOwner = deviceMarkerID.trimmingCharacters(in: .whitespacesAndNewlines)
        if !normalizedOwner.isEmpty { ownedDeviceMarkerID = normalizedOwner }
        guard configuredConfiguration != configuration
                || self.trackFolderName != normalizedFolderName
        else {
            // ContentView can observe the same map selection through both its
            // selection callback and its configuration fingerprint. Android
            // keeps one open-map session in this case; preserve the same
            // resolved folder IDs instead of starting a second create race.
            AppleLog.info(
                "CalTopo",
                "Ignoring duplicate publisher configuration for map id=\(configuration.mapID)"
            )
            return
        }
        configurationTransitionInFlight = true
        await performConfiguration(configuration, normalizedFolderName: normalizedFolderName)
        configurationTransitionInFlight = false
        let waiters = configurationIdleWaiters
        configurationIdleWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }

    private func waitForConfigurationTransitionToFinish() async {
        while configurationTransitionInFlight {
            await withCheckedContinuation { continuation in
                configurationIdleWaiters.append(continuation)
            }
        }
    }

    private static let disconnectFinishTimeout: Duration = .seconds(15)

    private func performConfiguration(
        _ configuration: AppleCaltopoConfiguration,
        normalizedFolderName: String
    ) async {
        recoveryTask?.cancel()
        recoveryTask = nil
        mapOpenRetry?.cancel()
        mapOpenRetry = nil
        mapOpenRetryAttempt = 0
        let disconnecting = configuration.liveConfiguration == nil && client != nil
        if disconnecting {
            // Finish owned LiveTracks (Shape archive once) before dropping the map client.
            // On timeout/unreachable CalTopo the finish path journals for reconnect recovery.
            await finishOwnedLiveTracksForDisconnect(timeout: Self.disconnectFinishTimeout)
        } else {
            // Persist the final buffered observations before replacing the map client.
            for remoteID in Array(requestedLiveTrackIDs.keys) {
                await persistInterruptedPublication(remoteID: remoteID, force: true)
            }
        }
        deviceMarkerGeneration += 1
        deviceMarkerPublishingSuspended = true
        pendingDeviceMarkerPublication = nil
        await waitForDeviceMarkerPublicationToFinish()
        await removePublishedDeviceMarker()
        configurationGeneration += 1
        await client?.stopAllPositionReports()
        await folderResolver.reset()
        folderResolver = CaltopoTrackFolderResolver()
        liveTrackIDs.removeAll()
        requestedLiveTrackIDs.removeAll()
        lastInterruptedJournalWriteAt.removeAll()
        nextStartAttemptAt.removeAll()
        labels.removeAll()
        observations.removeAll()
        finishingRemoteIDs.removeAll()
        startTasks.values.forEach { $0.cancel() }
        startTasks.removeAll()
        pendingInterruptedRecoveries.removeAll()
        recentArchivedTracks.removeAll()
        configuredConfiguration = configuration
        self.trackFolderName = normalizedFolderName
        trackFolderID = nil
        archiveFolderID = nil
        deviceMarkerAllowed = false
        lastDeviceMarkerPublishedAt = nil
        await CaltopoInitialFullReadGate.shared.complete()
        guard let liveConfiguration = configuration.liveConfiguration else {
            client = nil
            deviceMarkerPublishingSuspended = false
            continuation.yield(.disabled)
            return
        }
        do {
            let configuredClient = try CaltopoLiveClient(configuration: liveConfiguration)
            client = configuredClient
            let recoveryGeneration = configurationGeneration
            recoveryTask = Task { [weak self] in
                while !Task.isCancelled {
                    do { try await Task.sleep(for: .seconds(30)) } catch { break }
                    guard !Task.isCancelled else { break }
                    await self?.retryInterruptedPublications(generation: recoveryGeneration)
                }
            }
            pendingInterruptedRecoveries = await interruptedJournal.entries(mapID: configuration.mapID)
            await retryMarkerCleanup()
            await CaltopoInitialFullReadGate.shared.begin()
            do {
                // Connect must not create folders or a device marker. Full-map read discovers
                // existing Drone Tracks* folders / R2C markers; create only on first flight.
                let snapshot = try await configuredClient.fetchMapArtifacts(refresh: true)
                adoptTrackFoldersFromConnectSnapshot(snapshot)
                await CaltopoInitialFullReadGate.shared.complete()
                await removeStaleR2CDeviceMarkers(client: configuredClient)
                mapOpenRetry?.cancel()
                mapOpenRetry = nil
                mapOpenRetryAttempt = 0
                deviceMarkerPublishingSuspended = false
                // Clues and the connected map must not wait on interrupted LiveTrack recovery.
                continuation.yield(.ready)
                await retryInterruptedPublications(generation: configurationGeneration)
            } catch {
                await CaltopoInitialFullReadGate.shared.complete()
                if let message = CaltopoServerAvailability.retryMessage(error) {
                    continuation.yield(.failed(message))
                    scheduleMapOpenRetry(client: configuredClient, generation: configurationGeneration)
                } else {
                    AppleLog.warning(
                        "CalTopo",
                        "Map open probe deferred; will retry after reconnect: \(error.localizedDescription)"
                    )
                    deviceMarkerPublishingSuspended = false
                    continuation.yield(.ready)
                    await retryInterruptedPublications(generation: configurationGeneration)
                }
            }
        } catch {
            client = nil
            deviceMarkerPublishingSuspended = false
            continuation.yield(.failed("Configuration: \(error.localizedDescription)"))
        }
    }

    private func scheduleMapOpenRetry(client: CaltopoLiveClient, generation: Int) {
        mapOpenRetry?.cancel()
        let attempt = mapOpenRetryAttempt
        mapOpenRetryAttempt += 1
        let delay = [3.0, 6.0, 12.0, 15.0][min(attempt, 3)]
        mapOpenRetry = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            guard await self.configurationGeneration == generation else { return }
            await CaltopoInitialFullReadGate.shared.begin()
            do {
                // Probe the map without creating folders/markers; recovery ensures lazily.
                let snapshot = try await client.fetchMapArtifacts(refresh: true)
                await self.adoptTrackFoldersFromConnectSnapshot(snapshot)
                await CaltopoInitialFullReadGate.shared.complete()
                await self.removeStaleR2CDeviceMarkers(client: client)
                await self.finishMapOpenRetry()
                self.continuation.yield(.ready)
                await self.retryInterruptedPublications(generation: generation)
            } catch {
                await CaltopoInitialFullReadGate.shared.complete()
                guard !Task.isCancelled, await self.configurationGeneration == generation else { return }
                if let message = CaltopoServerAvailability.retryMessage(error) {
                    self.continuation.yield(.failed(message))
                    await self.scheduleMapOpenRetry(client: client, generation: generation)
                }
            }
        }
    }

    private func finishMapOpenRetry() {
        mapOpenRetry = nil
        mapOpenRetryAttempt = 0
        deviceMarkerPublishingSuspended = false
    }

    func publishDeviceMarker(_ marker: CaltopoDeviceMarker, force: Bool = false, consent: AppleLocationConsent, consentGeneration: UInt) async {
        guard consent.allows(consentGeneration), !deviceMarkerPublishingSuspended, deviceMarkerAllowed, client != nil else { return }
        if deviceMarkerPublishInFlight {
            let pendingForce = pendingDeviceMarkerPublication?.force ?? false
            pendingDeviceMarkerPublication = (marker, force || pendingForce, consent, consentGeneration)
            return
        }
        deviceMarkerPublishInFlight = true
        var publication: (marker: CaltopoDeviceMarker, force: Bool, consent: AppleLocationConsent, consentGeneration: UInt)? = (marker, force, consent, consentGeneration)
        while let current = publication {
            pendingDeviceMarkerPublication = nil
            await performDeviceMarkerPublication(current.marker, force: current.force, consent: current.consent, consentGeneration: current.consentGeneration)
            publication = pendingDeviceMarkerPublication
        }
        deviceMarkerPublishInFlight = false
        let waiters = deviceMarkerIdleWaiters
        deviceMarkerIdleWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }

    private func performDeviceMarkerPublication(_ marker: CaltopoDeviceMarker, force: Bool, consent: AppleLocationConsent, consentGeneration: UInt) async {
        guard consent.allows(consentGeneration), !deviceMarkerPublishingSuspended, deviceMarkerAllowed, !markerCleanupInFlight, let client,
              let markerConfiguration = configuredConfiguration?.liveConfiguration else { return }
        let generation = deviceMarkerGeneration
        let now = Date()
        if !force, let lastDeviceMarkerPublishedAt,
           now.timeIntervalSince(lastDeviceMarkerPublishedAt) < 30 {
            return
        }
        do {
            _ = try await ensureMapArtifacts(reason: .firstTrack)
            await recoverInterruptedPublications(client: client)
            await retryMarkerCleanup()
            guard consent.allows(consentGeneration), generation == deviceMarkerGeneration,
                  !deviceMarkerPublishingSuspended, deviceMarkerAllowed else { return }
            let ownership = try markerJournal.arm(domain: markerConfiguration.domainAndPort, map: markerConfiguration.mapID, marker: marker.id)
            publishedMarkerOwnership = ownership
            try await client.publishDeviceMarker(marker, folderID: trackFolderID, now: now)
            guard consent.allows(consentGeneration), generation == deviceMarkerGeneration, !deviceMarkerPublishingSuspended else {
                if await deleteAllDeviceMarkerOccurrences(client: client, markerID: marker.id) { try markerJournal.acknowledge(ownership) }
                return
            }
            lastDeviceMarkerPublishedAt = now
            publishedDeviceMarkerID = marker.id
            AppleLog.info(
                "CalTopo",
                "Published local device marker id=\(marker.id) folder='\(trackFolderName)'"
            )
        } catch {
            continuation.yield(.failed("Device marker: \(error.localizedDescription)"))
        }
    }

    func removeDeviceMarker() async {
        recoveryTask?.cancel()
        recoveryTask = nil
        // Persist the final buffered observations before replacing the map client.
        for remoteID in Array(requestedLiveTrackIDs.keys) {
            await persistInterruptedPublication(remoteID: remoteID, force: true)
        }
        deviceMarkerGeneration += 1
        deviceMarkerPublishingSuspended = true
        pendingDeviceMarkerPublication = nil
        await waitForDeviceMarkerPublicationToFinish()
        await removePublishedDeviceMarker()
        deviceMarkerPublishingSuspended = false
    }

    private func waitForDeviceMarkerPublicationToFinish() async {
        guard deviceMarkerPublishInFlight else { return }
        await withCheckedContinuation { continuation in
            deviceMarkerIdleWaiters.append(continuation)
        }
    }

    private func removePublishedDeviceMarker() async {
        if let client, let ownership = publishedMarkerOwnership {
            if await deleteAllDeviceMarkerOccurrences(client: client, markerID: ownership.marker) {
                do { try markerJournal.acknowledge(ownership) }
                catch { AppleLog.warning("CalTopo", "Marker cleanup acknowledgement could not be saved") }
            }
        } else if let client, let markerID = publishedDeviceMarkerID, let config = configuredConfiguration?.liveConfiguration {
            do {
                let ownership = try markerJournal.arm(domain: config.domainAndPort, map: config.mapID, marker: markerID)
                if await deleteAllDeviceMarkerOccurrences(client: client, markerID: markerID) { try markerJournal.acknowledge(ownership) }
            } catch { AppleLog.warning("CalTopo", "Marker cleanup retained for retry: \(error.localizedDescription)") }
        }
        publishedMarkerOwnership = nil
        publishedDeviceMarkerID = nil
        lastDeviceMarkerPublishedAt = nil
    }

    private func retryMarkerCleanup() async {
        guard !markerCleanupInFlight, let config = configuredConfiguration?.liveConfiguration else { return }
        markerCleanupInFlight = true
        defer { markerCleanupInFlight = false }
        do {
            for entry in try markerJournal.entries() where entry.domain == config.domainAndPort {
                if entry == publishedMarkerOwnership { continue }
                let owned = ownedDeviceMarkerID.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if owned.isEmpty || entry.marker.lowercased() != owned {
                    if !owned.isEmpty {
                        try? markerJournal.acknowledge(entry)
                        AppleLog.info("CalTopo", "Marker cleanup left an unproven marker id=\(entry.marker)")
                    }
                    continue
                }
                let target = CaltopoLiveConfiguration(domainAndPort: config.domainAndPort, mapID: entry.map,
                    credentialID: config.credentialID, credentialSecretBase64: config.credentialSecretBase64,
                    connectKey: config.connectKey, personalSessionID: config.personalSessionID,
                    personalAccountID: config.personalAccountID, personalMediaOwnerID: config.personalMediaOwnerID,
                    personalSignedAccount: config.personalSignedAccount)
                let cleanupClient = try CaltopoLiveClient(configuration: target)
                if await deleteAllDeviceMarkerOccurrences(client: cleanupClient, markerID: entry.marker) {
                    try markerJournal.acknowledge(entry)
                }
            }
        } catch { AppleLog.warning("CalTopo", "Marker cleanup journal retained for retry: \(error.localizedDescription)") }
    }

    private func deleteAllDeviceMarkerOccurrences(client: CaltopoLiveClient, markerID: String) async -> Bool {
        let normalizedID = markerID.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalizedID.isEmpty else { return false }
        let maximumAttempts = 8
        for attempt in 1...maximumAttempts {
            let beforeDelete = Int64(Date().timeIntervalSince1970 * 1_000)
            do {
                try await client.deleteMarker(markerID: normalizedID)
            } catch {
                AppleLog.warning(
                    "CalTopo",
                    "Could not remove local device marker id=\(normalizedID): \(error.localizedDescription)"
                )
                return false
            }
            do {
                let evidence = try await client.markerPresence(markerID: normalizedID, sinceMilliseconds: beforeDelete)
                if evidence.absent {
                    AppleLog.info(
                        "CalTopo",
                        "Removed local device marker id=\(normalizedID) attempts=\(attempt)"
                    )
                    return true
                }
                AppleLog.warning(
                    "CalTopo",
                    "Marker removal is not yet confirmed; durable retry retained id=\(normalizedID)"
                )
                if !evidence.present { return false }
            } catch {
                AppleLog.info(
                    "CalTopo",
                    "Marker removal unverified; retaining retry id=\(normalizedID): \(error.localizedDescription)"
                )
                return false
            }
        }
        AppleLog.warning(
            "CalTopo",
            "Local device marker id=\(normalizedID) remained after \(maximumAttempts) delete attempts"
        )
        return false
    }

    private func removeStaleR2CDeviceMarkers(client: CaltopoLiveClient, now: Date = Date()) async {
        do {
            let snapshot = try await client.fetchMapArtifacts(now: now)
            let staleIDs = snapshot.staleR2CDeviceMarkerIDs(
                now: now,
                staleAfter: Self.staleDeviceMarkerAge,
                ownedBy: ownedDeviceMarkerID
            )
            for markerID in staleIDs {
                do {
                    try await client.deleteMarker(markerID: markerID, now: now)
                    AppleLog.info("CalTopo", "Removed stale R2C device marker id=\(markerID)")
                } catch {
                    AppleLog.warning(
                        "CalTopo",
                        "Could not remove stale R2C device marker id=\(markerID): \(error.localizedDescription)"
                    )
                }
            }
        } catch {
            AppleLog.warning(
                "CalTopo",
                "Could not inspect map for stale R2C device markers: \(error.localizedDescription)"
            )
        }
    }

    func incidentCommandLocations() async -> [MapCoordinate] {
        guard let client else { return [] }
        let generation = configurationGeneration
        guard let snapshot = try? await client.fetchMapArtifacts(), generation == configurationGeneration else { return [] }
        return snapshot.points.filter { $0.title.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare("IC") == .orderedSame }.map(\.coordinate)
    }

    func hasBufferedPublication(remoteID: String, mapID: String) -> Bool {
        configuredConfiguration?.mapID == mapID && requestedLiveTrackIDs[remoteID] != nil
    }

    func seedDeferredFlight(_ entry: CaltopoInterruptedPublication) async {
        guard configuredConfiguration?.mapID == entry.mapID, requestedLiveTrackIDs[entry.remoteID] == nil else { return }
        requestedLiveTrackIDs[entry.remoteID] = entry.liveTrackID
        labels[entry.remoteID] = entry.label
        observations[entry.remoteID] = entry.observations
    }

    func queueDeferredArchive(_ entry: CaltopoInterruptedPublication) async throws {
        guard configuredConfiguration?.mapID == entry.mapID else { return }
        // An active publication owns its finalization; it will retain the latest geometry.
        try await interruptedJournal.upsert(entry)
        guard !requestedLiveTrackIDs.values.contains(entry.liveTrackID) else { return }
        pendingInterruptedRecoveries.removeAll { $0.liveTrackID == entry.liveTrackID }
        pendingInterruptedRecoveries.append(entry)
        await retryInterruptedPublications(generation: configurationGeneration)
    }

    func publish(track: RidAircraftTrack) async {
        await publish(
            remoteID: track.aircraftID,
            label: track.aircraftID,
            observation: track.lastObservation
        )
    }

    // Retain every admitted point before returning to the local publication chain.
    // Network completion must not hold that chain and build a historical backlog.
    func publish(
        remoteID: String,
        label: String,
        observation: RidObservation,
        cameraMetadata: CaltopoCameraMetadata? = nil
    ) async {
        guard client != nil, !finishingRemoteIDs.contains(remoteID) else { return }
        guard WaypointRecordingCadence.accepts(
            previous: observations[remoteID]?.last?.receivedAt,
            candidate: observation.receivedAt
        ) else { return }
        if observations[remoteID]?.last != observation {
            observations[remoteID, default: []].append(observation)
        }
        let id = requestedLiveTrackIDs[remoteID] ?? UUID().uuidString.lowercased()
        requestedLiveTrackIDs[remoteID] = id
        if labels[remoteID] == nil {
            labels[remoteID] = CaltopoTrackLabel.androidCompatible(
                baseLabel: label, firstWaypointAt: observations[remoteID]?.first?.receivedAt ?? observation.receivedAt)
        }
        Task {
            await self.publishBuffered(remoteID: remoteID, label: label,
                observation: observation, cameraMetadata: cameraMetadata, expectedID: id)
        }
    }

    private func publishBuffered(
        remoteID: String,
        label: String,
        observation: RidObservation,
        cameraMetadata: CaltopoCameraMetadata?,
        expectedID: String
    ) async {
        guard let client, requestedLiveTrackIDs[remoteID] == expectedID, !finishingRemoteIDs.contains(remoteID) else { return }
        let generation = configurationGeneration
        var publicationID: String?
        do {
            let saved = observations[remoteID, default: []]
            let trackLabel = labels[remoteID] ?? CaltopoTrackLabel.androidCompatible(
                baseLabel: label,
                firstWaypointAt: saved.first?.receivedAt ?? observation.receivedAt
            )
            labels[remoteID] = trackLabel
            let requestedID = requestedLiveTrackIDs[remoteID] ?? UUID().uuidString.lowercased()
            requestedLiveTrackIDs[remoteID] = requestedID
            publicationID = requestedID
            // Persist the identity and observations before any network request.
            guard let configuration = configuredConfiguration else { return }
            let now = Date()
            if liveTrackIDs[remoteID] == nil && startTasks[remoteID] == nil ||
                now.timeIntervalSince(lastInterruptedJournalWriteAt[remoteID] ?? .distantPast) >= 5 {
                try await interruptedJournal.upsert(CaltopoInterruptedPublication(
                    mapID: configuration.mapID, remoteID: remoteID, liveTrackID: requestedID,
                    label: trackLabel, description: "", observations: saved))
                lastInterruptedJournalWriteAt[remoteID] = now
            }
            guard generation == configurationGeneration,
                  requestedLiveTrackIDs[remoteID] == requestedID,
                  !finishingRemoteIDs.contains(remoteID) else { return }
            _ = try await ensureMapArtifacts(reason: .firstTrack)
            // First live-track create for this map session may publish the device marker.
            deviceMarkerAllowed = true
            guard generation == configurationGeneration,
                  requestedLiveTrackIDs[remoteID] == requestedID,
                  !finishingRemoteIDs.contains(remoteID) else { return }
            if liveTrackIDs[remoteID] == nil {
                let task: Task<String, Error>
                if startTasks[remoteID] != nil {
                    return // Points are already saved; only the original start awaits its result.
                } else {
                    guard Date() >= (nextStartAttemptAt[remoteID] ?? .distantPast) else { return }
                    nextStartAttemptAt[remoteID] = Date().addingTimeInterval(20)
                    let folderID = trackFolderID
                    task = Task {
                        try await client.startLiveTrack(
                            liveTrackID: requestedID,
                            remoteID: remoteID,
                            label: trackLabel,
                            folderID: folderID
                        )
                    }
                    startTasks[remoteID] = task
                }
                let liveTrackID = try await task.value
                guard generation == configurationGeneration,
                      requestedLiveTrackIDs[remoteID] == requestedID,
                      !finishingRemoteIDs.contains(remoteID) else { return }
                startTasks.removeValue(forKey: remoteID)
                liveTrackIDs[remoteID] = liveTrackID
                await persistInterruptedPublication(remoteID: remoteID, force: true)
                continuation.yield(.trackStarted(remoteID))
            }
            await persistInterruptedPublication(remoteID: remoteID)
            guard generation == configurationGeneration,
                  requestedLiveTrackIDs[remoteID] == publicationID,
                  !finishingRemoteIDs.contains(remoteID) else { return }
            let requestStarted = Date()
            let sent = try await client.publishPoint(
                remoteID: remoteID,
                observation: observations[remoteID]?.last ?? observation,
                cameraMetadata: cameraMetadata
            )
            guard sent else { return }
            let rttMilliseconds = max(0, Int64(Date().timeIntervalSince(requestStarted) * 1_000))
            guard generation == configurationGeneration, requestedLiveTrackIDs[remoteID] == publicationID else { return }
            continuation.yield(.pointPublished(remoteID, rttMilliseconds: rttMilliseconds))
        } catch {
            guard generation == configurationGeneration,
                  requestedLiveTrackIDs[remoteID] == publicationID else { return }
            if error as? CaltopoLiveClientError == .mismatchedLiveTrackID {
                nextStartAttemptAt[remoteID] = .distantFuture
            }
            startTasks.removeValue(forKey: remoteID)
            continuation.yield(.failed("\(remoteID): \(error.localizedDescription)"))
        }
    }

    /// Finish each owned LiveTrack for map disconnect using the normal archive path,
    /// bounded by `timeout`. Failures leave the interrupted journal for reconnect recovery.
    func finishOwnedLiveTracksForDisconnect(timeout: Duration) async {
        let remoteIDs = Array(requestedLiveTrackIDs.keys)
        guard !remoteIDs.isEmpty else { return }
        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        for remoteID in remoteIDs {
            if clock.now >= deadline {
                await persistInterruptedPublication(remoteID: remoteID, force: true)
                continue
            }
            let remaining = deadline - clock.now
            let finishTask = Task { await self.finish(remoteID: remoteID) }
            let timeoutTask = Task { try? await Task.sleep(for: remaining) }
            await withTaskGroup(of: Void.self) { group in
                group.addTask { _ = await finishTask.value }
                group.addTask { _ = await timeoutTask.value }
                _ = await group.next()
                finishTask.cancel()
                timeoutTask.cancel()
                group.cancelAll()
            }
        }
    }

    func finish(
        remoteID: String,
        description: String = "",
        fallbackObservations: [RidObservation] = []
    ) async -> AppleArchivedCaltopoTrack? {
        guard !finishingRemoteIDs.contains(remoteID) else { return nil }
        finishingRemoteIDs.insert(remoteID)
        let generation = configurationGeneration
        let label = labels[remoteID] ?? remoteID
        var saved = observations[remoteID] ?? []
        // Queued points from a flight that never received a LiveTrack id must still
        // publish through the archive when a map is configured.
        var id = liveTrackIDs[remoteID] ?? requestedLiveTrackIDs[remoteID]
        // Publisher buffers can be cleared by a mid-session reconfigure while the
        // LiveTrack is still live. Prefer durable journal / caller RID geometry over
        // stop-only (empty Shape archive → track vanishes with no Drone Tracks row).
        if saved.isEmpty {
            if let id, let journaled = await interruptedJournal.entry(liveTrackID: id),
               !journaled.observations.isEmpty {
                saved = journaled.observations
            } else if let journaled = await interruptedJournal.entry(remoteID: remoteID),
                      !journaled.observations.isEmpty {
                saved = journaled.observations
                if id == nil { id = journaled.liveTrackID }
            } else if !fallbackObservations.isEmpty {
                saved = fallbackObservations
            }
        }
        if id == nil && !saved.isEmpty && configuredConfiguration != nil {
            let assigned = UUID().uuidString.lowercased()
            requestedLiveTrackIDs[remoteID] = assigned
            id = assigned
        }
        let pendingStart = startTasks[remoteID]
        let finishingClient = client
        let finishingConfiguration = configuredConfiguration
        await finishingClient?.stopPositionReports(remoteID: remoteID)
        var clearPublicationState = true
        defer {
            if generation == configurationGeneration {
                finishingRemoteIDs.remove(remoteID)
                if clearPublicationState {
                    startTasks.removeValue(forKey: remoteID)
                    liveTrackIDs.removeValue(forKey: remoteID)
                    requestedLiveTrackIDs.removeValue(forKey: remoteID)
                    nextStartAttemptAt.removeValue(forKey: remoteID)
                    lastInterruptedJournalWriteAt.removeValue(forKey: remoteID)
                    labels.removeValue(forKey: remoteID)
                    observations.removeValue(forKey: remoteID)
                }
            }
        }
        // A map disconnect may already have cleared the live client while this
        // flight was still airborne. Persist the finish intent first so reconnect
        // recovery can convert the LiveTrack once, exactly as a normal archive.
        guard let id else {
            AppleLog.warning(
                "CalTopo",
                "CalTopo track not archived reason=no-live-track-id remoteId=\(remoteID)"
            )
            return nil
        }
        let mapID = finishingConfiguration?.mapID ?? configuredConfiguration?.mapID
        guard let mapID, !mapID.isEmpty else {
            AppleLog.warning(
                "CalTopo",
                "CalTopo track not archived reason=no-map liveTrackId=\(id)"
            )
            return nil
        }
        if saved.isEmpty {
            AppleLog.warning(
                "CalTopo",
                "CalTopo track not archived reason=no-waypoints liveTrackId=\(id)"
            )
            // Still stop the LiveTrack so it does not linger; do not claim a Shape archive.
            if let client = finishingClient {
                try? await client.stopLiveTrack(liveTrackID: id)
                continuation.yield(.trackStopped(remoteID))
            }
            return nil
        }
        let entry = CaltopoInterruptedPublication(
            mapID: mapID, remoteID: remoteID, liveTrackID: id,
            label: label, description: description, observations: saved)
        do {
            try await interruptedJournal.upsert(entry)
        } catch {
            AppleLog.warning(
                "CalTopo",
                "Could not persist finished LiveTrack \(id) for reconnect recovery: \(error.localizedDescription)"
            )
        }
        guard generation == configurationGeneration,
              let client = finishingClient,
              finishingConfiguration != nil else {
            // Ended offline after disconnect: the journal upsert above is the
            // durable pending finish; reconnect recovery loads it once.
            AppleLog.warning(
                "CalTopo",
                "CalTopo track not archived reason=client-unavailable liveTrackId=\(id)"
            )
            return nil
        }
        // Lost/unvalidated Internet: durable journal only — do not start a Shape POST
        // that can hang on a dead path and hide the entry from connectivity-restore flush.
        let internetAvailable = await MainActor.run {
            AppleNetworkDiagnosticCenter.shared.internetAvailable
        }
        if !internetAvailable {
            if generation == configurationGeneration {
                pendingInterruptedRecoveries.removeAll { $0.liveTrackID == id }
                pendingInterruptedRecoveries.append(entry)
            }
            AppleLog.warning(
                "CalTopo",
                "CalTopo track not archived reason=offline points=\(saved.count) liveTrackId=\(id)"
            )
            return nil
        }
        do {
            // Finish an in-flight create before the Shape write; a lost response does not
            // prevent conversion because the durable identity is already known.
            if let pendingStart { _ = try? await pendingStart.value }
            guard generation == configurationGeneration else {
                clearPublicationState = false
                AppleLog.warning(
                    "CalTopo",
                    "CalTopo track not archived reason=map-reconfigured liveTrackId=\(id)"
                )
                return nil
            }
            let flightDate = CaltopoTrackFolderResolver.flightArchiveDate(observations: saved)
            let archiveFolderTitle = CaltopoTrackFolderResolver.archiveFolderName(
                trackFolderName: trackFolderName,
                date: flightDate
            )
            let archiveFolderID = try await resolveArchiveFolderID(for: flightDate)
            guard generation == configurationGeneration else {
                clearPublicationState = false
                AppleLog.warning(
                    "CalTopo",
                    "CalTopo track not archived reason=map-reconfigured liveTrackId=\(id)"
                )
                return nil
            }
            guard !archiveFolderID.isEmpty else {
                if generation == configurationGeneration {
                    pendingInterruptedRecoveries.removeAll { $0.liveTrackID == id }
                    pendingInterruptedRecoveries.append(entry)
                }
                clearPublicationState = false
                AppleLog.warning(
                    "CalTopo",
                    "CalTopo track not archived reason=dated-folder-unavailable liveTrackId=\(id) folder=\(archiveFolderTitle)"
                )
                return nil
            }
            try await client.archiveLiveTrack(
                liveTrackID: id, label: label, observations: saved,
                folderID: archiveFolderID, description: description)
            try await interruptedJournal.remove(liveTrackID: id)
            pendingInterruptedRecoveries.removeAll { $0.liveTrackID == id }
            AppleLog.info(
                "CalTopo",
                "CalTopo track archived id=\(id) folder=\(archiveFolderTitle)"
            )
            continuation.yield(.trackArchived(
                liveTrackID: id, remoteID: remoteID, folderTitle: archiveFolderTitle))
            continuation.yield(.trackStopped(remoteID))
            let archived = AppleArchivedCaltopoTrack(
                liveTrackID: id, label: label, observations: saved, folderID: archiveFolderID,
                folderTitle: archiveFolderTitle, configurationGeneration: generation,
                description: description, remoteID: remoteID)
            rememberArchivedTrack(archived)
            return archived
        } catch {
            if generation == configurationGeneration {
                pendingInterruptedRecoveries.removeAll { $0.liveTrackID == id }
                pendingInterruptedRecoveries.append(entry)
            }
            AppleLog.warning(
                "CalTopo",
                "CalTopo track not archived reason=request-failed liveTrackId=\(id): \(error.localizedDescription)"
            )
            continuation.yield(.failed("Stop \(remoteID): \(error.localizedDescription)"))
            return nil
        }
    }

    func updateArchivedDescription(
        _ archivedTrack: AppleArchivedCaltopoTrack,
        description: String
    ) async -> Bool {
        guard archivedTrack.configurationGeneration == configurationGeneration, let client else { return false }
        do {
            try await client.updateArchivedTrack(
                liveTrackID: archivedTrack.liveTrackID,
                label: archivedTrack.label,
                observations: archivedTrack.observations,
                folderID: archivedTrack.folderID,
                description: description
            )
            if let index = recentArchivedTracks.firstIndex(where: { $0.liveTrackID == archivedTrack.liveTrackID }) {
                recentArchivedTracks[index].description = description
            }
            AppleLog.info(
                "CalTopo",
                "Added deferred recording link to archived track \(archivedTrack.liveTrackID)"
            )
            return true
        } catch {
            AppleLog.warning(
                "CalTopo",
                "Deferred recording link update failed for \(archivedTrack.liveTrackID): \(error.localizedDescription)"
            )
            return false
        }
    }

    private func rememberArchivedTrack(_ track: AppleArchivedCaltopoTrack) {
        recentArchivedTracks.removeAll { $0.liveTrackID == track.liveTrackID }
        recentArchivedTracks.append(track)
        if recentArchivedTracks.count > Self.recentArchivedTrackLimit {
            recentArchivedTracks.removeFirst(recentArchivedTracks.count - Self.recentArchivedTrackLimit)
        }
    }

    /// Attach a recording link to an already-archived (or archive-pending) flight Shape that was
    /// not handed back to the caller. Best effort and never creates a new Shape: it PATCHes the
    /// existing Shape id via `updateArchivedTrack`, or amends the durable interrupted entry so the
    /// pending recovery archives it with the link. Matches by remoteID (title fallback) and a
    /// flight-window overlap. Returns true when no further retry is needed.
    func patchArchivedDescriptionByFlight(
        remoteID: String,
        trackStartedAt: Date,
        trackEndedAt: Date,
        description: String
    ) async -> Bool {
        guard !description.isEmpty else { return false }
        let generation = configurationGeneration
        let windowStart = trackStartedAt.addingTimeInterval(-ManagedVideoRecordingIdentity.trackAssociationGrace)
        let windowEnd = trackEndedAt.addingTimeInterval(ManagedVideoRecordingIdentity.trackAssociationGrace)
        func overlaps(_ observations: [RidObservation]) -> Bool {
            guard let first = observations.map(\.receivedAt).min(),
                  let last = observations.map(\.receivedAt).max() else { return false }
            return first <= windowEnd && last >= windowStart
        }
        func matches(id: String, label: String) -> Bool {
            id == remoteID || label == remoteID
        }

        // 1. Shape already archived by this process.
        if let known = recentArchivedTracks.last(where: {
            matches(id: $0.remoteID, label: $0.label) && overlaps($0.observations)
        }) {
            if known.hasVideoLink { return true }
            guard known.configurationGeneration == generation, let client else { return false }
            do {
                try await client.updateArchivedTrack(
                    liveTrackID: known.liveTrackID,
                    label: known.label,
                    observations: known.observations,
                    folderID: known.folderID,
                    description: description
                )
                if let index = recentArchivedTracks.firstIndex(where: { $0.liveTrackID == known.liveTrackID }) {
                    recentArchivedTracks[index].description = description
                }
                AppleLog.info("CalTopo", "Added deferred recording link to archived track \(known.liveTrackID)")
                return true
            } catch {
                AppleLog.warning(
                    "CalTopo",
                    "Deferred recording link update failed for \(known.liveTrackID): \(error.localizedDescription)"
                )
                return false
            }
        }

        // 2. Archive still pending: amend the durable entry; recovery will archive with the link.
        if let pending = await interruptedJournal.entry(remoteID: remoteID),
           overlaps(pending.observations) {
            guard generation == configurationGeneration else { return false }
            if CaltopoArchiveDescription.alreadyContainsVideoLink(pending.description) { return true }
            var amended = pending
            amended.description = description
            do {
                try await interruptedJournal.upsert(amended)
            } catch {
                return false
            }
            if let index = pendingInterruptedRecoveries.firstIndex(where: { $0.liveTrackID == pending.liveTrackID }) {
                pendingInterruptedRecoveries[index].description = description
            }
            AppleLog.info(
                "CalTopo",
                "Added deferred recording link to pending archive \(pending.liveTrackID)"
            )
            return true
        }
        return false
    }

    func acceptsDeferredUpdate(for archivedTrack: AppleArchivedCaltopoTrack) -> Bool {
        archivedTrack.configurationGeneration == configurationGeneration && client != nil
    }

    /// Stops an ignored aircraft's live track without converting it to an archived Shape.
    func discard(remoteID: String) async {
        finishingRemoteIDs.insert(remoteID)
        await client?.stopPositionReports(remoteID: remoteID)
        defer {
            finishingRemoteIDs.remove(remoteID)
            startTasks.removeValue(forKey: remoteID)
            liveTrackIDs.removeValue(forKey: remoteID)
            requestedLiveTrackIDs.removeValue(forKey: remoteID)
            nextStartAttemptAt.removeValue(forKey: remoteID)
            labels.removeValue(forKey: remoteID)
            observations.removeValue(forKey: remoteID)
        }

        let liveTrackID: String?
        if let existing = liveTrackIDs[remoteID] {
            liveTrackID = existing
        } else if let task = startTasks[remoteID] {
            liveTrackID = (try? await task.value) ?? requestedLiveTrackIDs[remoteID]
        } else {
            liveTrackID = requestedLiveTrackIDs[remoteID]
        }
        guard let client, let liveTrackID else { return }
        do {
            try await client.stopLiveTrack(liveTrackID: liveTrackID)
            try await interruptedJournal.remove(liveTrackID: liveTrackID)
            continuation.yield(.trackStopped(remoteID))
            AppleLog.info("CalTopo", "Discarded ignored aircraft remoteId=\(remoteID) liveTrackId=\(liveTrackID)")
        } catch {
            continuation.yield(.failed("Discard \(remoteID): \(error.localizedDescription)"))
        }
    }

    /// Non-blocking folder id for clue send (nil until connect adoption or first-flight create).
    func cachedTrackFolderID() -> String? { trackFolderID }

    /// Bounded wait for the connect full-map read. Clue uploads only.
    func awaitInitialFullReadForClue(
        timeoutSeconds: TimeInterval = CaltopoClueTrackFolderSend.initialReadWaitSeconds
    ) async -> Bool {
        await CaltopoInitialFullReadGate.shared.wait(timeoutSeconds: timeoutSeconds)
    }

    /// Cache existing Drone Tracks* folder ids from the connect full-map read (never creates).
    private func adoptTrackFoldersFromConnectSnapshot(_ snapshot: CaltopoArtifactSnapshot) {
        let activeName = CaltopoTrackFolderResolver.normalizedTrackFolderNameForAdoption(trackFolderName)
        let archiveName = CaltopoTrackFolderResolver.archiveFolderName(trackFolderName: activeName)
        if trackFolderID == nil,
           let adopted = CaltopoTrackFolderResolver.adoptedFolderID(named: activeName, in: snapshot) {
            trackFolderID = adopted
            AppleLog.info("CalTopo", "adopted folder title=\(activeName) id=\(adopted)")
        }
        if archiveFolderID == nil,
           let adopted = CaltopoTrackFolderResolver.adoptedFolderID(named: archiveName, in: snapshot) {
            archiveFolderID = adopted
            AppleLog.info("CalTopo", "adopted folder title=\(archiveName) id=\(adopted)")
        }
    }

    /// Lazily creates or reuses Drone Tracks folders. Idempotent and coalesced under concurrency.
    /// Does not publish the device marker; callers enable that only for a live-track start.
    @discardableResult
    func ensureMapArtifacts(reason: CaltopoMapArtifactReason) async throws -> CaltopoTrackFolderIDs {
        guard let client else { throw CancellationError() }
        if let trackFolderID, let archiveFolderID {
            return CaltopoTrackFolderIDs(active: trackFolderID, archive: archiveFolderID, created: false)
        }
        let generation = configurationGeneration
        let resolver = folderResolver
        let folderName = trackFolderName
        let knownActive = trackFolderID
        let knownArchive = archiveFolderID
        let resolved = try await resolver.resolve(
            trackFolderName: folderName,
            settleDelay: .zero,
            existingActiveID: knownActive,
            existingArchiveID: knownArchive,
            fetchSnapshot: {
                // Prefer the warm map-open snapshot. refresh:true would re-enter the shared
                // full-map-read gate (90s) and stall clue POSTs behind reconnect / LiveTrack recovery.
                try await client.fetchMapArtifacts(refresh: false)
            },
            createFolder: { title, visible, labelVisible in
                try await client.createFolder(
                    title: title,
                    visible: visible,
                    labelVisible: labelVisible
                )
            }
        )
        guard generation == configurationGeneration else { throw CancellationError() }
        // Prefer ids adopted while resolve was in flight over a just-created duplicate.
        if trackFolderID == nil { trackFolderID = resolved.active }
        if archiveFolderID == nil { archiveFolderID = resolved.archive }
        let active = trackFolderID ?? resolved.active
        let archive = archiveFolderID ?? resolved.archive
        if resolved.created {
            AppleLog.info(
                "CalTopo",
                "Created map artifacts reason=\(reason.rawValue) active=\(active) archive=\(archive) folder='\(folderName)'"
            )
        }
        return CaltopoTrackFolderIDs(active: active, archive: archive, created: resolved.created)
    }

    private func persistInterruptedPublication(
        remoteID: String,
        description: String = "",
        force: Bool = false
    ) async {
        guard let configuration = configuredConfiguration,
              let liveTrackID = liveTrackIDs[remoteID] ?? requestedLiveTrackIDs[remoteID]
        else { return }
        let now = Date()
        if !force,
           let lastWrite = lastInterruptedJournalWriteAt[remoteID],
           now.timeIntervalSince(lastWrite) < 5 {
            return
        }
        let entry = CaltopoInterruptedPublication(
            mapID: configuration.mapID,
            remoteID: remoteID,
            liveTrackID: liveTrackID,
            label: labels[remoteID] ?? remoteID,
            description: description,
            observations: observations[remoteID] ?? []
        )
        do {
            try await interruptedJournal.upsert(entry)
            lastInterruptedJournalWriteAt[remoteID] = now
        } catch {
            AppleLog.warning("CalTopo", "Could not persist interrupted LiveTrack \(liveTrackID): \(error.localizedDescription)")
        }
    }

    /// Network-path wake: cancel hung CalTopo calls, then retry interrupted archives.
    func retryInterruptedPublications() async {
        await client?.cancelInFlightForConnectivityRestore()
        let archives = pendingInterruptedRecoveries.count
        AppleLog.info(
            "NetworkChecks",
            "connectivity restored → flush archives=\(archives) liveTracks=\(liveTrackIDs.count) clues=wake"
        )
        await retryInterruptedPublications(generation: configurationGeneration)
    }

    var pendingInterruptedArchiveCount: Int { pendingInterruptedRecoveries.count }

    private func retryInterruptedPublications(generation: Int) async {
        if generation == configurationGeneration && !configurationTransitionInFlight { await retryMarkerCleanup() }
        guard generation == configurationGeneration, !configurationTransitionInFlight,
              !pendingInterruptedRecoveries.isEmpty, let client else { return }
        // Active folder is optional for Shape archives; each flight resolves its own dated folder.
        _ = try? await ensureMapArtifacts(reason: .firstTrack)
        guard generation == configurationGeneration else { return }
        await recoverInterruptedPublications(client: client)
    }

    private func recoverInterruptedPublications(client: CaltopoLiveClient) async {
        guard !recoveryInFlight, !pendingInterruptedRecoveries.isEmpty else { return }
        recoveryInFlight = true
        defer { recoveryInFlight = false }
        let generation = configurationGeneration
        let batch = pendingInterruptedRecoveries
        var completed: Set<String> = []
        for entry in batch {
            guard generation == configurationGeneration else { break }
            guard !requestedLiveTrackIDs.values.contains(entry.liveTrackID),
                  !liveTrackIDs.values.contains(entry.liveTrackID) else { continue }
            do {
                let flightDate = CaltopoTrackFolderResolver.flightArchiveDate(
                    observations: entry.observations
                )
                let archiveFolderID = try await resolveArchiveFolderID(for: flightDate)
                guard !archiveFolderID.isEmpty else {
                    AppleLog.warning(
                        "CalTopo",
                        "Interrupted LiveTrack archive deferred — dated folder unavailable liveTrackId=\(entry.liveTrackID)"
                    )
                    continue
                }
                let archiveFolderTitle = CaltopoTrackFolderResolver.archiveFolderName(
                    trackFolderName: trackFolderName,
                    date: flightDate
                )
                try await client.archiveLiveTrack(
                    liveTrackID: entry.liveTrackID,
                    label: entry.label,
                    observations: entry.observations,
                    folderID: archiveFolderID,
                    description: entry.description
                )
                try await interruptedJournal.remove(liveTrackID: entry.liveTrackID)
                completed.insert(entry.liveTrackID)
                rememberArchivedTrack(AppleArchivedCaltopoTrack(
                    liveTrackID: entry.liveTrackID, label: entry.label,
                    observations: entry.observations, folderID: archiveFolderID,
                    folderTitle: archiveFolderTitle, configurationGeneration: generation,
                    description: entry.description, remoteID: entry.remoteID))
                AppleLog.info(
                    "CalTopo",
                    "CalTopo track archived id=\(entry.liveTrackID) folder=\(archiveFolderTitle)"
                )
                continuation.yield(.trackArchived(
                    liveTrackID: entry.liveTrackID,
                    remoteID: entry.remoteID,
                    folderTitle: archiveFolderTitle
                ))
                AppleLog.info(
                    "CalTopo",
                    "Recovered interrupted LiveTrack remoteId=\(entry.remoteID) liveTrackId=\(entry.liveTrackID) points=\(entry.points.count)"
                )
            } catch {
                // Keep this entry pending for the next independent retry.
                AppleLog.warning(
                    "CalTopo",
                    "Interrupted LiveTrack recovery deferred liveTrackId=\(entry.liveTrackID): \(error.localizedDescription)"
                )
            }
        }
        if generation == configurationGeneration {
            pendingInterruptedRecoveries.removeAll { completed.contains($0.liveTrackID) }
        }
    }

    /// Dated "Drone Tracks &lt;ddMMM&gt;" for a flight: adopt from the full read, or create.
    /// Never archives at the map root or into a duplicate same-title folder.
    private func resolveArchiveFolderID(for flightDate: Date) async throws -> String {
        guard let client else { throw CancellationError() }
        let generation = configurationGeneration
        let folderName = trackFolderName
        let archiveName = CaltopoTrackFolderResolver.archiveFolderName(
            trackFolderName: folderName,
            date: flightDate
        )
        let todayName = CaltopoTrackFolderResolver.archiveFolderName(trackFolderName: folderName)
        let knownForToday =
            CaltopoTrackFolderResolver.folderTitlesMatch(archiveName, expected: todayName)
            ? archiveFolderID
            : nil
        let snapshot = try await client.fetchMapArtifacts(refresh: false)
        let resolved = try await CaltopoTrackFolderResolver.adoptOrCreateArchiveFolderID(
            trackFolderName: folderName,
            flightDate: flightDate,
            existingArchiveID: knownForToday,
            snapshot: snapshot,
            createFolder: { title, visible, labelVisible in
                try await client.createFolder(
                    title: title,
                    visible: visible,
                    labelVisible: labelVisible
                )
            }
        )
        guard generation == configurationGeneration else { throw CancellationError() }
        if CaltopoTrackFolderResolver.folderTitlesMatch(archiveName, expected: todayName) {
            if archiveFolderID == nil { archiveFolderID = resolved }
        }
        if let adopted = CaltopoTrackFolderResolver.adoptedFolderID(named: archiveName, in: snapshot),
           adopted == resolved {
            AppleLog.info("CalTopo", "adopted folder title=\(archiveName) id=\(adopted)")
        }
        return resolved
    }

    private static func normalizedTrackFolderName(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Drone Tracks" : trimmed
    }

}
