package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.content.Context;
import android.os.Build;

import org.ncssar.rid2caltopo.BuildConfig;
import org.ncssar.rid2caltopo.video.PeerTrafficMapRegistry;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.ncssar.rid2caltopo.app.R2CActivity;
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecording;
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecordingCatalog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Tracker-backed coordination channel that uses tracker.kjt.us as the public
 * rendezvous, lease, and optional sighting relay service.
 *
 * The tracker service assigns a single owner per drone stream. Only the owner
 * publishes ordered waypoints to CalTopo; non-owner sightings are forwarded to
 * the owner over the tracker websocket.
 */
public final class TrackerPeerCoordinator implements PeerCoordinator {
    private static final String TAG = "TrackerPeerCoord";
    private static final TrackerPeerCoordinator INSTANCE = new TrackerPeerCoordinator();
    interface TimeSource {
        long now();
    }
    interface HardFailureListener {
        void onHardFailure(int responseCode, @Nullable String responseMessage);
    }

    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final long HELLO_ACK_TIMEOUT_MS = 10_000L;
    private static final long HEARTBEAT_ACK_TIMEOUT_MS = 10_000L;
    private static final long ACK_WATCHDOG_INTERVAL_MS = 5_000L;
    private static final long HEARTBEAT_MIN_SEND_GAP_MS = 1_000L;
    private static final long SIGHTING_SEND_INTERVAL_MS = 3_000L;
    private static final long TRAFFIC_SEND_INTERVAL_MS = 1_000L;
    private static final long TRAFFIC_SEND_INTERVAL_MAX_MS = 16_000L;
    private static final long TRAFFIC_DIAGNOSTIC_INTERVAL_MS = 5_000L;
    private static final long OWNER_ACTIVITY_HEARTBEAT_SUPPRESS_MS = 30_000L;
    private static final long OWNER_ACTIVITY_MAX_HEARTBEAT_SILENCE_MS = 45_000L;
    private static final long DEFAULT_IDLE_PARK_DELAY_MS = 30_000L;
    private static final long CONNECT_GRACE_MS = 12_000L;
    private static final long DEFAULT_HANDOFF_DELAY_MS = 2_000L;
    private static volatile long handoffDelayMs = DEFAULT_HANDOFF_DELAY_MS;
    private static volatile long idleParkDelayMs = DEFAULT_IDLE_PARK_DELAY_MS;
    private static final long RECONNECT_BASE_DELAY_MS = 2_000L;
    private static final long RECONNECT_MAX_DELAY_MS = 10_000L;
    private static final long VIDEO_PRESENCE_INTERVAL_MS = 15_000L;
    private static final long INCIDENT_STANDBY_SILENCE_MS = 60_000L;

    private static volatile TrackerCoordinationTransportFactory transportFactory =
            OkHttpTrackerCoordinationTransport::new;
    @NonNull private static volatile TimeSource timeSource = System::currentTimeMillis;
    @Nullable private static volatile String trackerApiKeyOverrideForTesting;
    @Nullable private static volatile String trackerUrlPrefixOverrideForTesting;

    private static final class PendingDrone {
        @NonNull final LiveTrackOwnerDelegate liveTrack;
        @NonNull final CtDroneSpec droneSpec;
        final double distMeters;
        final long firstSeenTs;
        @NonNull final DelayedExec fallbackTimer = new DelayedExec(false);
        @NonNull final DelayedExec ownershipActivationTimer = new DelayedExec(false);
        volatile boolean firstSightingSent;

        PendingDrone(@NonNull LiveTrackOwnerDelegate liveTrack,
                     @NonNull CtDroneSpec droneSpec,
                     double distMeters,
                     long firstSeenTs) {
            this.liveTrack = liveTrack;
            this.droneSpec = droneSpec;
            this.distMeters = distMeters;
            this.firstSeenTs = firstSeenTs;
        }
    }

    @NonNull private final ConcurrentHashMap<String, PendingDrone> pendingDrones = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, JSONObject> pendingConfirmationsByRemoteId = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, R2CMqttManager.PeerState> peers = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, String> ownerByRemoteId = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, Long> leaseSeqByRemoteId = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, Boolean> locallyConfirmedRemoteIds = new ConcurrentHashMap<>();
    @NonNull private final LinkedHashSet<String> seenVideoStreamRequestIds =
            new LinkedHashSet<>();
    @NonNull private final DelayedExec heartbeatTimer = new DelayedExec(false);
    @NonNull private final DelayedExec reconnectTimer = new DelayedExec(false);
    @NonNull private final DelayedExec ackWatchdogTimer = new DelayedExec(false);
    @NonNull private final DelayedExec heartbeatCoalesceTimer = new DelayedExec(false);
    @NonNull private final DelayedExec idleParkTimer = new DelayedExec(false);
    @NonNull private final DelayedExec videoPresenceTimer = new DelayedExec(false);
    @NonNull private final ConcurrentHashMap<String, Long> lastSightingSentByRemoteId = new ConcurrentHashMap<>();
    @NonNull private final String trafficSourceEpoch = UUID.randomUUID().toString();
    @NonNull private final ConcurrentHashMap<String, Long> trafficSequenceByKey = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, Long> lastTrafficSentByKey = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, Long> trafficSendIntervalByKey = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, Long> lastTrafficSentLogByKey = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, Long> lastTrafficShadowLogByKey = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, RecordingDownloadRequest>
            approvedRecordingUploads = new ConcurrentHashMap<>();
    @NonNull private final Set<String> activeTrackerInteractionRequestIds =
            ConcurrentHashMap.newKeySet();
    @NonNull private final ManagedVideoPreflightPeer videoPreflightPeer;
    @Nullable private volatile String lastOutboundJsonForTesting;
    @Nullable private volatile String lastWaypointRemoteIdForTesting;

    @Nullable private volatile TrackerCoordinationTransport transport;
    @Nullable private volatile R2CMqttManager.PeerListChangedListener peerListChangedListener;
    @Nullable private volatile CoordinationIndicatorListener coordinationIndicatorListener;
    @Nullable private volatile VideoStreamRequestListener videoStreamRequestListener;
    @NonNull private volatile String managedVideoIncidentName = "";
    @NonNull private volatile List<ManagedVideoStreamAdvertisement> managedVideoStreams =
            Collections.emptyList();
    private volatile long managedVideoThumbnailPreviewUntilMs;
    @Nullable private volatile HardFailureListener hardFailureListener;

    @Nullable private volatile String mapId;
    @Nullable private volatile String myGuid;
    @Nullable private volatile String myName;
    @Nullable private volatile String trackerApiKey;
    @Nullable private volatile String trackerWsUrl;
    @Nullable private volatile String trackerHttpOrigin;
    @NonNull private final ExecutorService recordingUploadExecutor =
            Executors.newSingleThreadExecutor();
    private volatile double myLat;
    private volatile double myLon;
    private volatile long myCaltopoRttMs = 2_000L;
    private volatile boolean started;
    private volatile boolean hardFailureNotified;
    private volatile long nextReconnectDelayMs = RECONNECT_BASE_DELAY_MS;
    private volatile long helloSeqSentAtMs;
    private volatile long helloAckAtMs;
    private volatile long heartbeatSeqCounter;
    private volatile long lastHeartbeatSeqSent;
    private volatile long lastHeartbeatSeqAcked;
    private volatile long lastHeartbeatSentAtMs;
    private volatile long lastHeartbeatAckAtMs;
    private volatile long coordinationAttemptStartedAtMs;
    private volatile long lastServerAcknowledgementAtMs;
    private volatile long lastOwnerLeaseExpireTs;
    private volatile long lastOwnerActivityAtMs;
    private volatile int lastCloseCode;
    @NonNull private volatile String lastCloseReason = "";
    @NonNull private volatile String lastReconnectCause = "";
    private volatile long forcedReconnectCount;
    private volatile boolean heartbeatSendQueued;
    private volatile boolean intentionallyParked;
    private volatile boolean standaloneStandbyEligible;
    private volatile long reconnectScheduledAtMs;
    private volatile long reconnectTargetAtMs;
    private volatile boolean reconnectPending;
    private volatile boolean suppressScheduledHeartbeatRequestsForTesting;

    private TrackerPeerCoordinator() {
        videoPreflightPeer = new ManagedVideoPreflightPeer(
                new ManagedVideoPreflightPeer.Sink() {
                    @Override
                    public void sendAnswer(
                            @NonNull String requestId,
                            @NonNull String sdp) {
                        sendVideoPreflightAnswer(requestId, sdp);
                    }

                    @Override
                    public void sendResult(
                            @NonNull String requestId,
                            @NonNull String routeKind,
                            long estimatedUplinkBps) {
                        sendVideoPreflightResult(
                                requestId,
                                routeKind,
                                estimatedUplinkBps);
                        VideoStreamRequestListener listener =
                                videoStreamRequestListener;
                        if (listener != null) {
                            listener.onVideoPreflightResult(
                                    requestId,
                                    routeKind,
                                    estimatedUplinkBps);
                        }
                    }

                    @Override
                    public void onFailure(
                            @NonNull String requestId,
                            @NonNull String reason) {
                        CTWarn(
                                TAG,
                                "Managed video preflight failed request="
                                        + requestId
                                        + " reason="
                                        + reason);
                        VideoStreamRequestListener listener =
                                videoStreamRequestListener;
                        if (listener != null) {
                            listener.onVideoPreflightFailure(
                                    requestId,
                                    reason);
                        }
                    }
                });
    }

    @NonNull
    public static TrackerPeerCoordinator getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
        stop();

        String trackerUrlPrefix = trackerUrlPrefixOverrideForTesting != null
                ? trackerUrlPrefixOverrideForTesting
                : CaltopoClient.GetTrackerCoordinationUrlPfx();
        String trackerApiKey = trackerApiKeyOverrideForTesting != null
                ? trackerApiKeyOverrideForTesting
                : CaltopoClient.GetTrackerCoordinationApiKey();
        String normalizedTrackerApiKey = normalizeTrackerToken(trackerApiKey);
        if (!trackerApiKey.equals(normalizedTrackerApiKey)) {
            CTWarn(TAG, "start(): trimming tracker token whitespace before websocket auth.");
        }
        trackerApiKey = normalizedTrackerApiKey;
        if (trackerUrlPrefix.isEmpty() || trackerApiKey.isEmpty()) {
            CTWarn(TAG, "start(): tracker coordination not configured; falling back to local ownership.");
            this.mapId = mapId;
            this.myGuid = guid;
            this.myName = name;
            this.started = true;
            notifyCoordinationIndicatorListener();
            return;
        }

        this.mapId = mapId;
        this.myGuid = guid;
        this.myName = name;
        this.trackerApiKey = trackerApiKey;
        this.trackerWsUrl = buildTrackerWebSocketUrl(trackerUrlPrefix);
        this.trackerHttpOrigin = trackerHttpOrigin(trackerUrlPrefix);
        this.started = true;
        this.hardFailureNotified = false;
        this.nextReconnectDelayMs = RECONNECT_BASE_DELAY_MS;
        this.helloSeqSentAtMs = 0L;
        this.helloAckAtMs = 0L;
        this.heartbeatSeqCounter = 0L;
        this.lastHeartbeatSeqSent = 0L;
        this.lastHeartbeatSeqAcked = 0L;
        this.lastHeartbeatSentAtMs = 0L;
        this.lastHeartbeatAckAtMs = 0L;
        this.coordinationAttemptStartedAtMs = nowMs();
        this.lastServerAcknowledgementAtMs = 0L;
        this.lastOwnerLeaseExpireTs = 0L;
        this.lastOwnerActivityAtMs = 0L;
        this.lastCloseCode = 0;
        this.lastCloseReason = "";
        this.lastReconnectCause = "";
        this.forcedReconnectCount = 0L;
        this.reconnectScheduledAtMs = 0L;
        this.reconnectTargetAtMs = 0L;
        this.reconnectPending = false;
        this.suppressScheduledHeartbeatRequestsForTesting = false;
        this.intentionallyParked = false;
        CTInfo(TAG, String.format(Locale.US,
                "start(): wsUrl='%s' token=%s %s",
                this.trackerWsUrl,
                describeToken(trackerApiKey),
                CaltopoClient.DescribeTrackerCredentialSelection("coordination")));

        transport = transportFactory.create();
        transport.setCallback(new TrackerCoordinationTransport.Callback() {
            @Override
            public void onOpen() {
                CTInfo(TAG, "tracker websocket connected");
                reconnectTimer.stop();
                onTransportOpen(false);
            }

            @Override
            public void onMessage(@NonNull String text) {
                handleIncomingMessage(text);
            }

            @Override
            public void onClosed(int code, @NonNull String reason) {
                lastCloseCode = code;
                lastCloseReason = reason;
                CTWarn(TAG, String.format(Locale.US,
                        "tracker websocket closed: code=%d reason='%s'", code, reason));
                heartbeatTimer.stop();
                ackWatchdogTimer.stop();
                notifyCoordinationIndicatorListener();
                scheduleReconnect("closed", -1L);
            }

            @Override
            public void onFailure(@Nullable Throwable throwable, int responseCode, @Nullable String responseMessage) {
                if (throwable != null) {
                    String message = throwable.getMessage();
                    if (message == null || message.isEmpty()) {
                        message = throwable.getClass().getSimpleName();
                    }
                    if (throwable instanceof EOFException) {
                        CTInfo(TAG, "tracker websocket transient disconnect: " + message);
                    } else {
                        CTWarn(TAG, "tracker websocket failure: " + message);
                    }
                } else {
                    CTWarn(TAG, "tracker websocket failure: unknown");
                }
                lastCloseCode = responseCode;
                lastCloseReason = responseMessage != null ? responseMessage : "";
                heartbeatTimer.stop();
                ackWatchdogTimer.stop();
                notifyCoordinationIndicatorListener();
                notifyHardFailureIfNeeded(responseCode, responseMessage);
                scheduleReconnect("failure", -1L);
            }
        });
        transport.connect(this.trackerWsUrl, trackerApiKey);
        notifyCoordinationIndicatorListener();
    }

    @Override
    public synchronized void stop() {
        heartbeatTimer.stop();
        reconnectTimer.stop();
        ackWatchdogTimer.stop();
        heartbeatCoalesceTimer.stop();
        idleParkTimer.stop();
        videoPresenceTimer.stop();
        videoPreflightPeer.cancel();
        Iterator<Map.Entry<String, PendingDrone>> iterator = pendingDrones.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingDrone> entry = iterator.next();
            entry.getValue().fallbackTimer.stop();
            entry.getValue().ownershipActivationTimer.stop();
            entry.getValue().liveTrack.setLocalOwner(false);
            iterator.remove();
        }
        pendingConfirmationsByRemoteId.clear();
        locallyConfirmedRemoteIds.clear();
        peers.clear();
        ownerByRemoteId.clear();
        leaseSeqByRemoteId.clear();
        lastSightingSentByRemoteId.clear();
        trafficSequenceByKey.clear();
        lastTrafficSentByKey.clear();
        trafficSendIntervalByKey.clear();
        lastTrafficSentLogByKey.clear();
        lastTrafficShadowLogByKey.clear();
        PeerTrafficMapRegistry.clear();
        approvedRecordingUploads.clear();
        activeTrackerInteractionRequestIds.clear();
        notifyPeerListChanged();
        TrackerCoordinationTransport activeTransport = transport;
        transport = null;
        if (activeTransport != null) {
            activeTransport.disconnect();
        }
        started = false;
        coordinationAttemptStartedAtMs = 0L;
        lastServerAcknowledgementAtMs = 0L;
        managedVideoThumbnailPreviewUntilMs = 0L;
        hardFailureNotified = false;
        reconnectPending = false;
        intentionallyParked = false;
        notifyCoordinationIndicatorListener();
    }

    @Override
    public synchronized void setStandaloneStandbyEligible(boolean eligible) {
        standaloneStandbyEligible = eligible;
        if (!eligible) {
            idleParkTimer.stop();
        } else {
            scheduleIdleParkIfEligible();
        }
    }

    @Override
    public void updateManagedVideoStreams(
            @NonNull String incidentName,
            @NonNull List<ManagedVideoStreamAdvertisement> streams) {
        managedVideoIncidentName = incidentName.trim();
        managedVideoStreams = Collections.unmodifiableList(
                new ArrayList<>(streams.subList(0, Math.min(streams.size(), 4)))
        );
        if (hasLiveManagedVideoStream()) {
            wakeForCoordinationActivity("video_stream");
        } else {
            scheduleIdleParkIfEligible();
        }
        sendManagedVideoPresence();
    }

    @NonNull
    @Override
    public List<ManagedVideoStreamAdvertisement> getManagedVideoStreams() {
        return managedVideoStreams;
    }

    @Override
    public boolean shouldRefreshManagedVideoThumbnails() {
        return nowMs() < managedVideoThumbnailPreviewUntilMs;
    }

    @Override
    public void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
        if (droneSpec.isLocalArchiveOnly()) {
            liveTrack.setLocalOwner(false);
            pendingDrones.remove(droneSpec.getRemoteId());
            ownerByRemoteId.remove(droneSpec.getRemoteId());
            leaseSeqByRemoteId.remove(droneSpec.getRemoteId());
            lastSightingSentByRemoteId.remove(droneSpec.getRemoteId());
            scheduleIdleParkIfEligible();
            return;
        }
        wakeForCoordinationActivity("first_sighting");
        PendingDrone pending = new PendingDrone(liveTrack, droneSpec, distMeters, firstSeenTs);
        pendingDrones.put(droneSpec.getRemoteId(), pending);
        scheduleFallbackOwnership(pending);
        applyKnownOwnerToPendingTrack(pending);
        sendFirstSighting(pending);
    }

    @Override
    public void onWaypointReceived(@NonNull CtDroneSpec droneSpec,
                                   double droneLat,
                                   double droneLon,
                                   double droneAlt,
                                   double distMeters,
                                   long timestampMsec,
                                   @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        lastWaypointRemoteIdForTesting = droneSpec.getRemoteId();
        wakeForCoordinationActivity("sighting");
        String remoteId = droneSpec.getRemoteId();
        sendTrafficPositionIfEligible(
                remoteId,
                droneSpec.getMappedId(),
                "rid",
                droneLat,
                droneLon,
                droneAlt,
                timestampMsec,
                timestampMsec,
                telemetry == null ? null : telemetry.aircraftTrackDeg,
                telemetry == null ? null : telemetry.aircraftGsKnots,
                telemetry == null ? null : telemetry.aircraftAltitudeRateFpm);
        boolean localOwner = isLocalOwner(remoteId);
        long nowMs = nowMs();
        if (!localOwner) {
            Long lastSentAtMs = lastSightingSentByRemoteId.get(remoteId);
            if (lastSentAtMs != null && nowMs - lastSentAtMs < SIGHTING_SEND_INTERVAL_MS) {
                CTDebug(TAG, String.format(Locale.US,
                        "onWaypointReceived(%s): throttling sighting ageMs=%d",
                        remoteId, nowMs - lastSentAtMs));
                return;
            }
        }
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "sighting");
            jo.put("mapId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("remoteId", remoteId);
            jo.put("mappedId", droneSpec.getMappedId());
            jo.put("trackLabel", droneSpec.trackLabel());
            jo.put("droneTs", timestampMsec);
            putFinite(jo, "lat", droneLat);
            putFinite(jo, "lng", droneLon);
            putFinite(jo, "altM", droneAlt);
            putFinite(jo, "distanceFromZoneM", distMeters);
            putTelemetry(jo, telemetry);
            if (sendJson(jo)) {
                lastSightingSentByRemoteId.put(remoteId, nowMs);
                if (localOwner) {
                    lastOwnerActivityAtMs = nowMs;
                }
            }
            scheduleIdleParkIfEligible();
        } catch (Exception e) {
            CTError(TAG, "onWaypointReceived() raised", e);
        }
    }

    @Override
    public void onTrafficPositionReceived(@NonNull CtDroneSpec droneSpec,
                                          double droneLat,
                                          double droneLon,
                                          double droneAlt,
                                          long timestampMsec,
                                          @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        sendTrafficPositionIfEligible(
                droneSpec.getRemoteId(),
                droneSpec.getMappedId(),
                "rid",
                droneLat,
                droneLon,
                droneAlt,
                timestampMsec,
                timestampMsec,
                telemetry == null ? null : telemetry.aircraftTrackDeg,
                telemetry == null ? null : telemetry.aircraftGsKnots,
                telemetry == null ? null : telemetry.aircraftAltitudeRateFpm);
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
        sendTrafficPositionIfEligible(
                remoteId, mappedId, "sei", droneLat, droneLon, droneAlt,
                timestampMsec, altitudeTimestampMsec, headingDegrees, null, null);
    }

    private void sendTrafficPositionIfEligible(@NonNull String remoteId,
                                               @NonNull String mappedId,
                                               @NonNull String source,
                                               double droneLat,
                                               double droneLon,
                                               double droneAlt,
                                               long timestampMsec,
                                               long altitudeTimestampMsec,
                                               @Nullable Double headingDegrees,
                                               @Nullable Double groundSpeedKnots,
                                               @Nullable Double verticalRateFpm) {
        String key = source + "|" + remoteId;
        long nowMs = nowMs();
        Long lastSent = lastTrafficSentByKey.get(key);
        long sendIntervalMs = trafficSendIntervalByKey.getOrDefault(key, TRAFFIC_SEND_INTERVAL_MS);
        if (lastSent != null && nowMs - lastSent < sendIntervalMs) return;
        long sequence = trafficSequenceByKey.merge(key, 1L, Long::sum);
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "traffic_position");
            jo.put("mapId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("remoteId", remoteId);
            jo.put("mappedId", mappedId);
            jo.put("source", source);
            jo.put("sourceEpoch", trafficSourceEpoch);
            jo.put("seq", sequence);
            jo.put("sampleTs", timestampMsec);
            putFinite(jo, "lat", droneLat);
            putFinite(jo, "lng", droneLon);
            putFinite(jo, "altM", droneAlt);
            jo.put("altSampleTs", altitudeTimestampMsec);
            jo.put("padFt", CaltopoClient.GetProximityAlertSpacingFeet());
            PeerTrafficAltitudeNormalizer.Metadata altitude =
                    PeerTrafficAltitudeNormalizer.metadata(remoteId, droneAlt, altitudeTimestampMsec);
            jo.put("altCalibrationState", altitude.getState());
            if (altitude.getFlightEpoch() != null) {
                jo.put("flightEpoch", altitude.getFlightEpoch());
            }
            if (altitude.getMslAltitudeMeters() != null) {
                putFinite(jo, "mslAltM", altitude.getMslAltitudeMeters());
                jo.put("mslAltSampleTs", altitude.getMslAltitudeSampleTimestampMsec());
                putFinite(jo, "altCorrectionM", altitude.getCorrectionMeters());
                jo.put("altCalibrationTs", altitude.getCalibrationTimestampMsec());
                if (altitude.getDemSource() != null) jo.put("demSource", altitude.getDemSource());
                if (altitude.getDemResolutionMeters() != null) {
                    putFinite(jo, "demResolutionM", altitude.getDemResolutionMeters());
                }
            }
            if (headingDegrees != null) putFinite(jo, "headingDeg", headingDegrees);
            if (groundSpeedKnots != null) putFinite(jo, "groundSpeedKnots", groundSpeedKnots);
            if (verticalRateFpm != null) putFinite(jo, "verticalRateFpm", verticalRateFpm);
            if (sendJson(jo)) {
                lastTrafficSentByKey.put(key, nowMs);
                Long lastLogMs = lastTrafficSentLogByKey.get(key);
                if (lastLogMs != null && nowMs - lastLogMs < TRAFFIC_DIAGNOSTIC_INTERVAL_MS) return;
                lastTrafficSentLogByKey.put(key, nowMs);
                CTDebug(TAG, String.format(Locale.US,
                        "Peer traffic sent remoteId=%s source=%s seq=%d sourceAgeMs=%d headingDeg=%s speedKt=%s altAgeMs=%d altState=%s mslAltM=%s mslAltAgeMs=%s correctionM=%s flightEpoch=%s intervalMs=%d",
                        remoteId,
                        source,
                        sequence,
                        Math.max(0L, nowMs - timestampMsec),
                        String.valueOf(headingDegrees),
                        String.valueOf(groundSpeedKnots),
                        Math.max(0L, nowMs - altitudeTimestampMsec),
                        altitude.getState(),
                        String.valueOf(altitude.getMslAltitudeMeters()),
                        altitude.getMslAltitudeSampleTimestampMsec() == null ? "null" : String.valueOf(Math.max(0L, nowMs - altitude.getMslAltitudeSampleTimestampMsec())),
                        String.valueOf(altitude.getCorrectionMeters()),
                        String.valueOf(altitude.getFlightEpoch()),
                        sendIntervalMs));
            }
        } catch (Exception e) {
            CTError(TAG, "sendTrafficPositionIfEligible() raised", e);
        }
    }

    @Override
    public void onDroneLost(@NonNull String remoteId) {
        PendingDrone pending = pendingDrones.remove(remoteId);
        boolean localArchiveOnly = pending != null && pending.droneSpec.isLocalArchiveOnly();
        if (pending != null) {
            pending.fallbackTimer.stop();
            pending.ownershipActivationTimer.stop();
            pending.liveTrack.setLocalOwner(false);
        }
        ownerByRemoteId.remove(remoteId);
        leaseSeqByRemoteId.remove(remoteId);
        locallyConfirmedRemoteIds.remove(remoteId);
        lastSightingSentByRemoteId.remove(remoteId);
        trafficSequenceByKey.keySet().removeIf(key -> key.endsWith("|" + remoteId));
        lastTrafficSentByKey.keySet().removeIf(key -> key.endsWith("|" + remoteId));
        trafficSendIntervalByKey.keySet().removeIf(key -> key.endsWith("|" + remoteId));
        lastTrafficSentLogByKey.keySet().removeIf(key -> key.endsWith("|" + remoteId));
        PeerTrafficAltitudeNormalizer.clear(remoteId);
        wakeForCoordinationActivity("drone_lost");
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "drone_lost");
            jo.put("mapId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("remoteId", remoteId);
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "onDroneLost() raised", e);
        }
        scheduleIdleParkIfEligible();
    }

    @Override
    public void onDroneConfirmed(@NonNull String remoteId,
                                 @NonNull String org,
                                 @NonNull String model,
                                 @NonNull String owner,
                                 @NonNull String mappedId) {
        JSONObject jo = new JSONObject();
        try {
            locallyConfirmedRemoteIds.put(remoteId, true);
            CtDroneSpec confirmedSpec = CaltopoClient.GetDroneSpec(remoteId);
            if (confirmedSpec != null) {
                PeerTrafficAltitudeNormalizer.lockAtConfirmation(confirmedSpec, nowMs());
            }
            jo.put("type", "drone_confirmed");
            jo.put("mapId", mapId != null ? mapId : "");
            jo.put("zoneId", myGuid != null ? myGuid : "");
            jo.put("guid", myGuid != null ? myGuid : "");
            jo.put("remoteId", remoteId);
            jo.put("mappedId", mappedId);
            jo.put("trackLabel", mappedId);
            jo.put("org", org);
            jo.put("model", model);
            jo.put("ownerName", owner);
            pendingConfirmationsByRemoteId.put(remoteId, jo);
            wakeForCoordinationActivity("drone_confirmed");
            CTDebug(TAG, String.format(Locale.US,
                    "onDroneConfirmed(): queued remoteId=%s mappedId='%s'",
                    remoteId,
                    mappedId));
            flushPendingConfirmations();
            applyOwnerAssignment(remoteId, myGuid, -1L, 0L);
            scheduleIdleParkIfEligible();
        } catch (Exception e) {
            CTError(TAG, "onDroneConfirmed() raised", e);
        }
    }

    @Override
    public boolean isLocalOwner(@NonNull String remoteId) {
        String ownerGuid = ownerByRemoteId.get(remoteId);
        return ownerGuid != null && ownerGuid.equals(myGuid);
    }

    @Override
    public boolean isLocalAlertEligible(@NonNull String remoteId) {
        return isLocalOwner(remoteId) && locallyConfirmedRemoteIds.containsKey(remoteId);
    }

    @Override
    public void updateCaltopoRtt(long rttMs) {
        myCaltopoRttMs = rttMs;
    }

    @Override
    public long getCaltopoRttMs() {
        return myCaltopoRttMs;
    }

    @Override
    public void updateMyPosition(double lat, double lon) {
        myLat = lat;
        myLon = lon;
        if (started && isConnected()) {
            requestHeartbeat("position");
        }
    }

    @Override
    public void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener) {
        peerListChangedListener = listener;
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
    }

    @NonNull
    @Override
    public List<R2CMqttManager.PeerState> getPeerList() {
        return new ArrayList<>(peers.values());
    }

    @NonNull
    @Override
    public CoordinationIndicatorState getCoordinationIndicatorState() {
        if (!started || trackerWsUrl == null || trackerApiKey == null) {
            return CoordinationIndicatorState.UNCONFIGURED;
        }
        if (intentionallyParked) {
            return CoordinationIndicatorState.IDLE;
        }
        return isConnected() && helloAckAtMs >= helloSeqSentAtMs && helloAckAtMs > 0L
                ? CoordinationIndicatorState.HEALTHY
                : CoordinationIndicatorState.DEGRADED;
    }

    @NonNull
    @Override
    public String getCoordinationStatusText() {
        CoordinationIndicatorState state = getCoordinationIndicatorState();
        switch (state) {
            case HEALTHY:
                return "Tracker verified";
            case IDLE:
                return "Tracker link standby";
            case DEGRADED:
                return "Tracker link degraded";
            case UNCONFIGURED:
            default:
                return "Tracker link not configured";
        }
    }

    @NonNull
    @Override
    public List<String> getCoordinationDiagnosticLines() {
        ArrayList<String> lines = new ArrayList<>();
        long nowMs = nowMs();
        if (!started) {
            lines.add("Tracker coordinator stopped");
            return lines;
        }
        if (intentionallyParked) {
            lines.add("Tracker websocket on standby until an incident or coordination activity resumes");
            return lines;
        }
        if (trackerWsUrl == null || trackerApiKey == null) {
            lines.add("Tracker websocket not configured");
            return lines;
        }

        if (helloAckAtMs > 0L) {
            lines.add("Hello ack " + formatDuration(nowMs - helloAckAtMs) + " ago");
        } else if (helloSeqSentAtMs > 0L) {
            lines.add("Waiting for hello ack " + formatDuration(nowMs - helloSeqSentAtMs));
        } else {
            lines.add("Waiting for tracker hello");
        }

        if (lastHeartbeatAckAtMs > 0L) {
            lines.add(String.format(Locale.US,
                    "Heartbeat ack %s ago (seq %d)",
                    formatDuration(nowMs - lastHeartbeatAckAtMs),
                    lastHeartbeatSeqAcked));
        } else if (lastHeartbeatSentAtMs > 0L) {
            lines.add(String.format(Locale.US,
                    "Waiting for heartbeat ack seq %d %s",
                    lastHeartbeatSeqSent,
                    formatDuration(nowMs - lastHeartbeatSentAtMs)));
        }

        if (reconnectPending) {
            lines.add("Reconnect pending in " + formatDuration(Math.max(reconnectTargetAtMs - nowMs, 0L)));
        } else if (!lastReconnectCause.isEmpty() && !"connected".equals(lastReconnectCause)) {
            lines.add("Last tracker event: " + lastReconnectCause);
        }

        if (lastCloseCode != 0 || !lastCloseReason.isEmpty()) {
            String reason = lastCloseReason.isEmpty() ? "" : " " + lastCloseReason;
            lines.add("Last close: " + lastCloseCode + reason);
        }

        return lines;
    }

    private boolean isConnected() {
        TrackerCoordinationTransport activeTransport = transport;
        return activeTransport != null && activeTransport.isConnected();
    }

    private boolean hasLocalOwnerLease() {
        String guid = myGuid;
        if (guid == null || guid.isEmpty()) return false;
        for (String ownerGuid : ownerByRemoteId.values()) {
            if (guid.equals(ownerGuid)) return true;
        }
        return false;
    }

    private boolean isIdleEligible() {
        return started &&
                standaloneStandbyEligible &&
                helloAckAtMs >= helloSeqSentAtMs &&
                helloAckAtMs > 0L &&
                pendingDrones.isEmpty() &&
                ownerByRemoteId.isEmpty() &&
                pendingConfirmationsByRemoteId.isEmpty() &&
                activeTrackerInteractionRequestIds.isEmpty() &&
                !hasLiveManagedVideoStream();
    }

    private void beginTrackerInteraction(@NonNull String requestId) {
        if (requestId.isEmpty()) return;
        activeTrackerInteractionRequestIds.add(requestId);
        idleParkTimer.stop();
    }

    private void endTrackerInteraction(@NonNull String requestId) {
        if (requestId.isEmpty()) return;
        activeTrackerInteractionRequestIds.remove(requestId);
        scheduleIdleParkIfEligible();
    }

    private boolean hasLiveManagedVideoStream() {
        for (ManagedVideoStreamAdvertisement stream : managedVideoStreams) {
            if ("live".equalsIgnoreCase(stream.mediaKind)) return true;
        }
        return false;
    }

    @Override
    public boolean hasOperationalActivityPreventingMapDisconnect() {
        if (hasLiveManagedVideoStream() || !approvedRecordingUploads.isEmpty()) {
            return true;
        }
        // After the tracker has been unreachable for a full minute, its cached
        // ownership/confirmation maps are no longer proof of current work. The
        // authoritative local active-flight and RID-quiet gates still apply.
        if (hasTrackerAcknowledgementSilence(INCIDENT_STANDBY_SILENCE_MS)) {
            return false;
        }
        return !pendingDrones.isEmpty() ||
                !ownerByRemoteId.isEmpty() ||
                !pendingConfirmationsByRemoteId.isEmpty();
    }

    @Override
    public boolean hasTrackerAcknowledgementSilence(long thresholdMs) {
        if (!started || intentionallyParked || trackerApiKey == null || trackerWsUrl == null) {
            return false;
        }
        long anchorMs = Math.max(lastServerAcknowledgementAtMs, coordinationAttemptStartedAtMs);
        return anchorMs > 0L && nowMs() - anchorMs >= Math.max(thresholdMs, INCIDENT_STANDBY_SILENCE_MS);
    }

    private boolean shouldSkipIntervalHeartbeat() {
        if (!hasLocalOwnerLease()) return false;
        long lastOwnerActivity = lastOwnerActivityAtMs;
        long nowMs = nowMs();
        long lastTrackerLivenessMessage = Math.max(lastHeartbeatSentAtMs, helloSeqSentAtMs);
        long heartbeatSilenceMs = lastTrackerLivenessMessage > 0L
                ? nowMs - lastTrackerLivenessMessage
                : Long.MAX_VALUE;
        return lastOwnerActivity > 0L &&
                nowMs - lastOwnerActivity < OWNER_ACTIVITY_HEARTBEAT_SUPPRESS_MS &&
                heartbeatSilenceMs < OWNER_ACTIVITY_MAX_HEARTBEAT_SILENCE_MS;
    }

    private void scheduleIdleParkIfEligible() {
        if (!isIdleEligible() || !isConnected() || intentionallyParked) {
            idleParkTimer.stop();
            return;
        }
        if (idleParkTimer.isRunning()) {
            return;
        }
        idleParkTimer.start(this::parkIfIdle, idleParkDelayMs, 0L);
    }

    private synchronized void parkIfIdle() {
        if (!isIdleEligible() || !isConnected()) return;
        CTInfo(TAG, "parkIfIdle(): placing standalone tracker websocket on standby");
        intentionallyParked = true;
        heartbeatTimer.stop();
        ackWatchdogTimer.stop();
        heartbeatCoalesceTimer.stop();
        reconnectTimer.stop();
        reconnectPending = false;
        peers.clear();
        notifyPeerListChanged();
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport != null) {
            sendIdleNotice();
            activeTransport.disconnect();
        }
        notifyCoordinationIndicatorListener();
    }

    private void sendIdleNotice() {
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "idle");
            jo.put("mapId", mapId != null ? mapId : "");
            jo.put("zoneId", myGuid != null ? myGuid : "");
            jo.put("guid", myGuid != null ? myGuid : "");
            jo.put("reason", "standalone_standby");
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "sendIdleNotice() raised", e);
        }
    }

    private synchronized void wakeForCoordinationActivity(@NonNull String reason) {
        if (!started || trackerWsUrl == null || trackerApiKey == null) return;
        idleParkTimer.stop();
        if (isConnected() || reconnectPending) return;
        CTInfo(TAG, "wakeForCoordinationActivity(): waking tracker websocket for " + reason);
        intentionallyParked = false;
        reconnectPending = true;
        lastReconnectCause = "wake-" + reason;
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport == null) {
            activeTransport = transportFactory.create();
            transport = activeTransport;
        }
        activeTransport.connect(trackerWsUrl, trackerApiKey);
        notifyCoordinationIndicatorListener();
    }

    private void notifyCoordinationIndicatorListener() {
        CoordinationIndicatorListener listener = coordinationIndicatorListener;
        if (listener != null) {
            listener.onCoordinationIndicatorStateChanged(getCoordinationIndicatorState());
        }
    }

    private void scheduleReconnect() {
        scheduleReconnect("retry", -1L);
    }

    private void scheduleReconnect(@NonNull String cause, long overrideDelayMs) {
        if (!started || trackerWsUrl == null || trackerApiKey == null || intentionallyParked) return;
        long delayMs = overrideDelayMs >= 0 ? overrideDelayMs : nextReconnectDelayMs;
        long scheduledAtMs = nowMs();
        long targetAtMs = scheduledAtMs + delayMs;
        if (reconnectPending && reconnectTargetAtMs > 0L && reconnectTargetAtMs <= targetAtMs) {
            CTDebug(TAG, String.format(Locale.US,
                    "scheduleReconnect(): ignoring duplicate cause=%s existingDelayMs=%d requestedDelayMs=%d",
                    cause,
                    Math.max(reconnectTargetAtMs - reconnectScheduledAtMs, 0L),
                    delayMs));
            return;
        }
        reconnectPending = true;
        lastReconnectCause = cause;
        reconnectScheduledAtMs = scheduledAtMs;
        reconnectTargetAtMs = targetAtMs;
        CTDebug(TAG, String.format(Locale.US,
                "scheduleReconnect(): cause=%s delayMs=%d lastAckSeq=%d helloAckAgeMs=%d heartbeatAckAgeMs=%d closeCode=%d closeReason='%s'",
                cause,
                delayMs,
                lastHeartbeatSeqAcked,
                ageSince(helloAckAtMs),
                ageSince(lastHeartbeatAckAtMs),
                lastCloseCode,
                lastCloseReason));
        reconnectTimer.start(this::reconnect, delayMs, 0L);
        if (overrideDelayMs < 0) {
            nextReconnectDelayMs = Math.min(nextReconnectDelayMs * 2L, RECONNECT_MAX_DELAY_MS);
        }
    }

    private synchronized void reconnect() {
        if (!started || trackerWsUrl == null || trackerApiKey == null || intentionallyParked) {
            reconnectPending = false;
            return;
        }
        long reconnectStartedAtMs = nowMs();
        long schedulerSkewMs = reconnectTargetAtMs > 0L
                ? Math.max(reconnectStartedAtMs - reconnectTargetAtMs, 0L)
                : -1L;
        long queuedForMs = reconnectScheduledAtMs > 0L
                ? Math.max(reconnectStartedAtMs - reconnectScheduledAtMs, 0L)
                : -1L;
        CTInfo(TAG, String.format(Locale.US,
                "reconnect(): cause=%s queuedForMs=%d schedulerSkewMs=%d targetDelayMs=%d",
                lastReconnectCause,
                queuedForMs,
                schedulerSkewMs,
                reconnectTargetAtMs > reconnectScheduledAtMs
                        ? (reconnectTargetAtMs - reconnectScheduledAtMs)
                        : -1L));
        reconnectPending = false;
        intentionallyParked = false;
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport == null) {
            activeTransport = transportFactory.create();
            transport = activeTransport;
        }
        activeTransport.setCallback(new TrackerCoordinationTransport.Callback() {
            @Override
            public void onOpen() {
                CTInfo(TAG, "tracker websocket reconnected");
                reconnectTimer.stop();
                onTransportOpen(true);
            }

            @Override
            public void onMessage(@NonNull String text) {
                handleIncomingMessage(text);
            }

            @Override
            public void onClosed(int code, @NonNull String reason) {
                lastCloseCode = code;
                lastCloseReason = reason;
                heartbeatTimer.stop();
                ackWatchdogTimer.stop();
                notifyCoordinationIndicatorListener();
                scheduleReconnect("closed", -1L);
            }

            @Override
            public void onFailure(@Nullable Throwable throwable, int responseCode, @Nullable String responseMessage) {
                heartbeatTimer.stop();
                if (throwable != null) {
                    String message = throwable.getMessage();
                    if (message == null || message.isEmpty()) {
                        message = throwable.getClass().getSimpleName();
                    }
                    if (throwable instanceof EOFException) {
                        CTInfo(TAG, "tracker websocket reconnect transient disconnect: " + message);
                    } else {
                        CTWarn(TAG, "tracker websocket reconnect failure: " + message);
                    }
                }
                lastCloseCode = responseCode;
                lastCloseReason = responseMessage != null ? responseMessage : "";
                notifyCoordinationIndicatorListener();
                notifyHardFailureIfNeeded(responseCode, responseMessage);
                scheduleReconnect("failure", -1L);
            }
        });
        activeTransport.connect(trackerWsUrl, trackerApiKey);
    }

    private void onTransportOpen(boolean reconnecting) {
        reconnectPending = false;
        lastTrafficSentByKey.clear();
        trafficSendIntervalByKey.clear();
        lastTrafficSentLogByKey.clear();
        lastTrafficShadowLogByKey.clear();
        PeerTrafficMapRegistry.clear();
        suppressScheduledHeartbeatRequestsForTesting = false;
        nextReconnectDelayMs = RECONNECT_BASE_DELAY_MS;
        lastReconnectCause = reconnecting ? "reconnected" : "connected";
        resetHeartbeatStateForNewTransport();
        sendHello();
        heartbeatSendQueued = false;
        heartbeatTimer.start(() -> requestHeartbeat("interval"), 0L, HEARTBEAT_INTERVAL_MS);
        ackWatchdogTimer.start(TrackerPeerCoordinator.this::checkAckLiveness, ACK_WATCHDOG_INTERVAL_MS, ACK_WATCHDOG_INTERVAL_MS);
        markAllPendingFirstSightingsDirty();
        replayPendingFirstSightings();
        flushPendingConfirmations();
        sendManagedVideoPresence();
        videoPresenceTimer.start(
                this::sendManagedVideoPresence,
                VIDEO_PRESENCE_INTERVAL_MS,
                VIDEO_PRESENCE_INTERVAL_MS
        );
        scheduleIdleParkIfEligible();
        notifyCoordinationIndicatorListener();
    }

    private void resetHeartbeatStateForNewTransport() {
        heartbeatSeqCounter = 0L;
        lastHeartbeatSeqSent = 0L;
        lastHeartbeatSeqAcked = 0L;
        lastHeartbeatSentAtMs = 0L;
        lastHeartbeatAckAtMs = 0L;
        heartbeatSendQueued = false;
    }

    private void notifyHardFailureIfNeeded(int responseCode, @Nullable String responseMessage) {
        if ((responseCode == 401 || responseCode == 403) && !hardFailureNotified) {
            hardFailureNotified = true;
            HardFailureListener listener = hardFailureListener;
            if (listener != null) {
                listener.onHardFailure(responseCode, responseMessage);
            }
        }
    }

    private void scheduleFallbackOwnership(@NonNull PendingDrone pending) {
        pending.fallbackTimer.start(() -> {
            if (!isConnected() && pendingDrones.containsKey(pending.droneSpec.getRemoteId())) {
                CTWarn(TAG, String.format(Locale.US,
                        "Fallback ownership for %s after tracker connect grace expired.",
                        pending.droneSpec.getRemoteId()));
                applyOwnerAssignment(pending.droneSpec.getRemoteId(), myGuid, -1L, 0L);
            }
        }, CONNECT_GRACE_MS, 0L);
    }

    private void applyKnownOwnerToPendingTrack(@NonNull PendingDrone pending) {
        String remoteId = pending.droneSpec.getRemoteId();
        String ownerGuid = ownerByRemoteId.get(remoteId);
        if (ownerGuid == null || ownerGuid.isEmpty()) return;
        Long leaseSeq = leaseSeqByRemoteId.get(remoteId);
        applyOwnerAssignment(remoteId, ownerGuid, leaseSeq != null ? leaseSeq : -1L, 0L);
    }

    private void replayPendingFirstSightings() {
        for (PendingDrone pending : pendingDrones.values()) {
            sendFirstSighting(pending);
        }
    }

    private void markAllPendingFirstSightingsDirty() {
        for (PendingDrone pending : pendingDrones.values()) {
            pending.firstSightingSent = false;
        }
    }

    private void sendHello() {
        JSONObject jo = new JSONObject();
        try {
            helloSeqSentAtMs = nowMs();
            jo.put("type", "hello");
            jo.put("mapId", mapId);
            jo.put("incidentId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("name", myName);
            jo.put("deviceModel", AndroidDeviceIdentity.modelName());
            jo.put("appPlatform", "android");
            putFinite(jo, "lat", myLat);
            putFinite(jo, "lng", myLon);
            jo.put("appVersion", BuildConfig.VERSION_NAME);
            jo.put("appVersionCode", BuildConfig.VERSION_CODE);
            jo.put("trackerFunctionalityRelease", BuildConfig.TRACKER_FUNCTIONALITY_RELEASE);
            jo.put("caltopoRttMs", myCaltopoRttMs);
            CTDebug(TAG, String.format(Locale.US,
                    "sendHello(): mapId=%s zoneId=%s lat=%.6f lng=%.6f rttMs=%d",
                    mapId, myGuid, myLat, myLon, myCaltopoRttMs));
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "sendHello() raised", e);
        }
    }

    private void sendHeartbeat() {
        sendHeartbeat(false);
    }

    private void sendScheduledHeartbeat() {
        sendHeartbeat(true);
    }

    private void sendHeartbeat(boolean fromScheduler) {
        if (fromScheduler && suppressScheduledHeartbeatRequestsForTesting) return;
        JSONObject jo = new JSONObject();
        try {
            heartbeatSendQueued = false;
            if (shouldSkipIntervalHeartbeat()) {
                return;
            }
            if (lastHeartbeatSeqSent > lastHeartbeatSeqAcked) {
                long ackAgeMs = nowMs() - lastHeartbeatSentAtMs;
                if (ackAgeMs > HEARTBEAT_ACK_TIMEOUT_MS) {
                    forceReconnect(String.format(Locale.US,
                            "missed heartbeat_ack seq=%d ageMs=%d", lastHeartbeatSeqSent, ackAgeMs));
                }
                return;
            }
            long seq = ++heartbeatSeqCounter;
            lastHeartbeatSeqSent = seq;
            lastHeartbeatSentAtMs = nowMs();
            jo.put("type", "heartbeat");
            jo.put("seq", seq);
            jo.put("mapId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("name", myName);
            putFinite(jo, "lat", myLat);
            putFinite(jo, "lng", myLon);
            jo.put("caltopoRttMs", myCaltopoRttMs);
            CTDebug(TAG, String.format(Locale.US,
                    "sendHeartbeat(): mapId=%s zoneId=%s lat=%.6f lng=%.6f rttMs=%d",
                    mapId, myGuid, myLat, myLon, myCaltopoRttMs));
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "sendHeartbeat() raised", e);
        }
    }

    private void requestHeartbeat(@NonNull String reason) {
        if (suppressScheduledHeartbeatRequestsForTesting) return;
        if (!started || !isConnected()) return;
        if ("position".equals(reason) && isIdleEligible()) return;
        if (heartbeatSendQueued) return;

        long nowMs = nowMs();
        long gapMs = nowMs - lastHeartbeatSentAtMs;
        long delayMs = (lastHeartbeatSentAtMs <= 0 || gapMs >= HEARTBEAT_MIN_SEND_GAP_MS)
                ? 0L
                : (HEARTBEAT_MIN_SEND_GAP_MS - gapMs);
        heartbeatSendQueued = true;
        heartbeatCoalesceTimer.start(this::sendScheduledHeartbeat, delayMs, 0L);
    }

    private void sendFirstSighting(@NonNull PendingDrone pending) {
        wakeForCoordinationActivity("first_sighting");
        if (!started || pending.firstSightingSent || !isConnected()) {
            return;
        }
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "first_sighting");
            jo.put("mapId", mapId);
            jo.put("incidentId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("name", myName);
            jo.put("remoteId", pending.droneSpec.getRemoteId());
            jo.put("mappedId", pending.droneSpec.getMappedId());
            jo.put("trackLabel", pending.droneSpec.trackLabel());
            jo.put("droneTs", pending.firstSeenTs);
            putFinite(jo, "distanceFromZoneM", pending.distMeters);
            putFinite(jo, "lat", pending.droneSpec.lastLat);
            putFinite(jo, "lng", pending.droneSpec.lastLng);
            putFinite(jo, "altM", pending.droneSpec.lastAlt);
            jo.put("org", pending.droneSpec.getOrg());
            jo.put("model", pending.droneSpec.getModel());
            jo.put("ownerName", pending.droneSpec.getOwner());
            CTDebug(TAG, String.format(Locale.US,
                    "sendFirstSighting(): remoteId=%s mappedId=%s droneTs=%d dist=%.1f",
                    pending.droneSpec.getRemoteId(),
                    pending.droneSpec.getMappedId(),
                    pending.firstSeenTs,
                    pending.distMeters));
            sendJson(jo);
            pending.firstSightingSent = true;
            scheduleIdleParkIfEligible();
        } catch (Exception e) {
            CTError(TAG, "sendFirstSighting() raised", e);
        }
    }

    private boolean sendJson(@NonNull JSONObject jo) {
        lastOutboundJsonForTesting = jo.toString();
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport == null || !activeTransport.isConnected()) return false;
        return activeTransport.send(jo.toString());
    }

    private void sendManagedVideoPresence() {
        if (!started || !isConnected()) return;
        List<ManagedVideoStreamAdvertisement> snapshot = managedVideoStreams;
        if (managedVideoIncidentName.isEmpty()) return;
        JSONObject message = new JSONObject();
        JSONArray streams = new JSONArray();
        try {
            for (ManagedVideoStreamAdvertisement stream : snapshot) {
                JSONObject advertised = new JSONObject();
                advertised.put("sessionId", stream.sessionId);
                advertised.put("droneDesignator", stream.droneDesignator);
                advertised.put("sourceWidth", stream.sourceWidth);
                advertised.put("sourceHeight", stream.sourceHeight);
                advertised.put("sourceFps", stream.sourceFps);
                advertised.put("sourceBitrateBps", stream.sourceBitrateBps);
                advertised.put("sourceSizeBytes", stream.sourceSizeBytes);
                advertised.put("sourceCodec", stream.sourceCodec);
                advertised.put("mediaKind", stream.mediaKind);
                if (stream.recordedAt != null && !stream.recordedAt.isEmpty()) {
                    advertised.put("recordedAt", stream.recordedAt);
                }
                advertised.put("durationMs", stream.durationMs);
                if (!stream.thumbnailRevision.isEmpty()) {
                    advertised.put("thumbnailRevision", stream.thumbnailRevision);
                }
                if (stream.thumbnailJpegBase64 != null &&
                        !stream.thumbnailJpegBase64.isEmpty()) {
                    advertised.put("thumbnailJpegBase64", stream.thumbnailJpegBase64);
                }
                streams.put(advertised);
            }
            message.put("type", "video_stream_advertisement");
            message.put(
                    "deviceName",
                    AndroidDeviceIdentity.selectDisplayName(
                            R2CActivity.MyDeviceName,
                            (Build.MANUFACTURER + " " + Build.MODEL).trim()));
            message.put("incidentName", managedVideoIncidentName);
            message.put("timeZone", ZoneId.systemDefault().getId());
            message.put(
                    "remoteControlEnabled",
                    RemoteVideoControlPrefs.isEnabled(R2CApplication.getAppCtxt()));
            message.put("streams", streams);
            sendJson(message);
        } catch (Exception e) {
            CTError(TAG, "sendManagedVideoPresence() raised", e);
        }
    }

    private void flushPendingConfirmations() {
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport == null || !activeTransport.isConnected()) {
            if (!pendingConfirmationsByRemoteId.isEmpty()) {
                CTDebug(TAG, String.format(Locale.US,
                        "flushPendingConfirmations(): waiting for tracker connection pending=%d",
                        pendingConfirmationsByRemoteId.size()));
            }
            return;
        }
        for (Map.Entry<String, JSONObject> entry : pendingConfirmationsByRemoteId.entrySet()) {
            if (sendJson(entry.getValue())) {
                CTDebug(TAG, String.format(Locale.US,
                        "flushPendingConfirmations(): sent remoteId=%s",
                        entry.getKey()));
                pendingConfirmationsByRemoteId.remove(entry.getKey(), entry.getValue());
            } else {
                CTDebug(TAG, String.format(Locale.US,
                        "flushPendingConfirmations(): send rejected remoteId=%s pending=%d",
                        entry.getKey(),
                        pendingConfirmationsByRemoteId.size()));
            }
        }
        scheduleIdleParkIfEligible();
    }

    private void checkAckLiveness() {
        if (!started || !isConnected()) return;
        long nowMs = nowMs();
        if (helloAckAtMs < helloSeqSentAtMs && helloSeqSentAtMs > 0 &&
                nowMs - helloSeqSentAtMs > HELLO_ACK_TIMEOUT_MS) {
            forceReconnect(String.format(Locale.US,
                    "missed hello_ack ageMs=%d", nowMs - helloSeqSentAtMs));
            return;
        }
        if (lastHeartbeatSeqSent > lastHeartbeatSeqAcked && lastHeartbeatSentAtMs > 0 &&
                nowMs - lastHeartbeatSentAtMs > HEARTBEAT_ACK_TIMEOUT_MS) {
            forceReconnect(String.format(Locale.US,
                    "missed heartbeat_ack seq=%d ageMs=%d",
                    lastHeartbeatSeqSent, nowMs - lastHeartbeatSentAtMs));
        }
    }

    private synchronized void forceReconnect(@NonNull String cause) {
        if (!started) return;
        if (reconnectPending) {
            CTWarn(TAG, String.format(Locale.US,
                    "forceReconnect(): reconnect already pending; ignoring duplicate cause=%s lastReconnectCause=%s",
                    cause,
                    lastReconnectCause));
            return;
        }
        forcedReconnectCount++;
        lastReconnectCause = cause;
        CTWarn(TAG, String.format(Locale.US,
                "forceReconnect(): cause=%s lastAckSeq=%d helloAckAgeMs=%d heartbeatAckAgeMs=%d closeCode=%d closeReason='%s'",
                cause,
                lastHeartbeatSeqAcked,
                ageSince(helloAckAtMs),
                ageSince(lastHeartbeatAckAtMs),
                lastCloseCode,
                lastCloseReason));
        heartbeatTimer.stop();
        ackWatchdogTimer.stop();
        heartbeatCoalesceTimer.stop();
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport != null) {
            activeTransport.disconnect();
        }
        scheduleReconnect("forced-" + cause, 0L);
    }

    private void handleIncomingMessage(@NonNull String text) {
        try {
            JSONObject jo = new JSONObject(text);
            String type = jo.optString("type");
            switch (type) {
                case "hello_ack":
                    helloAckAtMs = nowMs();
                    lastServerAcknowledgementAtMs = helloAckAtMs;
                    String canonicalDeviceName = jo.optString("canonicalDeviceName", "").trim();
                    Context identityContext = R2CApplication.getAppCtxt();
                    if (!canonicalDeviceName.isEmpty() && identityContext != null) {
                        AndroidDeviceIdentity.applyManagedDisplayName(
                                identityContext, canonicalDeviceName);
                        R2CActivity.MyDeviceName = canonicalDeviceName;
                        myName = canonicalDeviceName;
                    }
                    handleAppUpdateRecommendation(jo);
                    long advertisedStandbySeconds = jo.optLong(
                            "standbyParkSec",
                            jo.optLong("idleParkSec", DEFAULT_IDLE_PARK_DELAY_MS / 1_000L));
                    idleParkDelayMs = Math.max(
                            5_000L,
                            Math.min(advertisedStandbySeconds * 1_000L, 3_600_000L));
                    long managedConfigVersionMs = jo.optLong(
                            "organizationConfigVersionMs", 0L);
                    if (managedConfigVersionMs != 0L
                            && trackerHttpOrigin != null
                            && trackerApiKey != null
                            && R2CApplication.getAppCtxt() != null) {
                        OrgConfigManager.syncManagedConfiguration(
                                R2CApplication.getAppCtxt(),
                                trackerHttpOrigin,
                                CaltopoClient.GetHomeOrgName(),
                                trackerApiKey,
                                managedConfigVersionMs,
                                this::scheduleIdleParkIfEligible);
                    } else {
                        scheduleIdleParkIfEligible();
                    }
                    notifyCoordinationIndicatorListener();
                    CTDebug(TAG, String.format(Locale.US,
                            "hello_ack received from tracker after %d ms",
                            helloAckAtMs - helloSeqSentAtMs));
                    break;
                case "reauthentication_required":
                    boolean managedCaltopoCleared =
                            CaltopoClient.QuarantineTrackerManagedCaltopoCredentials();
                    Context appContext = R2CApplication.getAppCtxt();
                    if (managedCaltopoCleared && appContext != null) {
                        OrgConfigManager.invalidateManagedConfigurationVersion(appContext);
                    }
                    CTWarn(TAG, "Tracker requires reauthentication; tracker access paused; "
                            + (managedCaltopoCleared
                            ? "tracker-managed CalTopo credentials cleared"
                            : "independent CalTopo credentials preserved"));
                    String reauthenticationUrl = jo.optString("reauthenticationUrl", "");
                    R2CActivity activity = R2CActivity.getR2CActivity();
                    if (activity != null && !reauthenticationUrl.isEmpty()) {
                        activity.beginTrackerReauthentication(reauthenticationUrl);
                    } else {
                        CaltopoClient.ShowToast(
                                managedCaltopoCleared
                                        ? "Tracker access is paused. Tracker-managed CalTopo credentials were cleared; RID2Caltopo remains available offline."
                                        : "Tracker access is paused. Independent CalTopo credentials and offline RID2Caltopo operation remain available.");
                    }
                    stop();
                    break;
                case "upgrade_required":
                    CTWarn(TAG, jo.optString(
                            "message", "Tracker requires a newer functionality release"));
                    stop();
                    break;
                case "heartbeat_ack":
                    onHeartbeatAck(jo);
                    break;
                case "zone_update":
                    onZoneUpdate(jo.optJSONArray("zones"));
                    break;
                case "owner_assigned":
                    applyOwnerAssignment(
                            jo.optString("remoteId"),
                            firstNonEmpty(jo.optString("ownerGuid"), jo.optString("ownerZoneId")),
                            jo.optLong("leaseSeq", -1L),
                            jo.optLong("leaseExpireTs", 0L));
                    break;
                case "owner_expired":
                    clearOwner(
                            jo.optString("remoteId"),
                            firstNonEmpty(jo.optString("prevOwnerGuid"), jo.optString("prevOwnerZoneId")));
                    break;
                case "relay_sighting":
                    onRelaySighting(jo);
                    break;
                case "peer_traffic_position":
                    onPeerTrafficShadow(jo);
                    break;
                case "traffic_schedule":
                    onTrafficSchedule(jo);
                    break;
                case "drone_confirmed":
                    onDroneConfirmedByPeer(jo);
                    break;
                case "video_stream_request":
                    onVideoStreamRequest(jo);
                    break;
                case "recording_download_request":
                    onRecordingDownloadRequest(jo);
                    break;
                case "recording_download_decision_ack":
                    onRecordingDownloadDecisionAck(jo);
                    break;
                case "aircraft_readiness_changed":
                    java.util.concurrent.CompletableFuture.runAsync(() -> AircraftOrganizationAccess.INSTANCE.refresh());
                    break;
                case "organization_config_snapshot_request":
                    onOrganizationConfigSnapshotRequest(jo);
                    break;
                case "organization_config_snapshot_ack":
                    if (!jo.optBoolean("accepted", false)) {
                        CTWarn(TAG, "Organization configuration snapshot rejected: "
                                + jo.optString("error", "Tracker rejected snapshot"));
                    }
                    break;
                case "video_thumbnail_preview":
                    int previewTtlSeconds = Math.max(
                            10,
                            Math.min(jo.optInt("ttlSec", 25), 60)
                    );
                    managedVideoThumbnailPreviewUntilMs = Math.max(
                            managedVideoThumbnailPreviewUntilMs,
                            nowMs() + previewTtlSeconds * 1_000L
                    );
                    CTDebug(TAG, "Managed video thumbnail preview lease renewed ttlSec="
                            + previewTtlSeconds);
                    break;
                case "video_preflight_offer":
                    onVideoPreflightOffer(jo);
                    break;
                case "video_media_offer":
                    onVideoMediaOffer(jo);
                    break;
                case "video_stream_request_cancelled":
                    onVideoStreamRequestCancelled(jo);
                    break;
                case "video_stream_advertisement_ack":
                    if (jo.optBoolean("accepted", false)) {
                        JSONArray sessionIds = jo.optJSONArray("sessionIds");
                        CTDebug(TAG, "Managed video presence accepted sessions="
                                + (sessionIds == null ? 0 : sessionIds.length()));
                    } else {
                        CTWarn(TAG, "Managed video presence rejected: "
                                + jo.optString("error", "Tracker rejected presence"));
                    }
                    break;
                default:
                    CTDebug(TAG, "Ignoring tracker message type: " + type);
                    break;
            }
        } catch (Exception e) {
            CTError(TAG, "handleIncomingMessage() raised for: " + text, e);
        }
    }

    private void onOrganizationConfigSnapshotRequest(@NonNull JSONObject request) {
        String requestId = request.optString("requestId").trim();
        if (requestId.isEmpty()) return;
        try {
            JSONObject response = new JSONObject()
                    .put("type", "organization_config_snapshot_response")
                    .put("requestId", requestId)
                    .put("config", OrgConfigManager.buildManagedSnapshot());
            if (!sendJson(response)) {
                CTWarn(TAG, "Could not return requested organization configuration.");
            }
        } catch (Exception e) {
            CTWarn(TAG, "Could not build requested organization configuration.", e);
        }
    }

    private void onVideoStreamRequest(@NonNull JSONObject jo) {
        String requestId = jo.optString("requestId").trim();
        String requesterEmail = jo.optString("requesterEmail").trim();
        String streamSessionId = jo.optString("streamSessionId").trim();
        if (requestId.isEmpty() || requesterEmail.isEmpty() || streamSessionId.isEmpty()) {
            CTWarn(TAG, "Ignoring incomplete video stream request.");
            return;
        }
        synchronized (seenVideoStreamRequestIds) {
            if (!seenVideoStreamRequestIds.add(requestId)) {
                CTDebug(TAG, "Ignoring replayed video stream request: " + requestId);
                return;
            }
            while (seenVideoStreamRequestIds.size() > 50) {
                String oldest = seenVideoStreamRequestIds.iterator().next();
                seenVideoStreamRequestIds.remove(oldest);
            }
        }
        boolean hasActiveStream = false;
        for (ManagedVideoStreamAdvertisement stream : managedVideoStreams) {
            if (stream.sessionId.equals(streamSessionId)) {
                hasActiveStream = true;
                break;
            }
        }
        if (!hasActiveStream) {
            sendVideoStreamUnavailable(requestId, streamSessionId);
            CTWarn(TAG, "Rejected video stream request error=e_nosuch_stream request="
                    + requestId + " session=" + streamSessionId);
            return;
        }
        VideoStreamRequestListener listener = videoStreamRequestListener;
        if (listener == null) {
            CTWarn(TAG, "Video stream request arrived without an active UI listener.");
            return;
        }
        beginTrackerInteraction(requestId);
        listener.onVideoStreamRequest(
                new VideoStreamViewRequest(
                        requestId,
                        requesterEmail,
                        streamSessionId,
                        jo.optString("incidentName").trim(),
                        jo.optString("droneDesignator").trim(),
                        jo.optInt("sourceWidth", 0),
                        jo.optInt("sourceHeight", 0),
                        jo.optDouble("sourceFps", 0.0),
                        jo.optLong("sourceBitrateBps", 0L),
                        jo.optString("sourceCodec").trim(),
                        jo.optString("expiresAt").trim(),
                        jo.optBoolean("consentRequired", true)));
    }

    private void onRecordingDownloadRequest(@NonNull JSONObject jo) {
        RecordingDownloadRequest request = new RecordingDownloadRequest(
                jo.optString("requestId").trim(),
                jo.optString("requesterEmail").trim(),
                jo.optString("streamSessionId").trim(),
                jo.optString("droneDesignator").trim(),
                jo.optString("uploadPath").trim(),
                jo.optString("expiresAt").trim(),
                jo.optBoolean("consentRequired", true));
        if (request.requestId.isEmpty() || request.streamSessionId.isEmpty()
                || request.uploadPath.isEmpty()) {
            CTWarn(TAG, "Ignoring incomplete recording download request.");
            return;
        }
        VideoStreamRequestListener listener = videoStreamRequestListener;
        if (listener != null) {
            beginTrackerInteraction(request.requestId);
            listener.onRecordingDownloadRequest(request);
        }
    }

    private void onRecordingDownloadDecisionAck(@NonNull JSONObject jo) {
        String requestId = jo.optString("requestId").trim();
        RecordingDownloadRequest request = approvedRecordingUploads.remove(requestId);
        if (request == null) return;
        if (!jo.optBoolean("accepted", false)) {
            endTrackerInteraction(requestId);
            CTWarn(TAG, "Tracker rejected recording transfer request=" + requestId
                    + " error=" + jo.optString("error"));
            return;
        }
        CTInfo(TAG, "Tracker confirmed recording transfer authorization request=" + requestId);
    }

    private void sendVideoStreamUnavailable(
            @NonNull String requestId,
            @NonNull String streamSessionId) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "video_stream_unavailable");
            message.put("requestId", requestId);
            message.put("streamSessionId", streamSessionId);
            message.put("errorCode", "e_nosuch_stream");
            if (!sendJson(message)) {
                CTWarn(TAG, "Unable to report unavailable managed video stream.");
            }
        } catch (Exception exception) {
            CTError(TAG, "Unable to encode unavailable managed video stream.", exception);
        }
    }

    private void onVideoPreflightOffer(@NonNull JSONObject jo) {
        String requestId = jo.optString("requestId").trim();
        String sdp = jo.optString("sdp").trim();
        if (requestId.isEmpty() || !sdp.startsWith("v=0")) {
            CTWarn(TAG, "Ignoring incomplete managed video preflight offer.");
            return;
        }
        beginTrackerInteraction(requestId);
        videoPreflightPeer.start(
                R2CApplication.getAppCtxt(),
                requestId,
                sdp,
                jo.optJSONArray("iceServers"));
    }

    private void onVideoStreamRequestCancelled(@NonNull JSONObject jo) {
        String requestId = jo.optString("requestId").trim();
        if (requestId.isEmpty()) {
            CTWarn(TAG, "Ignoring incomplete managed video cancellation.");
            return;
        }
        // A request cancellation retires only this probe. Keep the peer's
        // executor available for the next independently authorized request.
        videoPreflightPeer.cancel();
        endTrackerInteraction(requestId);
        VideoStreamRequestListener listener = videoStreamRequestListener;
        if (listener != null) {
            listener.onVideoStreamRequestCancelled(requestId);
        }
        CTDebug(TAG, "Managed video request cancelled: " + requestId);
    }

    private void onVideoMediaOffer(@NonNull JSONObject jo) {
        String requestId = jo.optString("requestId").trim();
        String streamSessionId = jo.optString("streamSessionId").trim();
        String sdp = jo.optString("sdp").trim();
        if (requestId.isEmpty() || !sdp.startsWith("v=0")) {
            CTWarn(TAG, "Ignoring incomplete managed-video media offer.");
            return;
        }
        beginTrackerInteraction(requestId);
        VideoStreamRequestListener listener = videoStreamRequestListener;
        if (listener != null) {
            listener.onVideoMediaOffer(new VideoMediaOffer(
                    requestId,
                    streamSessionId,
                    jo.optString("requesterEmail").trim(),
                    jo.optString("routeKind", "unknown").trim(),
                    jo.optInt("selectedWidth", 0),
                    jo.optInt("selectedHeight", 0),
                    jo.optDouble("selectedFps", 0.0),
                    jo.optLong("selectedBitrateBps", 0L),
                    sdp,
                    jo.optJSONArray("iceServers")));
        }
    }

    private void sendVideoPreflightAnswer(
            @NonNull String requestId,
            @NonNull String sdp) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "video_preflight_answer");
            message.put("requestId", requestId);
            message.put("sdp", sdp);
            if (!sendJson(message)) {
                CTWarn(TAG, "Unable to send managed video preflight answer.");
            }
        } catch (Exception exception) {
            CTError(TAG, "Unable to encode managed video preflight answer.", exception);
        }
    }

    private void sendVideoPreflightResult(
            @NonNull String requestId,
            @NonNull String routeKind,
            long estimatedUplinkBps) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "video_preflight_result");
            message.put("requestId", requestId);
            message.put("routeKind", routeKind);
            message.put("estimatedUplinkBps", estimatedUplinkBps);
            if (!sendJson(message)) {
                CTWarn(TAG, "Unable to send managed video preflight result.");
            }
        } catch (Exception exception) {
            CTError(TAG, "Unable to encode managed video preflight result.", exception);
        }
    }

    @Override
    public void respondToVideoStreamRequest(
            @NonNull String requestId,
            boolean approved,
            int selectedWidth,
            int selectedHeight,
            double selectedFps,
            long selectedBitrateBps) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "video_stream_decision");
            message.put("requestId", requestId);
            message.put("decision", approved ? "approve" : "decline");
            if (approved) {
                message.put("selectedWidth", selectedWidth);
                message.put("selectedHeight", selectedHeight);
                message.put("selectedFps", selectedFps);
                message.put("selectedBitrateBps", selectedBitrateBps);
            }
            if (!sendJson(message)) {
                CTWarn(TAG, "Unable to send managed video decision.");
            }
            if (approved) {
                beginTrackerInteraction(requestId);
            } else {
                endTrackerInteraction(requestId);
            }
        } catch (Exception exception) {
            CTError(TAG, "Unable to encode managed video decision.", exception);
        }
    }

    @Override
    public void sendVideoMediaAnswer(@NonNull String requestId, @NonNull String sdp) {
        beginTrackerInteraction(requestId);
        sendManagedVideoMessage("video_media_answer", requestId, sdp, "");
    }

    @Override
    public void sendVideoStreamTerminated(@NonNull String requestId, @NonNull String reason) {
        sendManagedVideoMessage("video_stream_terminated", requestId, "", reason);
        endTrackerInteraction(requestId);
    }

    @Override
    public void respondToRecordingDownloadRequest(
            @NonNull String requestId, boolean approved) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "recording_download_decision");
            message.put("requestId", requestId);
            message.put("decision", approved ? "approve" : "decline");
            if (sendJson(message)) {
                CTInfo(TAG, "Recording transfer decision sent request=" + requestId
                        + " approved=" + approved);
            } else {
                CTWarn(TAG, "Recording transfer decision could not be sent request=" + requestId
                        + " approved=" + approved);
            }
            if (approved) {
                beginTrackerInteraction(requestId);
            } else {
                endTrackerInteraction(requestId);
            }
        } catch (Exception exception) {
            CTError(TAG, "Unable to encode recording download decision.", exception);
        }
    }

    @Override
    public void uploadRecordingDownload(@NonNull RecordingDownloadRequest request) {
        beginTrackerInteraction(request.requestId);
        if (request.consentRequired) {
            approvedRecordingUploads.put(request.requestId, request);
            CTInfo(TAG, "Starting operator-approved recording transfer request="
                    + request.requestId + " session=" + request.streamSessionId);
        } else {
            CTInfo(TAG, "Starting remotely authorized recording transfer request="
                    + request.requestId + " session=" + request.streamSessionId);
        }
        // The authenticated upload endpoint atomically authorizes an
        // awaiting-approval request before accepting its first chunk.  Start
        // the upload immediately so a delayed or lost websocket ack cannot
        // strand an approval that the tablet operator already granted.
        uploadRecordingDownloadNow(request);
    }

    private void uploadRecordingDownloadNow(@NonNull RecordingDownloadRequest request) {
        final String origin = trackerHttpOrigin;
        final String token = trackerApiKey;
        ManagedVideoSessionRecording recording = ManagedVideoSessionRecordingCatalog.INSTANCE.find(
                R2CApplication.getAppCtxt(), request.streamSessionId);
        if (origin == null || token == null || recording == null) {
            approvedRecordingUploads.remove(request.requestId);
            CTWarn(TAG, "Unable to attach requested recording " + request.streamSessionId);
            return;
        }
        recordingUploadExecutor.execute(() -> {
            final long total = recording.getFile().length();
            final long chunkSize = 8L * 1024L * 1024L;
            try {
                for (long start = 0; start < total; start += chunkSize) {
                    final long chunkStart = start;
                    final long length = Math.min(chunkSize, total - start);
                    RequestBody body = new RequestBody() {
                        @Override public MediaType contentType() { return MediaType.get("video/mp4"); }
                        @Override public long contentLength() { return length; }
                        @Override public void writeTo(@NonNull BufferedSink sink) throws IOException {
                            try (FileInputStream input = new FileInputStream(recording.getFile())) {
                                long skipped = 0;
                                while (skipped < chunkStart) {
                                    long step = input.skip(chunkStart - skipped);
                                    if (step <= 0) throw new EOFException("Unable to seek recording chunk");
                                    skipped += step;
                                }
                                byte[] buffer = new byte[64 * 1024];
                                long remaining = length;
                                while (remaining > 0) {
                                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                    if (count < 0) throw new EOFException("Recording ended during upload");
                                    sink.write(buffer, 0, count);
                                    remaining -= count;
                                }
                            }
                        }
                    };
                    Request upload = new Request.Builder()
                            .url(origin + request.uploadPath)
                            .header("X-SAR-Token", token)
                            .header("X-R2C-Functionality-Release",
                                    Integer.toString(BuildConfig.TRACKER_FUNCTIONALITY_RELEASE))
                            .header(
                                    "X-R2C-Filename",
                                    ManagedVideoSessionRecordingCatalog.INSTANCE
                                            .downloadFileName(recording))
                            .header("Content-Range", "bytes " + start + "-"
                                    + (start + length - 1) + "/" + total)
                            .put(body)
                            .build();
                    try (Response response = CaltopoSession.MyOkHttpClient.newCall(upload).execute()) {
                        if (!response.isSuccessful()) {
                            throw new IOException("HTTP " + response.code());
                        }
                    }
                }
                CTInfo(TAG, "Recording transfer completed request=" + request.requestId
                        + " bytes=" + total);
            } catch (Exception error) {
                CTError(TAG, "Recording transfer failed request=" + request.requestId, error);
            } finally {
                approvedRecordingUploads.remove(request.requestId);
                endTrackerInteraction(request.requestId);
            }
        });
    }

    private void sendManagedVideoMessage(
            @NonNull String type,
            @NonNull String requestId,
            @NonNull String sdp,
            @NonNull String reason) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", type);
            message.put("requestId", requestId);
            if (!sdp.isEmpty()) message.put("sdp", sdp);
            if (!reason.isEmpty()) message.put("reason", reason);
            if (!sendJson(message)) {
                CTWarn(TAG, "Unable to send managed-video message type=" + type);
            }
        } catch (Exception exception) {
            CTError(TAG, "Unable to encode managed-video message type=" + type, exception);
        }
    }

    private void handleAppUpdateRecommendation(@NonNull JSONObject jo) {
        int recommendedVersionCode = jo.optInt("recommendedAppVersionCode", 0);
        if (recommendedVersionCode <= 0) return;
        String updateUrl = jo.optString("updateUrl", "");
        AppUpdateAdvisory.onTrackerRecommendation(recommendedVersionCode, updateUrl);
    }

    private void onZoneUpdate(@Nullable JSONArray zones) {
        peers.clear();
        if (zones != null) {
            for (int i = 0; i < zones.length(); i++) {
                JSONObject zone = zones.optJSONObject(i);
                if (zone == null) continue;
                String zoneGuid = firstNonEmpty(zone.optString("guid"), zone.optString("zoneId"));
                if (zoneGuid.isEmpty() || zoneGuid.equals(myGuid)) continue;
                R2CMqttManager.PeerState ps = new R2CMqttManager.PeerState(zoneGuid);
                ps.name = firstNonEmpty(zone.optString("name"), zoneGuid);
                ps.lat = zone.optDouble("lat", 0.0);
                ps.lon = zone.optDouble("lng", 0.0);
                ps.caltopoRttMs = zone.optLong("caltopoRttMs", 2_000L);
                ps.lastSeenMs = zone.optLong("lastSeenMs", nowMs());
                ps.online = zone.optBoolean("online", true);
                peers.put(zoneGuid, ps);
            }
        }
        notifyPeerListChanged();
    }

    private void onHeartbeatAck(@NonNull JSONObject jo) {
        long nowMs = nowMs();
        long ackSeq = jo.optLong("clientSeq", -1L);
        long ownerLeaseExpireTs = jo.optLong("ownerLeaseExpireTs", 0L);
        if (ackSeq <= 0) {
            CTWarn(TAG, "heartbeat_ack missing/invalid clientSeq");
            return;
        }
        if (ackSeq < lastHeartbeatSeqAcked) {
            CTWarn(TAG, String.format(Locale.US,
                    "heartbeat_ack stale seq=%d lastAckSeq=%d", ackSeq, lastHeartbeatSeqAcked));
            return;
        }
        if (ackSeq != lastHeartbeatSeqSent) {
            forceReconnect(String.format(Locale.US,
                    "heartbeat_ack seq mismatch ack=%d expected=%d", ackSeq, lastHeartbeatSeqSent));
            return;
        }
        lastHeartbeatSeqAcked = ackSeq;
        lastHeartbeatAckAtMs = nowMs;
        lastServerAcknowledgementAtMs = nowMs;
        if (ownerLeaseExpireTs > 0) {
            lastOwnerLeaseExpireTs = ownerLeaseExpireTs;
        }
        CTDebug(TAG, String.format(Locale.US,
                "heartbeat_ack received: seq=%d rttMs=%d ownerLeaseExpireTs=%d",
                ackSeq, nowMs - lastHeartbeatSentAtMs, ownerLeaseExpireTs));
        flushPendingConfirmations();
    }

    private void applyOwnerAssignment(@NonNull String remoteId, @Nullable String ownerGuid, long leaseSeq, long leaseExpireTs) {
        if (remoteId.isEmpty() || ownerGuid == null || ownerGuid.isEmpty()) return;
        Long previousLeaseSeq = leaseSeqByRemoteId.get(remoteId);
        if (leaseSeq >= 0 && previousLeaseSeq != null && leaseSeq < previousLeaseSeq) {
            CTWarn(TAG, String.format(Locale.US,
                    "applyOwnerAssignment(%s): ignoring stale leaseSeq=%d previousLeaseSeq=%d ownerGuid='%s'",
                    remoteId, leaseSeq, previousLeaseSeq, ownerGuid));
            return;
        }
        ownerByRemoteId.put(remoteId, ownerGuid);
        if (leaseSeq >= 0) {
            leaseSeqByRemoteId.put(remoteId, leaseSeq);
        }
        if (leaseExpireTs > 0 && ownerGuid.equals(myGuid)) {
            lastOwnerLeaseExpireTs = leaseExpireTs;
        }
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending != null) {
            pending.fallbackTimer.stop();
            pending.ownershipActivationTimer.stop();
            if (ownerGuid.equals(myGuid) && locallyConfirmedRemoteIds.containsKey(remoteId)) {
                lastOwnerActivityAtMs = nowMs();
                CTInfo(TAG, String.format(Locale.US,
                        "applyOwnerAssignment(%s): ownership granted locally; publishing in %d ms mappedId='%s' trackLabel='%s' queuedPoints=%d",
                        remoteId, handoffDelayMs, pending.droneSpec.getMappedId(), pending.droneSpec.trackLabel(), pending.liveTrack.getQueuedPointCount()));
                Runnable activateOwnership = () -> {
                    if (!started) return;
                    String currentOwnerGuid = ownerByRemoteId.get(remoteId);
                    if (myGuid != null && myGuid.equals(currentOwnerGuid)) {
                        pending.liveTrack.setLocalOwner(true);
                    }
                };
                if (handoffDelayMs <= 0L) {
                    activateOwnership.run();
                } else {
                    pending.ownershipActivationTimer.start(activateOwnership, handoffDelayMs, 0L);
                }
            } else if (ownerGuid.equals(myGuid)) {
                CTInfo(TAG, String.format(Locale.US,
                        "applyOwnerAssignment(%s): tracker lease granted locally but waiting for local Save before publishing mappedId='%s' trackLabel='%s'",
                        remoteId, pending.droneSpec.getMappedId(), pending.droneSpec.trackLabel()));
                pending.liveTrack.setLocalOwner(false);
            } else {
                CTInfo(TAG, String.format(Locale.US,
                        "applyOwnerAssignment(%s): ownership assigned to peer guid='%s' mappedId='%s' trackLabel='%s'",
                        remoteId, ownerGuid, pending.droneSpec.getMappedId(), pending.droneSpec.trackLabel()));
                pending.liveTrack.setLocalOwner(false);
            }
        }
        notifyPeerListChanged();
        scheduleIdleParkIfEligible();
    }

    private void clearOwner(@NonNull String remoteId) {
        clearOwner(remoteId, "");
    }

    private void clearOwner(@NonNull String remoteId, @NonNull String expectedOwnerGuid) {
        String currentOwnerGuid = ownerByRemoteId.get(remoteId);
        if (!expectedOwnerGuid.isEmpty() && currentOwnerGuid != null && !expectedOwnerGuid.equals(currentOwnerGuid)) {
            CTWarn(TAG, String.format(Locale.US,
                    "clearOwner(%s): ignoring stale owner_expired prevOwnerGuid='%s' currentOwnerGuid='%s'",
                    remoteId, expectedOwnerGuid, currentOwnerGuid));
            return;
        }
        ownerByRemoteId.remove(remoteId);
        leaseSeqByRemoteId.remove(remoteId);
        locallyConfirmedRemoteIds.remove(remoteId);
        lastSightingSentByRemoteId.remove(remoteId);
        CaltopoClient.ClearCurrentPeerDroneConfirmation(remoteId);
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending != null) {
            pending.ownershipActivationTimer.stop();
            pending.liveTrack.setLocalOwner(false);
        }
        notifyPeerListChanged();
        scheduleIdleParkIfEligible();
    }

    private void onRelaySighting(@NonNull JSONObject jo) {
        String remoteId = jo.optString("remoteId");
        if (remoteId.isEmpty() || !isLocalOwner(remoteId)) return;
        String fromZoneId = firstNonEmpty(jo.optString("fromZoneId"), jo.optString("zoneId"));
        if (fromZoneId.equals(myGuid)) {
            CTDebug(TAG, "Ignoring self-relayed sighting for " + remoteId);
            return;
        }
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending == null) return;
        pending.liveTrack.onPeerWaypoint(
                fromZoneId,
                jo.optDouble("lat", 0.0),
                jo.optDouble("lng", 0.0),
                jo.optDouble("altM", 0.0),
                jo.optLong("droneTs", 0L),
                parseTelemetry(jo.optJSONObject("telemetry"))
        );
    }

    private void onPeerTrafficShadow(@NonNull JSONObject jo) {
        String remoteId = jo.optString("remoteId");
        String sourceZoneId = jo.optString("fromZoneId");
        String source = jo.optString("source");
        if (remoteId.isEmpty() || sourceZoneId.isEmpty() || sourceZoneId.equals(myGuid)) return;
        long nowMs = nowMs();
        PeerTrafficMapRegistry.update(
                sourceZoneId,
                remoteId,
                jo.optString("mappedId", remoteId),
                source,
                jo.optLong("seq", -1L),
                jo.optLong("sampleTs", 0L),
                nowMs,
                jo.optDouble("lat", Double.NaN),
                jo.optDouble("lng", Double.NaN),
                jo.has("mslAltM") ? jo.optDouble("mslAltM") : null,
                jo.has("headingDeg") ? jo.optDouble("headingDeg") : null,
                jo.has("groundSpeedKnots") ? jo.optDouble("groundSpeedKnots") : null);
        String logKey = sourceZoneId + "|" + source + "|" + remoteId;
        Long lastLogMs = lastTrafficShadowLogByKey.get(logKey);
        if (lastLogMs != null && nowMs - lastLogMs < TRAFFIC_DIAGNOSTIC_INTERVAL_MS) return;
        lastTrafficShadowLogByKey.put(logKey, nowMs);
        CTDebug(TAG, String.format(Locale.US,
                "Peer traffic shadow remoteId=%s source=%s fromZoneId=%s seq=%d sourceAgeMs=%d trackerAgeMs=%d headingDeg=%s speedKt=%s altAgeMs=%d altState=%s mslAltM=%s mslAltAgeMs=%s correctionM=%s demSource=%s demResolutionM=%s incidentPadFt=%s nearestDistanceM=%s schedulingPadFt=%s shadowIntervalMs=%d",
                remoteId,
                source,
                sourceZoneId,
                jo.optLong("seq", -1L),
                Math.max(0L, nowMs - jo.optLong("sampleTs", 0L)),
                Math.max(0L, nowMs - jo.optLong("receivedTs", 0L)),
                jo.has("headingDeg") ? String.valueOf(jo.optDouble("headingDeg")) : "null",
                jo.has("groundSpeedKnots") ? String.valueOf(jo.optDouble("groundSpeedKnots")) : "null",
                Math.max(0L, nowMs - jo.optLong("altSampleTs", jo.optLong("sampleTs", 0L))),
                jo.optString("altCalibrationState", "unavailable"),
                jo.has("mslAltM") ? String.valueOf(jo.optDouble("mslAltM")) : "null",
                jo.has("mslAltSampleTs") ? String.valueOf(Math.max(0L, nowMs - jo.optLong("mslAltSampleTs"))) : "null",
                jo.has("altCorrectionM") ? String.valueOf(jo.optDouble("altCorrectionM")) : "null",
                jo.optString("demSource", ""),
                jo.has("demResolutionM") ? String.valueOf(jo.optDouble("demResolutionM")) : "null",
                jo.has("incidentPadFt") ? String.valueOf(jo.optDouble("incidentPadFt")) : "null",
                jo.has("shadowNearestDistanceM") ? String.valueOf(jo.optDouble("shadowNearestDistanceM")) : "null",
                jo.has("shadowSchedulingPadFt") ? String.valueOf(jo.optDouble("shadowSchedulingPadFt")) : "null",
                jo.optLong("shadowIntervalMs", 0L)));
    }

    private void onTrafficSchedule(@NonNull JSONObject jo) {
        if (!trafficSourceEpoch.equals(jo.optString("sourceEpoch"))) return;
        String remoteId = jo.optString("remoteId");
        String source = jo.optString("source");
        if (remoteId.isEmpty() || (!"rid".equals(source) && !"sei".equals(source))) return;
        long intervalMs = Math.max(TRAFFIC_SEND_INTERVAL_MS,
                Math.min(TRAFFIC_SEND_INTERVAL_MAX_MS,
                        jo.optLong("shadowIntervalMs", TRAFFIC_SEND_INTERVAL_MS)));
        String key = source + "|" + remoteId;
        Long previousIntervalMs = trafficSendIntervalByKey.put(key, intervalMs);
        if (previousIntervalMs != null && previousIntervalMs == intervalMs) return;
        CTDebug(TAG, String.format(Locale.US,
                "Peer traffic schedule remoteId=%s source=%s seq=%d intervalMs=%d incidentPadFt=%s nearestDistanceM=%s schedulingPadFt=%s",
                remoteId,
                source,
                jo.optLong("seq", -1L),
                intervalMs,
                jo.has("incidentPadFt") ? String.valueOf(jo.optDouble("incidentPadFt")) : "null",
                jo.has("shadowNearestDistanceM") ? String.valueOf(jo.optDouble("shadowNearestDistanceM")) : "null",
                jo.has("shadowSchedulingPadFt") ? String.valueOf(jo.optDouble("shadowSchedulingPadFt")) : "null"));
    }

    private void onDroneConfirmedByPeer(@NonNull JSONObject jo) {
        if (mapId == null || mapId.trim().isEmpty()) {
            CTDebug(TAG, "Standalone requires local confirmation; ignoring peer drone_confirmed");
            return;
        }
        String remoteId = jo.optString("remoteId");
        if (remoteId.isEmpty()) return;
        CTDebug(TAG, String.format(Locale.US,
                "drone_confirmed received: remoteId=%s confirmedBy=%s mappedId='%s'",
                remoteId,
                firstNonEmpty(jo.optString("confirmedByGuid"), firstNonEmpty(jo.optString("guid"), jo.optString("zoneId"))),
                jo.optString("mappedId")));
        String confirmedByGuid = firstNonEmpty(
                jo.optString("confirmedByGuid"),
                firstNonEmpty(jo.optString("guid"), jo.optString("zoneId")));
        if (myGuid != null && myGuid.equals(confirmedByGuid)) {
            locallyConfirmedRemoteIds.put(remoteId, true);
        } else {
            locallyConfirmedRemoteIds.remove(remoteId);
        }
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
                remoteId,
                jo.optString("org"),
                jo.optString("model"),
                jo.optString("ownerName"),
                jo.optString("mappedId"));
        if (!confirmedByGuid.isEmpty()) {
            applyOwnerAssignment(
                    remoteId,
                    confirmedByGuid,
                    jo.optLong("leaseSeq", -1L),
                    jo.optLong("leaseExpireTs", 0L));
        }
    }

    void handleOwnerAssignedForTesting(@NonNull String remoteId, @NonNull String ownerGuid, long leaseSeq) {
        applyOwnerAssignment(remoteId, ownerGuid, leaseSeq, 0L);
    }

    void handleOwnerExpiredForTesting(@NonNull String remoteId) {
        clearOwner(remoteId);
    }

    void handleRelaySightingForTesting(
            @NonNull String remoteId,
            @NonNull String fromZoneId,
            double lat,
            double lng,
            double altM,
            long droneTs,
            @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        if (!isLocalOwner(remoteId)) return;
        if (fromZoneId.equals(myGuid)) return;
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending == null) return;
        pending.liveTrack.onPeerWaypoint(fromZoneId, lat, lng, altM, droneTs, telemetry);
    }

    void markHeartbeatSentForTesting(long seq, long sentAtMs) {
        heartbeatSeqCounter = Math.max(heartbeatSeqCounter, seq);
        lastHeartbeatSeqSent = seq;
        lastHeartbeatSentAtMs = sentAtMs;
    }

    void handleHeartbeatAckForTesting(long ackSeq, long ownerLeaseExpireTs) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("clientSeq", ackSeq);
            if (ownerLeaseExpireTs > 0L) {
                jo.put("ownerLeaseExpireTs", ownerLeaseExpireTs);
            }
        } catch (Exception ignored) {
        }
        onHeartbeatAck(jo);
    }

    void handleHelloAckForTesting() {
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "hello_ack");
        } catch (Exception ignored) {
        }
        handleIncomingMessage(jo.toString());
    }

    void handleHelloAckForTesting(int recommendedVersionCode, @Nullable String updateUrl) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "hello_ack");
            jo.put("recommendedAppVersionCode", recommendedVersionCode);
            if (updateUrl != null) {
                jo.put("updateUrl", updateUrl);
            }
        } catch (Exception ignored) {
        }
        handleIncomingMessage(jo.toString());
    }

    void checkAckLivenessForTesting() {
        checkAckLiveness();
    }

    void parkIfIdleForTesting() {
        parkIfIdle();
    }

    void sendHeartbeatForTesting() {
        sendHeartbeat();
    }

    void stopBackgroundTimersForTesting() {
        heartbeatTimer.stop();
        reconnectTimer.stop();
        ackWatchdogTimer.stop();
        heartbeatCoalesceTimer.stop();
        idleParkTimer.stop();
    }

    void stopBackgroundTimersAndResetHeartbeatStateForTesting() {
        stopBackgroundTimersForTesting();
        suppressScheduledHeartbeatRequestsForTesting = true;
        resetHeartbeatStateForNewTransport();
    }

    @NonNull
    String getLastReconnectCauseForTesting() {
        return lastReconnectCause;
    }

    long getForcedReconnectCountForTesting() {
        return forcedReconnectCount;
    }

    @Nullable
    String getLastOutboundJsonForTesting() {
        return lastOutboundJsonForTesting;
    }

    @Nullable
    String getLastWaypointRemoteIdForTesting() {
        return lastWaypointRemoteIdForTesting;
    }

    long getLastHeartbeatSeqSentForTesting() {
        return lastHeartbeatSeqSent;
    }

    long getLastHeartbeatSeqAckedForTesting() {
        return lastHeartbeatSeqAcked;
    }

    private void notifyPeerListChanged() {
        R2CMqttManager.PeerListChangedListener listener = peerListChangedListener;
        if (listener == null) return;

        List<R2CMqttManager.PeerState> snapshot = new ArrayList<>(peers.values());
        if (myGuid != null && !myGuid.isEmpty()) {
            R2CMqttManager.PeerState self = new R2CMqttManager.PeerState(myGuid);
            self.name = myName != null && !myName.isEmpty() ? myName : myGuid;
            self.lat = myLat;
            self.lon = myLon;
            self.caltopoRttMs = myCaltopoRttMs;
            self.lastSeenMs = nowMs();
            self.online = started;
            snapshot.add(self);
        }
        for (R2CMqttManager.PeerState ps : snapshot) {
            ps.ownedDrones.clear();
        }
        for (PendingDrone pending : pendingDrones.values()) {
            String ownerGuid = ownerByRemoteId.get(pending.droneSpec.getRemoteId());
            if (ownerGuid == null) continue;
            R2CMqttManager.PeerState ps = null;
            for (R2CMqttManager.PeerState candidate : snapshot) {
                if (ownerGuid.equals(candidate.guid)) {
                    ps = candidate;
                    break;
                }
            }
            if (ps != null) {
                ps.ownedDrones.add(pending.droneSpec.copy());
            }
        }
        listener.onPeerListChanged(snapshot);
    }

    @NonNull
    private static String buildTrackerWebSocketUrl(@NonNull String trackerUrlPrefix) {
        String url = trackerUrlPrefix.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("https://")) {
            url = "wss://" + url.substring("https://".length());
        } else if (url.startsWith("http://")) {
            url = "ws://" + url.substring("http://".length());
        }
        return url + "/ws/r2c";
    }

    @NonNull
    private static String trackerHttpOrigin(@NonNull String trackerUrlPrefix) {
        try {
            URI uri = URI.create(trackerUrlPrefix.trim());
            String scheme = uri.getScheme();
            if ("wss".equalsIgnoreCase(scheme)) scheme = "https";
            if ("ws".equalsIgnoreCase(scheme)) scheme = "http";
            return new URI(scheme, uri.getAuthority(), null, null, null).toString();
        } catch (Exception ignored) {
            return trackerUrlPrefix.replaceAll("/+$", "");
        }
    }

    @NonNull
    private static String describeToken(@Nullable String token) {
        if (token == null) return "<null>";
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return "<empty>";
        if (trimmed.length() <= 4) {
            return String.format(Locale.US, "len=%d suffix=%s", trimmed.length(), trimmed);
        }
        return String.format(Locale.US, "len=%d suffix=%s",
                trimmed.length(), trimmed.substring(trimmed.length() - 4));
    }

    @NonNull
    private static String normalizeTrackerToken(@Nullable String token) {
        return token == null ? "" : token.trim();
    }

    private static void putTelemetry(@NonNull JSONObject jo, @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        if (telemetry == null) return;
        JSONObject t = new JSONObject();
        try {
            if (telemetry.aircraftTrackDeg != null) putFinite(t, "headingDeg", telemetry.aircraftTrackDeg);
            if (telemetry.aircraftGsKnots != null) putFinite(t, "groundSpeedKnots", telemetry.aircraftGsKnots);
            if (telemetry.aircraftAltitudeRateFpm != null) putFinite(t, "verticalRateFpm", telemetry.aircraftAltitudeRateFpm);
            jo.put("telemetry", t);
        } catch (Exception ignored) {
        }
    }

    private static void putFinite(@NonNull JSONObject jo, @NonNull String name, double value) throws Exception {
        if (Double.isFinite(value)) {
            jo.put(name, value);
        }
    }

    @Nullable
    private static CtDroneSpec.PositionTelemetry parseTelemetry(@Nullable JSONObject jo) {
        if (jo == null) return null;
        Double verticalRateFpm = jo.has("verticalRateFpm") ? jo.optDouble("verticalRateFpm") : null;
        Double groundSpeedKnots = jo.has("groundSpeedKnots") ? jo.optDouble("groundSpeedKnots") : null;
        Double headingDeg = jo.has("headingDeg") ? jo.optDouble("headingDeg") : null;
        return new CtDroneSpec.PositionTelemetry(verticalRateFpm, groundSpeedKnots, headingDeg);
    }

    @NonNull
    private static String firstNonEmpty(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isEmpty()) return first;
        return second != null ? second : "";
    }

    private long ageSince(long timestampMs) {
        if (timestampMs <= 0) return -1L;
        return nowMs() - timestampMs;
    }

    @NonNull
    private static String formatDuration(long durationMs) {
        long safeDurationMs = Math.max(durationMs, 0L);
        if (safeDurationMs < 1_000L) {
            return safeDurationMs + " ms";
        }
        long seconds = safeDurationMs / 1_000L;
        if (seconds < 60L) {
            return seconds + " sec";
        }
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (remainingSeconds == 0L) {
            return minutes + " min";
        }
        return minutes + " min " + remainingSeconds + " sec";
    }

    static void setTransportFactoryForTesting(@Nullable TrackerCoordinationTransportFactory factory) {
        transportFactory = factory != null ? factory : OkHttpTrackerCoordinationTransport::new;
    }

    static void setTrackerConfigForTesting(@Nullable String trackerUrlPrefix, @Nullable String trackerApiKey) {
        trackerUrlPrefixOverrideForTesting = trackerUrlPrefix;
        trackerApiKeyOverrideForTesting = trackerApiKey;
    }

    static void setHandoffDelayMsForTesting(long delayMs) {
        handoffDelayMs = Math.max(0L, delayMs);
    }

    static void setIdleParkDelayMsForTesting(long delayMs) {
        idleParkDelayMs = Math.max(0L, delayMs);
    }

    static void setTimeSourceForTesting(@NonNull TimeSource override) {
        timeSource = override;
    }

    void setHardFailureListenerForTesting(@Nullable HardFailureListener listener) {
        hardFailureListener = listener;
    }

    void setHardFailureListener(@Nullable HardFailureListener listener) {
        hardFailureListener = listener;
    }

    private static long nowMs() {
        return timeSource.now();
    }

    static void resetForTesting() {
        INSTANCE.stop();
        INSTANCE.mapId = null;
        INSTANCE.myGuid = null;
        INSTANCE.myName = null;
        INSTANCE.trackerApiKey = null;
        INSTANCE.trackerWsUrl = null;
        INSTANCE.myLat = 0.0;
        INSTANCE.myLon = 0.0;
        INSTANCE.myCaltopoRttMs = 2_000L;
        INSTANCE.peerListChangedListener = null;
        INSTANCE.videoStreamRequestListener = null;
        INSTANCE.seenVideoStreamRequestIds.clear();
        INSTANCE.managedVideoIncidentName = "";
        INSTANCE.managedVideoStreams = Collections.emptyList();
        INSTANCE.managedVideoThumbnailPreviewUntilMs = 0L;
        INSTANCE.hardFailureListener = null;
        INSTANCE.hardFailureNotified = false;
        INSTANCE.forcedReconnectCount = 0L;
        INSTANCE.lastOwnerActivityAtMs = 0L;
        INSTANCE.intentionallyParked = false;
        INSTANCE.standaloneStandbyEligible = false;
        INSTANCE.suppressScheduledHeartbeatRequestsForTesting = false;
        INSTANCE.lastOutboundJsonForTesting = null;
        INSTANCE.lastWaypointRemoteIdForTesting = null;
        transportFactory = OkHttpTrackerCoordinationTransport::new;
        trackerUrlPrefixOverrideForTesting = null;
        trackerApiKeyOverrideForTesting = null;
        timeSource = System::currentTimeMillis;
        handoffDelayMs = DEFAULT_HANDOFF_DELAY_MS;
        idleParkDelayMs = DEFAULT_IDLE_PARK_DELAY_MS;
    }
}
