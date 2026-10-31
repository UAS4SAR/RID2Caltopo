package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.ncssar.rid2caltopo.app.R2CActivity;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/**
 * Default app runtime peer coordinator.
 *
 * This is the first extraction seam for multi-runtime testing: callers can
 * depend on the {@link PeerCoordinator} interface today while the production
 * app continues to route through the existing static {@link R2CMqttManager}.
 */
public final class DefaultPeerCoordinator implements PeerCoordinator {
    public static final String TRACKER_REENROLLMENT_REQUIRED_STATUS =
            "Tracker authorization rejected; re-enrollment required";
    private static final DefaultPeerCoordinator INSTANCE = new DefaultPeerCoordinator();
    @Nullable private static volatile PeerCoordinator mqttCoordinatorOverrideForTesting;

    private static final class ActiveTrackRegistration {
        @NonNull final LiveTrackOwnerDelegate liveTrack;
        @NonNull final CtDroneSpec droneSpec;
        final double distMeters;
        final long firstSeenTs;
        volatile boolean coordinatorActivated;

        ActiveTrackRegistration(@NonNull LiveTrackOwnerDelegate liveTrack,
                                @NonNull CtDroneSpec droneSpec,
                                double distMeters,
                                long firstSeenTs) {
            this.liveTrack = liveTrack;
            this.droneSpec = droneSpec;
            this.distMeters = distMeters;
            this.firstSeenTs = firstSeenTs;
            this.coordinatorActivated = false;
        }
    }

    @NonNull private final Map<String, ActiveTrackRegistration> activeTracks = new ConcurrentHashMap<>();
    @Nullable private volatile R2CMqttManager.PeerListChangedListener peerListChangedListener;
    @Nullable private volatile CoordinationIndicatorListener coordinationIndicatorListener;
    @Nullable private volatile VideoStreamRequestListener videoStreamRequestListener;
    @NonNull private volatile String managedVideoIncidentName = "";
    @NonNull private volatile List<ManagedVideoStreamAdvertisement> managedVideoStreams =
            java.util.Collections.emptyList();
    @Nullable private volatile String startedMapId;
    @Nullable private volatile String startedGuid;
    @Nullable private volatile String startedName;
    @Nullable private volatile String startedBrokerUri;
    private volatile boolean trackerSelected;
    private volatile boolean trackerFallbackActive;
    @NonNull private volatile String trackerUnavailableDetail = "";
    @Nullable private String lastReenrollmentNoticeTrackerApiKey;
    private volatile double myLat;
    private volatile double myLon;
    private volatile long myCaltopoRttMs = 2_000L;
    private volatile boolean standaloneStandbyEligible;
    @NonNull private volatile PeerCoordinator activeCoordinator = getMqttCoordinator();
    @NonNull private volatile CoordinationIndicatorState lastIndicatorState = CoordinationIndicatorState.UNCONFIGURED;
    @NonNull private volatile String lastLoggedStatusSignature = "";

    private DefaultPeerCoordinator() { }

    @NonNull
    public static DefaultPeerCoordinator getInstance() {
        return INSTANCE;
    }

    @Override
    public void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
        boolean nextTrackerSelected = shouldUseTrackerCoordinator();
        PeerCoordinator nextCoordinator = nextTrackerSelected
                ? TrackerPeerCoordinator.getInstance()
                : getMqttCoordinator();
        CoordinationIndicatorState currentState = activeCoordinator.getCoordinationIndicatorState();
        if (sameString(startedMapId, mapId) &&
                sameString(startedGuid, guid) &&
                sameString(startedName, name) &&
                sameString(startedBrokerUri, brokerUri) &&
                trackerSelected == nextTrackerSelected &&
                activeCoordinator == nextCoordinator) {
            String message = String.format(
                    "start(): ignoring duplicate start for mapId='%s' guid='%s' using %s coordination state=%s",
                    mapId,
                    guid,
                    trackerSelected ? "tracker" : "mqtt",
                    currentState
            );
            if (currentState == CoordinationIndicatorState.HEALTHY) {
                CaltopoClient.CTDebug("DefaultPeerCoord", message);
            } else {
                CaltopoClient.CTWarn(
                        "DefaultPeerCoord",
                        message + " and allowing the existing coordinator to recover in place."
                );
            }
            return;
        }
        startedMapId = mapId;
        startedGuid = guid;
        startedName = name;
        startedBrokerUri = brokerUri;
        trackerSelected = nextTrackerSelected;
        trackerFallbackActive = false;
        trackerUnavailableDetail = "";
        activeCoordinator = nextCoordinator;
        if (trackerSelected) {
            TrackerPeerCoordinator.getInstance().setHardFailureListener(this::handleTrackerHardFailure);
        }
        CaltopoClient.CTInfo(
                "DefaultPeerCoord",
                String.format(
                        "start(): using %s coordination for mapId='%s' guid='%s' trackerConfigured=%s brokerUriPresent=%s",
                        trackerSelected ? "tracker" : "mqtt",
                        mapId,
                        guid,
                        trackerSelected,
                        brokerUri != null && !brokerUri.isEmpty()
                )
        );
        if (peerListChangedListener != null) {
            activeCoordinator.setPeerListChangedListener(peerListChangedListener);
        }
        activeCoordinator.setCoordinationIndicatorListener(this::handleChildCoordinationIndicatorStateChanged);
        activeCoordinator.setVideoStreamRequestListener(videoStreamRequestListener);
        activeCoordinator.setStandaloneStandbyEligible(standaloneStandbyEligible);
        activeCoordinator.updateManagedVideoStreams(
                managedVideoIncidentName,
                managedVideoStreams
        );
        activeCoordinator.start(mapId, guid, name, brokerUri);
        activeCoordinator.updateCaltopoRtt(myCaltopoRttMs);
        activeCoordinator.updateMyPosition(myLat, myLon);
        replayConfirmedTracks();
        emitCoordinationIndicatorIfChanged();
    }

    @Override
    public void setStandaloneStandbyEligible(boolean eligible) {
        standaloneStandbyEligible = eligible;
        activeCoordinator.setStandaloneStandbyEligible(eligible);
    }

    @Override
    public void stop() {
        if (trackerSelected) {
            TrackerPeerCoordinator.getInstance().setHardFailureListener(null);
        }
        activeCoordinator.stop();
        activeCoordinator.setCoordinationIndicatorListener(null);
        trackerSelected = false;
        trackerFallbackActive = false;
        trackerUnavailableDetail = "";
        startedMapId = null;
        startedGuid = null;
        startedName = null;
        startedBrokerUri = null;
        activeTracks.clear();
        emitCoordinationIndicatorIfChanged();
    }

    @Override
    public synchronized void resumeAfterReauthentication() {
        if (startedMapId == null || startedGuid == null || startedName == null) {
            CaltopoClient.CTDebug(
                    "DefaultPeerCoord",
                    "resumeAfterReauthentication(): coordination has not started yet; normal startup will connect."
            );
            return;
        }
        if (!shouldUseTrackerCoordinator()) {
            CaltopoClient.CTWarn(
                    "DefaultPeerCoord",
                    "resumeAfterReauthentication(): tracker is no longer configured."
            );
            return;
        }

        PeerCoordinator trackerCoordinator = TrackerPeerCoordinator.getInstance();
        if (activeCoordinator != trackerCoordinator || !trackerSelected) {
            trackerFallbackActive = false;
            trackerUnavailableDetail = "";
            switchToCoordinator(trackerCoordinator, true);
            return;
        }

        CaltopoClient.CTInfo(
                "DefaultPeerCoord",
                "resumeAfterReauthentication(): reconnecting tracker coordination in place."
        );
        TrackerPeerCoordinator.getInstance().setHardFailureListener(this::handleTrackerHardFailure);
        trackerCoordinator.setPeerListChangedListener(peerListChangedListener);
        trackerCoordinator.setCoordinationIndicatorListener(this::handleChildCoordinationIndicatorStateChanged);
        trackerCoordinator.setVideoStreamRequestListener(videoStreamRequestListener);
        trackerCoordinator.updateManagedVideoStreams(managedVideoIncidentName, managedVideoStreams);
        trackerCoordinator.start(startedMapId, startedGuid, startedName, startedBrokerUri);
        trackerCoordinator.updateCaltopoRtt(myCaltopoRttMs);
        trackerCoordinator.updateMyPosition(myLat, myLon);
        for (ActiveTrackRegistration registration : activeTracks.values()) {
            if (!registration.coordinatorActivated) continue;
            trackerCoordinator.onLiveTrackCreated(
                    registration.liveTrack,
                    registration.droneSpec,
                    registration.distMeters,
                    registration.firstSeenTs
            );
        }
        emitCoordinationIndicatorIfChanged();
    }

    @Override
    public void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
        if (droneSpec.isLocalArchiveOnly()) {
            liveTrack.setLocalOwner(false);
            activeTracks.remove(droneSpec.getRemoteId());
            return;
        }
        ActiveTrackRegistration registration =
                new ActiveTrackRegistration(liveTrack, droneSpec, distMeters, firstSeenTs);
        activeTracks.put(droneSpec.getRemoteId(), registration);
        if (!CaltopoClient.IsCurrentPeerDroneConfirmed(droneSpec.getRemoteId())) {
            liveTrack.setLocalOwner(false);
            CaltopoClient.CTInfo(
                    "DefaultPeerCoord",
                    "onLiveTrackCreated(): buffering remoteId=" + droneSpec.getRemoteId() +
                            " pending operator confirmation."
            );
            return;
        }
        activateRegistration(registration);
    }

    @Override
    public void onWaypointReceived(@NonNull CtDroneSpec droneSpec,
                                   double droneLat,
                                   double droneLon,
                                   double droneAlt,
                                   double distMeters,
                                   long timestampMsec,
                                   @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        ActiveTrackRegistration registration = activeTracks.get(droneSpec.getRemoteId());
        if (registration == null ||
                !registration.coordinatorActivated ||
                !CaltopoClient.IsCurrentPeerDroneConfirmed(droneSpec.getRemoteId())) {
            if (trackerSelected) {
                activeCoordinator.onTrafficPositionReceived(
                        droneSpec, droneLat, droneLon, droneAlt, timestampMsec, telemetry);
            }
            return;
        }
        activeCoordinator.onWaypointReceived(
                droneSpec,
                droneLat,
                droneLon,
                droneAlt,
                distMeters,
                timestampMsec,
                telemetry
        );
    }

    @Override
    public void onSEIPositionReceived(@NonNull String remoteId,
                                      @NonNull String mappedId,
                                      double droneLat,
                                      double droneLon,
                                      double droneAlt,
                                      long timestampMsec,
                                      long altitudeTimestampMsec,
                                      @Nullable Double headingDegrees) {
        if (!trackerSelected) return;
        activeCoordinator.onSEIPositionReceived(
                remoteId, mappedId, droneLat, droneLon, droneAlt,
                timestampMsec, altitudeTimestampMsec, headingDegrees);
    }

    @Override
    public void onDroneLost(@NonNull String remoteId) {
        ActiveTrackRegistration registration = activeTracks.remove(remoteId);
        if (registration != null &&
                (registration.droneSpec.isLocalArchiveOnly() || !registration.coordinatorActivated)) return;
        activeCoordinator.onDroneLost(remoteId);
    }

    @Override
    public void onDroneConfirmed(@NonNull String remoteId,
                                 @NonNull String org,
                                 @NonNull String model,
                                 @NonNull String owner,
                                 @NonNull String mappedId) {
        CaltopoClient.CTInfo(
                "DefaultPeerCoord",
                String.format(
                        "onDroneConfirmed(): forwarding remoteId=%s mappedId='%s' via %s",
                        remoteId,
                        mappedId,
                        activeCoordinator.getClass().getSimpleName()
                )
        );
        activeCoordinator.onDroneConfirmed(
                remoteId,
                org,
                model,
                owner,
                mappedId
        );
        ActiveTrackRegistration registration = activeTracks.get(remoteId);
        if (registration != null &&
                !registration.coordinatorActivated &&
                !registration.droneSpec.isLocalArchiveOnly() &&
                CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId)) {
            activateRegistration(registration);
        }
    }

    @Override
    public boolean isLocalOwner(@NonNull String remoteId) {
        ActiveTrackRegistration registration = activeTracks.get(remoteId);
        if (registration != null && !registration.coordinatorActivated) return false;
        return activeCoordinator.isLocalOwner(remoteId);
    }

    @Override
    public boolean isLocalAlertEligible(@NonNull String remoteId) {
        if (!trackerSelected) return true;
        return activeCoordinator.isLocalAlertEligible(remoteId);
    }

    @Override
    public void updateCaltopoRtt(long rttMs) {
        myCaltopoRttMs = rttMs;
        activeCoordinator.updateCaltopoRtt(rttMs);
    }

    @Override
    public long getCaltopoRttMs() {
        return myCaltopoRttMs;
    }

    @Override
    public void updateMyPosition(double lat, double lon) {
        myLat = lat;
        myLon = lon;
        activeCoordinator.updateMyPosition(lat, lon);
    }

    @Override
    public void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener) {
        peerListChangedListener = listener;
        activeCoordinator.setPeerListChangedListener(listener);
    }

    @Override
    public void setCoordinationIndicatorListener(@Nullable CoordinationIndicatorListener listener) {
        coordinationIndicatorListener = listener;
        if (listener != null) {
            listener.onCoordinationIndicatorStateChanged(getCoordinationIndicatorState());
        }
    }

    @Override
    public void setVideoStreamRequestListener(
            @Nullable VideoStreamRequestListener listener) {
        videoStreamRequestListener = listener;
        activeCoordinator.setVideoStreamRequestListener(listener);
    }

    @Override
    public void updateManagedVideoStreams(
            @NonNull String incidentName,
            @NonNull List<ManagedVideoStreamAdvertisement> streams) {
        managedVideoIncidentName = incidentName;
        managedVideoStreams = java.util.Collections.unmodifiableList(
                new ArrayList<>(streams)
        );
        activeCoordinator.updateManagedVideoStreams(
                managedVideoIncidentName,
                managedVideoStreams
        );
    }

    @NonNull
    @Override
    public List<ManagedVideoStreamAdvertisement> getManagedVideoStreams() {
        return managedVideoStreams;
    }

    @Override
    public boolean shouldRefreshManagedVideoThumbnails() {
        return activeCoordinator.shouldRefreshManagedVideoThumbnails();
    }

    @Override
    public void respondToVideoStreamRequest(
            @NonNull String requestId,
            boolean approved,
            int selectedWidth,
            int selectedHeight,
            double selectedFps,
            long selectedBitrateBps) {
        activeCoordinator.respondToVideoStreamRequest(
                requestId,
                approved,
                selectedWidth,
                selectedHeight,
                selectedFps,
                selectedBitrateBps
        );
    }

    @Override
    public void sendVideoMediaAnswer(@NonNull String requestId, @NonNull String sdp) {
        activeCoordinator.sendVideoMediaAnswer(requestId, sdp);
    }

    @Override
    public void sendVideoStreamTerminated(@NonNull String requestId, @NonNull String reason) {
        activeCoordinator.sendVideoStreamTerminated(requestId, reason);
    }

    @Override
    public void respondToRecordingDownloadRequest(
            @NonNull String requestId, boolean approved) {
        activeCoordinator.respondToRecordingDownloadRequest(requestId, approved);
    }

    @Override
    public void uploadRecordingDownload(@NonNull RecordingDownloadRequest request) {
        activeCoordinator.uploadRecordingDownload(request);
    }

    @NonNull
    @Override
    public List<R2CMqttManager.PeerState> getPeerList() {
        return activeCoordinator.getPeerList();
    }

    @NonNull
    @Override
    public CoordinationIndicatorState getCoordinationIndicatorState() {
        boolean peersEnabled = CaltopoClient.GetUsePeersFlag();
        boolean trackerConfigured = !CaltopoClient.GetTrackerCoordinationUrlPfx().isEmpty()
                && !CaltopoClient.GetTrackerCoordinationApiKey().isEmpty();
        boolean mqttConfigured = peersEnabled;

        if (isStandaloneTrackerCoordinationDisabled()) {
            return CoordinationIndicatorState.UNCONFIGURED;
        }

        if (!trackerConfigured && !mqttConfigured) {
            return CoordinationIndicatorState.UNCONFIGURED;
        }

        if (isCoordinatorUnavailable()) {
            return CoordinationIndicatorState.UNCONFIGURED;
        }

        CoordinationIndicatorState activeState = activeCoordinator.getCoordinationIndicatorState();
        if (activeState == CoordinationIndicatorState.HEALTHY ||
                activeState == CoordinationIndicatorState.IDLE) {
            return activeState;
        }

        if (trackerConfigured || mqttConfigured) {
            return CoordinationIndicatorState.DEGRADED;
        }

        return CoordinationIndicatorState.UNCONFIGURED;
    }

    @NonNull
    @Override
    public String getCoordinationStatusText() {
        if (isCoordinatorUnavailable()) {
            if (trackerFallbackActive) {
                return TRACKER_REENROLLMENT_REQUIRED_STATUS;
            }
            return "Coordinator unavailable";
        }
        if (isStandaloneTrackerCoordinationDisabled()) {
            return "Tracker link disabled";
        }
        CoordinationIndicatorState state = getCoordinationIndicatorState();
        if (state == CoordinationIndicatorState.UNCONFIGURED) {
            return "R2C link not configured";
        }
        String channel = isTrackerConfiguredForCoordination() ? "Tracker" : "MQTT";
        if (state == CoordinationIndicatorState.IDLE) {
            return channel + " link standby";
        }
        if (state == CoordinationIndicatorState.HEALTHY) {
            return isTrackerConfiguredForCoordination()
                    ? "Tracker verified"
                    : "MQTT link healthy";
        }
        return channel + " link degraded";
    }

    @NonNull
    @Override
    public List<String> getCoordinationDiagnosticLines() {
        if (isCoordinatorUnavailable()) {
            ArrayList<String> unavailableLines = new ArrayList<>();
            unavailableLines.add(trackerUnavailableDetail.isEmpty()
                    ? "Tracker not configured"
                    : trackerUnavailableDetail);
            unavailableLines.add(describePeers(getPeerList()));
            return unavailableLines;
        }
        ArrayList<String> lines = new ArrayList<>(activeCoordinator.getCoordinationDiagnosticLines());
        if (isStandaloneTrackerCoordinationDisabled()) {
            lines.add("Standalone tracker coordination disabled");
        } else if (isTrackerConfiguredForCoordination() && !trackerSelected) {
            lines.add("Tracker coordinator waiting for map connection");
        }
        lines.add(describePeers(getPeerList()));
        return lines;
    }

    private void handleChildCoordinationIndicatorStateChanged(@NonNull CoordinationIndicatorState ignoredState) {
        emitCoordinationIndicatorIfChanged();
    }

    private synchronized void emitCoordinationIndicatorIfChanged() {
        CoordinationIndicatorState current = getCoordinationIndicatorState();
        logCoordinationStatusIfChanged(current);
        if (current == lastIndicatorState) return;
        lastIndicatorState = current;
        CoordinationIndicatorListener listener = coordinationIndicatorListener;
        if (listener != null) {
            listener.onCoordinationIndicatorStateChanged(current);
        }
    }

    private void logCoordinationStatusIfChanged(@NonNull CoordinationIndicatorState state) {
        String statusText = getCoordinationStatusText();
        String signature = state.name() + "|" + statusText;
        if (signature.equals(lastLoggedStatusSignature)) return;
        lastLoggedStatusSignature = signature;

        StringBuilder diagnostics = new StringBuilder();
        for (String line : getCoordinationDiagnosticLines()) {
            if (line == null || line.isEmpty()) continue;
            if (diagnostics.length() > 0) diagnostics.append(" | ");
            diagnostics.append(line);
        }
        String diagnosticText = diagnostics.length() > 0
                ? " diagnostics=\"" + diagnostics + "\""
                : "";
        CaltopoClient.CTInfo(
                "DefaultPeerCoord",
                "coordination status changed: state=" + state +
                        " status=\"" + statusText + "\"" +
                        diagnosticText
        );
    }

    private boolean shouldUseTrackerCoordinator() {
        return CaltopoClient.GetUsePeersFlag()
                && isTrackerConfiguredForCoordination();
    }

    private boolean isTrackerConfiguredForCoordination() {
        return !CaltopoClient.GetTrackerApiKey().isEmpty()
                && !CaltopoClient.GetTrackerUrlPfx().isEmpty();
    }

    private boolean isStandaloneTrackerCoordinationDisabled() {
        return isTrackerConfiguredForCoordination()
                && !trackerFallbackActive
                && !trackerSelected
                && !CaltopoClient.GetStandaloneR2cCoordinationEnabled();
    }

    private boolean isCoordinatorUnavailable() {
        return CaltopoClient.GetUsePeersFlag()
                && (trackerFallbackActive || !isTrackerConfiguredForCoordination());
    }

    private void handleTrackerHardFailure(int responseCode, @Nullable String responseMessage) {
        if (!trackerSelected) return;
        boolean showReenrollmentNotice = markReenrollmentNoticeForCurrentCredential();
        trackerFallbackActive = true;
        trackerUnavailableDetail = "Tracker unavailable: HTTP " + responseCode +
                (responseMessage == null || responseMessage.isEmpty() ? "" : " " + responseMessage);
        CaltopoClient.CTWarn(
                "DefaultPeerCoord",
                String.format("Tracker coordination hard-failed with code=%d message='%s'; falling back to MQTT.",
                        responseCode, responseMessage == null ? "" : responseMessage)
        );
        if (showReenrollmentNotice) {
            R2CActivity activity = R2CActivity.getR2CActivity();
            if (activity != null) {
                activity.showTrackerReenrollmentRequired();
            }
        }
        switchToCoordinator(getMqttCoordinator(), false);
    }

    private synchronized boolean markReenrollmentNoticeForCurrentCredential() {
        String trackerApiKey = CaltopoClient.GetTrackerCoordinationApiKey().trim();
        if (!shouldShowReenrollmentNotice(lastReenrollmentNoticeTrackerApiKey, trackerApiKey)) {
            return false;
        }
        lastReenrollmentNoticeTrackerApiKey = trackerApiKey;
        return true;
    }

    static boolean shouldShowReenrollmentNotice(
            @Nullable String lastRejectedTrackerApiKey,
            @NonNull String currentTrackerApiKey) {
        return lastRejectedTrackerApiKey == null ||
                !lastRejectedTrackerApiKey.equals(currentTrackerApiKey);
    }

    private synchronized void switchToCoordinator(@NonNull PeerCoordinator coordinator, boolean tracker) {
        if (startedMapId == null || startedGuid == null || startedName == null) return;
        if (activeCoordinator == coordinator && trackerSelected == tracker) return;
        if (trackerSelected) {
            TrackerPeerCoordinator.getInstance().setHardFailureListener(null);
        }
        activeCoordinator.stop();
        activeCoordinator = coordinator;
        trackerSelected = tracker;
        if (peerListChangedListener != null) {
            activeCoordinator.setPeerListChangedListener(peerListChangedListener);
        }
        activeCoordinator.setCoordinationIndicatorListener(this::handleChildCoordinationIndicatorStateChanged);
        activeCoordinator.setStandaloneStandbyEligible(standaloneStandbyEligible);
        activeCoordinator.start(startedMapId, startedGuid, startedName, startedBrokerUri);
        activeCoordinator.updateCaltopoRtt(myCaltopoRttMs);
        activeCoordinator.updateMyPosition(myLat, myLon);
        for (ActiveTrackRegistration registration : activeTracks.values()) {
            if (!registration.coordinatorActivated) continue;
            activeCoordinator.onLiveTrackCreated(
                    registration.liveTrack,
                    registration.droneSpec,
                    registration.distMeters,
                    registration.firstSeenTs
            );
        }
        emitCoordinationIndicatorIfChanged();
    }

    private void activateRegistration(@NonNull ActiveTrackRegistration registration) {
        if (registration.coordinatorActivated) return;
        registration.coordinatorActivated = true;
        CaltopoClient.CTInfo(
                "DefaultPeerCoord",
                "activateRegistration(): operator-confirmed remoteId=" +
                        registration.droneSpec.getRemoteId()
        );
        replayConfirmation(registration);
        activeCoordinator.onLiveTrackCreated(
                registration.liveTrack,
                registration.droneSpec,
                registration.distMeters,
                registration.firstSeenTs
        );
    }

    /** Tracks confirmed before this coordinator started still need their Save replayed. */
    private void replayConfirmedTracks() {
        for (ActiveTrackRegistration registration : activeTracks.values()) {
            if (registration.droneSpec.isLocalArchiveOnly()) continue;
            if (!CaltopoClient.IsCurrentPeerDroneConfirmed(registration.droneSpec.getRemoteId())) continue;
            if (!registration.coordinatorActivated) {
                activateRegistration(registration);
                continue;
            }
            replayConfirmation(registration);
            activeCoordinator.onLiveTrackCreated(
                    registration.liveTrack,
                    registration.droneSpec,
                    registration.distMeters,
                    registration.firstSeenTs
            );
        }
    }

    private void replayConfirmation(@NonNull ActiveTrackRegistration registration) {
        CtDroneSpec spec = registration.droneSpec;
        if (!CaltopoClient.IsCurrentPeerDroneConfirmed(spec.getRemoteId())) return;
        activeCoordinator.onDroneConfirmed(
                spec.getRemoteId(),
                spec.getOrg() == null ? "" : spec.getOrg(),
                spec.getModel() == null ? "" : spec.getModel(),
                spec.getOwner() == null ? "" : spec.getOwner(),
                spec.getMappedId() == null ? "" : spec.getMappedId());
    }

    @NonNull
    private static PeerCoordinator getMqttCoordinator() {
        return mqttCoordinatorOverrideForTesting != null
                ? mqttCoordinatorOverrideForTesting
                : R2CMqttManager.GetDefaultCoordinator();
    }

    static void setMqttCoordinatorOverrideForTesting(@Nullable PeerCoordinator coordinator) {
        mqttCoordinatorOverrideForTesting = coordinator;
    }

    private static boolean sameString(@Nullable String left, @Nullable String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    @NonNull
    private static String describePeers(@NonNull List<R2CMqttManager.PeerState> peers) {
        if (peers.isEmpty()) return "Peers: none";
        int onlineCount = 0;
        StringBuilder names = new StringBuilder();
        int listed = 0;
        for (R2CMqttManager.PeerState peer : peers) {
            if (peer.online) onlineCount++;
            if (listed >= 3) continue;
            String name = peer.name != null && !peer.name.isEmpty() ? peer.name : peer.guid;
            if (name == null || name.isEmpty()) continue;
            if (names.length() > 0) names.append(", ");
            names.append(name);
            listed++;
        }
        String suffix = names.length() > 0 ? " (" + names + ")" : "";
        if (peers.size() > listed) {
            suffix = suffix.isEmpty()
                    ? " (+" + (peers.size() - listed) + ")"
                    : suffix.substring(0, suffix.length() - 1) + ", +" + (peers.size() - listed) + ")";
        }
        return "Peers: " + onlineCount + "/" + peers.size() + " online" + suffix;
    }
}
