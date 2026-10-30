import Foundation
import R2CCore

enum AppleCaltopoPublisherEvent: Sendable, Equatable {
    case disabled
    case ready
    case trackStarted(String)
    case pointPublished(String, rttMilliseconds: Int64)
    case trackStopped(String)
    case failed(String)
}

struct AppleArchivedCaltopoTrack: Sendable {
    let liveTrackID: String
    let label: String
    let observations: [RidObservation]
    let folderID: String
    let configurationGeneration: Int
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
    private var mapOpenRetry: Task<Void, Never>?
    private var mapOpenRetryAttempt = 0
    private var deviceMarkerGeneration = 0
    private var deviceMarkerPublishingSuspended = false
    private var deviceMarkerPublishInFlight = false
    private var pendingDeviceMarkerPublication: (marker: CaltopoDeviceMarker, force: Bool, consent: AppleLocationConsent, consentGeneration: UInt)?
    private var deviceMarkerIdleWaiters: [CheckedContinuation<Void, Never>] = []
    private var lastInterruptedJournalWriteAt: [String: Date] = [:]
    private var pendingInterruptedRecoveries: [CaltopoInterruptedPublication] = []
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
        configuredConfiguration = configuration
        self.trackFolderName = normalizedFolderName
        trackFolderID = nil
        archiveFolderID = nil
        lastDeviceMarkerPublishedAt = nil
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
            do {
                try await ensureFolders(client: configuredClient)
                await removeStaleR2CDeviceMarkers(client: configuredClient)
                mapOpenRetry?.cancel()
                mapOpenRetry = nil
                mapOpenRetryAttempt = 0
                deviceMarkerPublishingSuspended = false
                // Clues and the connected map must not wait on interrupted LiveTrack recovery.
                continuation.yield(.ready)
                await recoverInterruptedPublications(client: configuredClient)
            } catch {
                if let message = CaltopoServerAvailability.retryMessage(error) {
                    continuation.yield(.failed(message))
                    scheduleMapOpenRetry(client: configuredClient, generation: configurationGeneration)
                } else {
                    AppleLog.warning(
                        "CalTopo",
                        "Interrupted LiveTrack recovery will retry after map reconnect: \(error.localizedDescription)"
                    )
                    deviceMarkerPublishingSuspended = false
                    continuation.yield(.ready)
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
            do {
                try await self.ensureFolders(client: client)
                await self.removeStaleR2CDeviceMarkers(client: client)
                await self.finishMapOpenRetry()
                self.continuation.yield(.ready)
                await self.recoverInterruptedPublications(client: client)
            } catch {
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
        guard consent.allows(consentGeneration), !deviceMarkerPublishingSuspended, client != nil else { return }
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
        guard consent.allows(consentGeneration), !deviceMarkerPublishingSuspended, !markerCleanupInFlight, let client,
              let markerConfiguration = configuredConfiguration?.liveConfiguration else { return }
        let generation = deviceMarkerGeneration
        let now = Date()
        if !force, let lastDeviceMarkerPublishedAt,
           now.timeIntervalSince(lastDeviceMarkerPublishedAt) < 30 {
            return
        }
        do {
            try await ensureFolders(client: client)
            await recoverInterruptedPublications(client: client)
            await retryMarkerCleanup()
            guard consent.allows(consentGeneration), generation == deviceMarkerGeneration, !deviceMarkerPublishingSuspended else { return }
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
            try await ensureFolders(client: client)
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

    func finish(remoteID: String, description: String = "") async -> AppleArchivedCaltopoTrack? {
        guard !finishingRemoteIDs.contains(remoteID) else { return nil }
        finishingRemoteIDs.insert(remoteID)
        let generation = configurationGeneration
        let label = labels[remoteID] ?? remoteID
        let saved = observations[remoteID] ?? []
        // Queued points from a flight that never received a LiveTrack id must still
        // publish through the archive when a map is configured.
        var id = liveTrackIDs[remoteID] ?? requestedLiveTrackIDs[remoteID]
        if id == nil && !saved.isEmpty && configuredConfiguration != nil {
            let assigned = UUID().uuidString.lowercased()
            requestedLiveTrackIDs[remoteID] = assigned
            id = assigned
        }
        let pendingStart = startTasks[remoteID]
        let finishingClient = client
        let finishingConfiguration = configuredConfiguration
        await finishingClient?.stopPositionReports(remoteID: remoteID)
        defer {
            if generation == configurationGeneration {
                finishingRemoteIDs.remove(remoteID)
                startTasks.removeValue(forKey: remoteID)
                liveTrackIDs.removeValue(forKey: remoteID)
                requestedLiveTrackIDs.removeValue(forKey: remoteID)
                nextStartAttemptAt.removeValue(forKey: remoteID)
                lastInterruptedJournalWriteAt.removeValue(forKey: remoteID)
                labels.removeValue(forKey: remoteID)
                observations.removeValue(forKey: remoteID)
            }
        }
        // A map disconnect may already have cleared the live client while this
        // flight was still airborne. Persist the finish intent first so reconnect
        // recovery can convert the LiveTrack once, exactly as a normal archive.
        guard let id else { return nil }
        let mapID = finishingConfiguration?.mapID ?? configuredConfiguration?.mapID
        guard let mapID, !mapID.isEmpty else { return nil }
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
              let configuration = finishingConfiguration else {
            // Ended offline after disconnect: the journal upsert above is the
            // durable pending finish; reconnect recovery loads it once.
            return nil
        }
        do {
            // Finish an in-flight create before the Shape write; a lost response does not
            // prevent conversion because the durable identity is already known.
            if let pendingStart { _ = try? await pendingStart.value }
            guard generation == configurationGeneration else { return nil }
            try await ensureFolders(client: client)
            guard generation == configurationGeneration, let archiveFolderID else { return nil }
            try await client.archiveLiveTrack(
                liveTrackID: id, label: label, observations: saved,
                folderID: archiveFolderID, description: description)
            try await interruptedJournal.remove(liveTrackID: id)
            continuation.yield(.trackStopped(remoteID))
            return AppleArchivedCaltopoTrack(
                liveTrackID: id, label: label, observations: saved, folderID: archiveFolderID,
                configurationGeneration: generation)
        } catch {
            if generation == configurationGeneration {
                pendingInterruptedRecoveries.removeAll { $0.liveTrackID == id }
                pendingInterruptedRecoveries.append(entry)
            }
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

    private func ensureFolders(client: CaltopoLiveClient) async throws {
        guard trackFolderID == nil || archiveFolderID == nil else { return }
        let generation = configurationGeneration
        let resolver = folderResolver
        let folderName = trackFolderName
        let resolved = try await resolver.resolve(
            trackFolderName: folderName,
            settleDelay: .milliseconds(500),
            fetchSnapshot: {
                // A second folder/race inspection refreshes the existing snapshot with a delta.
                try await client.fetchMapArtifacts(refresh: true)
            },
            createFolder: { title, visible, labelVisible in
                try await client.createFolder(
                    title: title,
                    visible: visible,
                    labelVisible: labelVisible
                )
            },
            deleteFolder: { folderID in
                try await client.deleteFolder(folderID: folderID)
                AppleLog.info(
                    "CalTopo",
                    "Removed empty duplicate track folder id=\(folderID)"
                )
            }
        )
        guard generation == configurationGeneration else { throw CancellationError() }
        trackFolderID = resolved.active
        archiveFolderID = resolved.archive
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

    /// Network-path wake: retry interrupted LiveTrack archive recovery without waiting on clue work.
    func retryInterruptedPublications() async {
        await retryInterruptedPublications(generation: configurationGeneration)
    }

    private func retryInterruptedPublications(generation: Int) async {
        if generation == configurationGeneration && !configurationTransitionInFlight { await retryMarkerCleanup() }
        guard generation == configurationGeneration, !configurationTransitionInFlight,
              !pendingInterruptedRecoveries.isEmpty, let client else { return }
        do {
            try await ensureFolders(client: client)
            guard generation == configurationGeneration else { return }
            await recoverInterruptedPublications(client: client)
        } catch {
            AppleLog.warning("CalTopo", "Pending track recovery deferred: \(error.localizedDescription)")
        }
    }

    private func recoverInterruptedPublications(client: CaltopoLiveClient) async {
        guard !recoveryInFlight, let archiveFolderID, !pendingInterruptedRecoveries.isEmpty else { return }
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
                try await client.archiveLiveTrack(
                    liveTrackID: entry.liveTrackID,
                    label: entry.label,
                    observations: entry.observations,
                    folderID: archiveFolderID,
                    description: entry.description
                )
                try await interruptedJournal.remove(liveTrackID: entry.liveTrackID)
                completed.insert(entry.liveTrackID)
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

    private static func normalizedTrackFolderName(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Drone Tracks" : trimmed
    }

}
