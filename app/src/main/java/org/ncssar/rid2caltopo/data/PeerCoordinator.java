package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

public interface PeerCoordinator {
    interface CoordinationIndicatorListener {
        void onCoordinationIndicatorStateChanged(@NonNull CoordinationIndicatorState state);
    }
    interface VideoStreamRequestListener {
        void onVideoStreamRequest(@NonNull VideoStreamViewRequest request);
        default void onVideoPreflightResult(
                @NonNull String requestId,
                @NonNull String routeKind,
                long estimatedUplinkBps) {
        }
        default void onVideoPreflightFailure(
                @NonNull String requestId,
                @NonNull String reason) {
        }
        default void onVideoStreamRequestCancelled(@NonNull String requestId) {
        }
        default void onVideoMediaOffer(@NonNull VideoMediaOffer offer) {
        }
        default void onRecordingDownloadRequest(@NonNull RecordingDownloadRequest request) {
        }
    }

    enum CoordinationIndicatorState {
        HEALTHY,
        IDLE,
        DEGRADED,
        UNCONFIGURED
    }

    void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri);
    default void setStandaloneStandbyEligible(boolean eligible) {
    }
    default void resumeAfterReauthentication() {
    }
    void stop();
    void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                            @NonNull CtDroneSpec droneSpec,
                            double distMeters,
                            long firstSeenTs);
    void onWaypointReceived(@NonNull CtDroneSpec droneSpec,
                            double droneLat,
                            double droneLon,
                            double droneAlt,
                            double distMeters,
                            long timestampMsec,
                            @Nullable CtDroneSpec.PositionTelemetry telemetry);
    default void onTrafficPositionReceived(@NonNull CtDroneSpec droneSpec,
                                           double droneLat,
                                           double droneLon,
                                           double droneAlt,
                                           long timestampMsec,
                                           @Nullable CtDroneSpec.PositionTelemetry telemetry) {
    }
    default void onSEIPositionReceived(@NonNull String remoteId,
                                       @NonNull String mappedId,
                                       double droneLat,
                                       double droneLon,
                                       double droneAlt,
                                       long timestampMsec,
                                       long altitudeTimestampMsec,
                                       @Nullable Double headingDegrees) {
    }
    void onDroneLost(@NonNull String remoteId);
    default void onDroneConfirmed(@NonNull String remoteId,
                                  @NonNull String org,
                                  @NonNull String model,
                                  @NonNull String owner,
                                  @NonNull String mappedId) {
    }
    boolean isLocalOwner(@NonNull String remoteId);
    default boolean isLocalAlertEligible(@NonNull String remoteId) {
        return true;
    }
    /** True when a peer (not this device) currently holds the flight owner lease. */
    default boolean hasActivePeerOwner(@NonNull String remoteId) {
        return false;
    }
    /** Owner zone guid for {@code remoteId}, or empty when none. */
    @NonNull
    default String ownerGuidForRemoteId(@NonNull String remoteId) {
        return "";
    }
    /** This device's coordination zone id, or empty when not started. */
    @NonNull
    default String localZoneId() {
        return "";
    }
    /**
     * Short {@code /v/} URL for a recording hosted by a peer tablet, from the in-memory peer
     * presence last received. Never blocks or touches disk; null when nothing matches.
     */
    @Nullable
    default String peerRecordingShortUrl(
            @NonNull String[] candidates, long trackStartedAtMs, long trackEndedAtMs) {
        return null;
    }
    void updateCaltopoRtt(long rttMs);
    long getCaltopoRttMs();
    void updateMyPosition(double lat, double lon);
    void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener);
    void setCoordinationIndicatorListener(@Nullable CoordinationIndicatorListener listener);
    default void setVideoStreamRequestListener(
            @Nullable VideoStreamRequestListener listener) {
    }
    default void updateManagedVideoStreams(
            @NonNull String incidentName,
            @NonNull List<ManagedVideoStreamAdvertisement> streams) {
    }
    @NonNull
    default List<ManagedVideoStreamAdvertisement> getManagedVideoStreams() {
        return Collections.emptyList();
    }
    default boolean shouldRefreshManagedVideoThumbnails() {
        return false;
    }
    /**
     * True while coordination owns work that must keep the incident-map session alive.
     * Lifecycle auto-disconnect uses this in addition to the local active-flight check.
     */
    default boolean hasOperationalActivityPreventingMapDisconnect() {
        return false;
    }
    /** True when configured tracker coordination has received no server acknowledgement
     * for at least the requested interval. Intentional healthy standby is not silence. */
    default boolean hasTrackerAcknowledgementSilence(long thresholdMs) {
        return false;
    }
    default void respondToVideoStreamRequest(
            @NonNull String requestId,
            boolean approved,
            int selectedWidth,
            int selectedHeight,
            double selectedFps,
            long selectedBitrateBps) {
    }
    default void sendVideoMediaAnswer(@NonNull String requestId, @NonNull String sdp) {
    }
    default void sendVideoStreamTerminated(@NonNull String requestId, @NonNull String reason) {
    }
    default void respondToRecordingDownloadRequest(@NonNull String requestId, boolean approved) {
    }
    default void uploadRecordingDownload(@NonNull RecordingDownloadRequest request) {
    }
    @NonNull List<R2CMqttManager.PeerState> getPeerList();
    @NonNull CoordinationIndicatorState getCoordinationIndicatorState();
    @NonNull
    default String getCoordinationStatusText() {
        switch (getCoordinationIndicatorState()) {
            case HEALTHY:
                return "R2C link healthy";
            case IDLE:
                return "R2C link standby";
            case DEGRADED:
                return "R2C link degraded";
            case UNCONFIGURED:
            default:
                return "R2C link not configured";
        }
    }
    @NonNull
    default List<String> getCoordinationDiagnosticLines() {
        return Collections.emptyList();
    }
}
