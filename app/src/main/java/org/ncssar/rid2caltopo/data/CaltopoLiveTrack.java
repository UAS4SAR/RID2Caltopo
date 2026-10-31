/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */


package org.ncssar.rid2caltopo.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.BuildConfig;
import org.ncssar.rid2caltopo.app.R2CActivity;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecording;
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecordingCatalog;
import org.ncssar.rid2caltopo.video.ManagedVideoStreamPresence;
import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetryRegistry;
import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetrySample;

import org.opendroneid.android.data.Util;

import android.os.Looper;

import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedList;

/** CaltopoLiveTrack
 * Creates and reports track waypoints to a CaltopoMap.  When multiple R2C
 * instances are connected to the same map, ownership is coordinated via
 * R2CMqttManager: only the owning instance publishes waypoints to CalTopo.
 * Ownership is assigned by MQTT-based score (proximity + CalTopo RTT);
 * this class simply acts on the result via {@link #setLocalOwner}.
 */

public class CaltopoLiveTrack implements CaltopoMap.MapStatusListener, LiveTrackOwnerDelegate {
    private static final String ICON_LATENCY_TAG = "RidIconLatency";
    public interface LocalTrackListener {
        void onLocalTrackPoint(
                @NonNull String remoteId,
                @NonNull String mappedId,
                double lat,
                double lng,
                double altitudeMeters,
                long timestampMsec
        );
    }
    public interface LocalTrackFinishedListener {
        void onLocalTrackFinished(
                @NonNull String remoteId,
                @NonNull String mappedId,
                @NonNull String reason
        );
    }

    private static final String TAG = "CaltopoLiveTrack";
    private static final long LIVE_TRACK_SIDE_EFFECT_SLOW_MS = 250L;
    private static final long DEFERRED_VIDEO_LINK_RETRY_MS = 2_000L;
    private static final long DEFERRED_VIDEO_LINK_WINDOW_MS = 30_000L;
    /** Bound Shape POST wait so one flight's archive cannot occupy the archive lane indefinitely. */
    private static final long ARCHIVE_NETWORK_TIMEOUT_MS = 15_000L;
    private static final long SEI_POSITION_PUBLISH_INTERVAL_MS = 1_000L;
    private static final ScheduledExecutorService DeferredVideoLinkExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "CaltopoDeferredVideoLink");
                thread.setDaemon(true);
                return thread;
            });
    private static final java.util.concurrent.ExecutorService CatalogLookupExecutor =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "CaltopoRecordingCatalogLookup");
                thread.setDaemon(true);
                return thread;
            });
    private static final ScheduledExecutorService SeiPositionExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "CaltopoSeiPosition");
                thread.setDaemon(true);
                return thread;
            });
    private static final Util.SimpleMovingAverage CaltopoRttInMsec = new Util.SimpleMovingAverage(10);
    private static final Hashtable<String, CaltopoLiveTrack> LiveTrackByRemoteId = new Hashtable<>(16);
    private static final CopyOnWriteArrayList<LocalTrackListener> LocalTrackListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<LocalTrackFinishedListener> LocalTrackFinishedListeners = new CopyOnWriteArrayList<>();
    private CaltopoOp startLiveTrackOp;
    private volatile String liveTrackId;
    private volatile String requestedLiveTrackId;
    private String publicationMapId;
    /**
     * Stable WaypointTrackJournal flight key for this LiveTrack segment. Chosen on the
     * first accepted point (publication id when known; otherwise a local RidFlightId) and
     * held for the segment so ANR/kill before liveTrackId still recovers every point.
     */
    @Nullable private String durableJournalFlightId;

    static boolean hasActivePublication(String id) {
        for (CaltopoLiveTrack track : LiveTrackByRemoteId.values()) {
            if (id.equals(track.liveTrackId) || id.equals(track.requestedLiveTrackId)) return true;
        }
        return false;
    }
    private boolean startInFlight;
    private long nextStartAttemptAtMs;
    private long publicationGeneration;

    private static class QueuedPoint {
        final double lat;
        final double lng;
        final double ele;
        final long timestampMsec;
        @Nullable final CtDroneSpec.PositionTelemetry telemetry;
        @Nullable final StreamCameraTelemetrySample cameraTelemetry;

        QueuedPoint(double lat, double lng, double ele, long timestampMsec,
                    @Nullable CtDroneSpec.PositionTelemetry telemetry,
                    @Nullable StreamCameraTelemetrySample cameraTelemetry) {
            this.lat = lat;
            this.lng = lng;
            this.ele = ele;
            this.timestampMsec = timestampMsec;
            this.telemetry = telemetry;
            this.cameraTelemetry = cameraTelemetry;
        }
    }
    private static final double DUPLICATE_COORD_EPSILON = 0.000001;
    private static final double DUPLICATE_ALT_EPSILON_METERS = 0.5;
    private final LinkedList<QueuedPoint> linePoints = new LinkedList<>();
    /** Lock-free size mirror for {@link #hasOwnedActivePublication()}; mutated with linePoints under the track monitor. */
    private final AtomicInteger queuedPointCount = new AtomicInteger(0);
    private int linePointsSentCount;
    private int linePointsConfirmedCount;
    private String folderId;
    private volatile boolean active;
    /** True once R2CMqttManager has confirmed this instance owns this drone. */
    private volatile boolean localOwner = false;
    private static final class OwnershipRequest {
        final boolean owner;
        OwnershipRequest(boolean owner) { this.owner = owner; }
    }
    private final java.util.concurrent.atomic.AtomicReference<OwnershipRequest> ownershipRequest =
            new java.util.concurrent.atomic.AtomicReference<>();

    private boolean ownsPublication() {
        OwnershipRequest requested = ownershipRequest.get();
        return localOwner && (requested == null || requested.owner);
    }
    @NonNull private final R2cRuntime runtime;
    private String myRemoteId;
    private CtDroneSpec droneSpec;
    // Segment identity outlives CtDroneSpec.reset() on the UI thread. Only
    // creation/restart and an explicit rename replace it; archives and journal
    // checkpoints must never read the next flight's mutable label.
    @NonNull private volatile String segmentTrackLabel;
    private volatile boolean shuttingDown = false;
    private int consecutiveUpdateFails = 0;
    private long lastInterruptedJournalWriteMs = 0L;
    private final Object journalWriteLock = new Object();
    private long journalRevision;
    // Only accessed under journalWriteLock, never by UI status readers.
    private long persistedJournalRevision;

    private static final class JournalSnapshot {
        final long revision;
        final String mapId, remoteId, trackId, label, description;
        final JSONArray points;
        JournalSnapshot(long revision, String mapId, String remoteId, String trackId,
                        String label, String description, JSONArray points) {
            this.revision = revision; this.mapId = mapId; this.remoteId = remoteId;
            this.trackId = trackId; this.label = label; this.description = description;
            this.points = points;
        }
    }
    private long trackObservedStartedAtMs;
    private long trackObservedEndedAtMs;
    private long lastSeiPositionTimestampMs;
    @Nullable private CaltopoOp thumbnailMetadataRefreshOp;
    @NonNull private String lastThumbnailMetadataRevision = "";
    public static long GetCaltopoRttInMsec() { return CaltopoRttInMsec.get();}
    private volatile CaltopoMap.MapStatusListener.mapStatus mapStatus;

    static {
        SeiPositionExecutor.scheduleAtFixedRate(
                CaltopoLiveTrack::publishFreshSeiPositions,
                SEI_POSITION_PUBLISH_INTERVAL_MS,
                SEI_POSITION_PUBLISH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private static void publishFreshSeiPositions() {
        for (CaltopoLiveTrack track : new LinkedList<>(LiveTrackByRemoteId.values())) {
            try {
                track.publishFreshSeiPositionIfAvailable();
            } catch (Exception error) {
                CTError(TAG, "Unable to publish fresh video SEI position" + " (" + error.getClass().getSimpleName() + ")");
            }
        }
    }

    private static void logSideEffectIfSlow(@NonNull String step,
                                            @NonNull String remoteId,
                                            @NonNull String mappedId,
                                            long elapsedMs) {
        if (elapsedMs < LIVE_TRACK_SIDE_EFFECT_SLOW_MS) return;
        CTWarn(TAG, String.format(Locale.US,
                "liveTrack slow step=%s elapsedMs=%d remoteId=%s mappedId=%s",
                step, elapsedMs, remoteId, mappedId));
    }

    public CaltopoLiveTrack(@NonNull CtDroneSpec droneSpec, double lat, double lng, double ele,
                            long droneTimestampInMsec) throws RuntimeException {
        segmentTrackLabel = droneSpec.trackLabel();
        if (segmentTrackLabel.isEmpty()) {
            throw new RuntimeException("CaltopoLiveTrack(): trackLabel is required.");
        }
        mapStatus = CaltopoMap.GetMapStatus();
        runtime = R2cRuntimeRegistry.getDefaultRuntime();
        CaltopoMap.AddMapStatusListener(this);
        CaltopoMap.AddLiveTrack(this);
        myRemoteId = droneSpec.getRemoteId();
        LiveTrackByRemoteId.put(myRemoteId, this);
        active = true;
        this.droneSpec = droneSpec;
        droneSpec.setMyLiveTrack(this);
        this.localOwner = false;

        trackObservedStartedAtMs = trackObservedEndedAtMs = System.currentTimeMillis();

        StreamCameraTelemetrySample cameraTelemetry = currentCameraTelemetry(lat, lng, ele);
        double effectiveLat = preferredLatitude(cameraTelemetry, lat);
        double effectiveLng = preferredLongitude(cameraTelemetry, lng);
        double effectiveEle = preferredAltitude(cameraTelemetry, ele);
        linePoints.add(new QueuedPoint(effectiveLat, effectiveLng, effectiveEle, droneTimestampInMsec,
                droneSpec.getLastPositionTelemetry(), cameraTelemetry));
        queuedPointCount.set(linePoints.size());
        appendDurableJournalPointLocked(effectiveLat, effectiveLng, effectiveEle, droneTimestampInMsec,
                System.currentTimeMillis(), "live");
        notifyLocalTrackPoint(effectiveLat, effectiveLng, effectiveEle, droneTimestampInMsec);
        linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;

        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) return;

        // Ask R2CMqttManager to determine ownership.  It will call setLocalOwner()
        // (possibly after a brief discovery window) when a decision is reached.
        double distMeters = CaltopoMap.DistanceFromMeInMeters(lat, lng);
        runtime.getPeerCoordinator()
                .onLiveTrackCreated(this, droneSpec, distMeters, droneTimestampInMsec);
    }

    public void startNewTrack(double lat, double lng, double ele, long droneTimestampInMsec) {
        final String label = droneSpec.trackLabel();
        CaltopoRecoveryWork.offMainStart(() -> startNewTrackOnWorker(lat, lng, ele, droneTimestampInMsec, label));
    }

    private synchronized void startNewTrackOnWorker(double lat, double lng, double ele, long droneTimestampInMsec, String label) {
        if (shuttingDown) return;
        // A transport failure is not a new flight segment.
        if (active || requestedLiveTrackId != null || liveTrackId != null || startInFlight) {
            active = true;
            publishDirect(lat, lng, (long) ele, droneTimestampInMsec);
            return;
        }
        long startedAtMs = System.currentTimeMillis();

        // Receiving a segment and owning its publication are separate states. Keep
        // buffering while confirmation/ownership is pending, just as the constructor
        // does, instead of recreating peer discovery for every returning waypoint.
        active = true;

        if (!label.isEmpty()) segmentTrackLabel = label;

        trackObservedStartedAtMs = trackObservedEndedAtMs = startedAtMs;
        durableJournalFlightId = null;
        lastSeiPositionTimestampMs = 0L;
        thumbnailMetadataRefreshOp = null;
        lastThumbnailMetadataRevision = "";

        StreamCameraTelemetrySample cameraTelemetry = currentCameraTelemetry(lat, lng, ele);
        double effectiveLat = preferredLatitude(cameraTelemetry, lat);
        double effectiveLng = preferredLongitude(cameraTelemetry, lng);
        double effectiveEle = preferredAltitude(cameraTelemetry, ele);
        linePoints.add(new QueuedPoint(effectiveLat, effectiveLng, effectiveEle, droneTimestampInMsec,
                droneSpec.getLastPositionTelemetry(), cameraTelemetry));
        queuedPointCount.set(linePoints.size());
        appendDurableJournalPointLocked(effectiveLat, effectiveLng, effectiveEle, droneTimestampInMsec,
                System.currentTimeMillis(), "live");
        long notifyStartedAtMs = System.currentTimeMillis();
        notifyLocalTrackPoint(effectiveLat, effectiveLng, effectiveEle, droneTimestampInMsec);
        logSideEffectIfSlow("startNewTrack.notifyLocalTrackPoint", myRemoteId, droneSpec.getMappedId(),
                System.currentTimeMillis() - notifyStartedAtMs);
        linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;
        startLiveTrackOp = null;
        localOwner = false;

        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) {
            logSideEffectIfSlow("startNewTrack.total.mapDown", myRemoteId, droneSpec.getMappedId(),
                    System.currentTimeMillis() - startedAtMs);
            return;
        }

        double distMeters = CaltopoMap.DistanceFromMeInMeters(lat, lng);
        runtime.getPeerCoordinator()
                .onLiveTrackCreated(this, droneSpec, distMeters, droneTimestampInMsec);
        logSideEffectIfSlow("startNewTrack.total", myRemoteId, droneSpec.getMappedId(),
                System.currentTimeMillis() - startedAtMs);
    }

    public void mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus mapStatusIn,
                                @Nullable CaltopoNode.MapNode map, @Nullable String emsg) {
        mapStatus = mapStatusIn;
        CTDebug(TAG, String.format(Locale.US, "mapStatusUpdate(%s) %s is %s.  localOwner:%s",
                droneSpec.trackLabel(), CaltopoMap.GetMapId(), mapStatus, localOwner));
        switch (mapStatusIn) {
            case credentialsVerified:
            case connecting: {
                shuttingDown = false;
                break;
            }
            case up: {
                folderId = CaltopoMap.GetFolderId();
                // Ownership may already be true when the map becomes (or stays) up.
                CaltopoRecoveryWork.offMainStart(this::ensureLiveTrackStarted);
                break;
            }
            case down: {
                break;
            }
        }
    }

    public boolean publishingLocally() {
        return (mapStatus == CaltopoMap.MapStatusListener.mapStatus.up && ownsPublication());
    }

    @NonNull
    public static String buildArchiveDescription(@Nullable CtDroneSpec droneSpec) {
        return "";
    }

    @NonNull
    public static String buildArchiveDescription(
            @Nullable CtDroneSpec droneSpec,
            @Nullable String capturedVideoUrl
    ) {
        if (droneSpec == null) return "";
        String videoUrl = capturedVideoUrl == null ? "" : capturedVideoUrl.trim();
        return videoUrl;
    }

    @Nullable
    private String capturedVideoUrl(@NonNull CtDroneSpec droneSpec) {
        return capturedVideoUrl(
                droneSpec.getMappedId(),
                droneSpec.getRemoteId(),
                segmentTrackLabel.split("_", 2)[0],
                trackObservedStartedAtMs,
                trackObservedEndedAtMs);
    }

    /** Catalog / MediaMetadataRetriever can hang on incomplete MediaMTX files; bound the wait. */
    @Nullable
    private static String capturedVideoUrl(
            @NonNull String mappedId,
            @NonNull String remoteId,
            @NonNull String labelPrefix,
            long trackStartedAtMs,
            long trackEndedAtMs) {
        if (R2CApplication.getAppCtxt() == null) return null;
        String[] candidates = new String[] { mappedId, remoteId, labelPrefix };
        try {
            java.util.concurrent.Future<ManagedVideoSessionRecording> lookup =
                    CatalogLookupExecutor.submit(() -> ManagedVideoSessionRecordingCatalog.findForTrack(
                            R2CApplication.getAppCtxt(),
                            trackStartedAtMs,
                            trackEndedAtMs,
                            candidates));
            ManagedVideoSessionRecording recording = lookup.get(5, TimeUnit.SECONDS);
            String sessionId = recording != null
                    ? recording.getSessionId()
                    : ManagedVideoStreamPresence.matchingLiveSessionId(candidates);
            if (sessionId == null) return null;
            return TrackerTabletLink.recordingShortUrl(
                    CaltopoClient.GetTrackerCoordinationUrlPfx(),
                    R2CActivity.MyDeviceName,
                    sessionId
            );
        } catch (Exception error) {
            CTWarn(TAG, "capturedVideoUrl timed out or failed; deferring video link", error);
            return null;
        }
    }

    private static void scheduleDeferredVideoDescriptionUpdate(
            @NonNull JSONObject archivedFeature,
            long trackStartedAtMs,
            long trackEndedAtMs,
            @NonNull String[] candidates,
            @NonNull java.util.function.BiFunction<JSONObject, java.util.function.Consumer<CaltopoOp>, CaltopoOp> updateArchivedTrack
    ) {
        RecordingLinkRetryWindow retryWindow = new RecordingLinkRetryWindow(
                DEFERRED_VIDEO_LINK_WINDOW_MS, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        JSONObject featureCopy;
        try {
            featureCopy = new JSONObject(archivedFeature.toString());
        } catch (JSONException error) {
            CTError(TAG, "Unable to copy archived feature for deferred video link." + " (" + error.getClass().getSimpleName() + ")");
            return;
        }
        DeferredVideoLinkExecutor.execute(() -> attemptDeferredVideoDescriptionUpdate(
                featureCopy,
                trackStartedAtMs,
                trackEndedAtMs,
                candidates,
                retryWindow,
                updateArchivedTrack
        ));
    }

    private static void attemptDeferredVideoDescriptionUpdate(
            @NonNull JSONObject archivedFeature,
            long trackStartedAtMs,
            long trackEndedAtMs,
            @NonNull String[] candidates,
            @NonNull RecordingLinkRetryWindow retryWindow,
            @NonNull java.util.function.BiFunction<JSONObject, java.util.function.Consumer<CaltopoOp>, CaltopoOp> updateArchivedTrack
    ) {
        if (R2CApplication.getAppCtxt() == null) return;
        ManagedVideoSessionRecording recording = ManagedVideoSessionRecordingCatalog.findForTrack(
                R2CApplication.getAppCtxt(),
                trackStartedAtMs,
                trackEndedAtMs,
                candidates
        );
        if (recording == null) {
            retryDeferredVideoDescriptionUpdate(
                    archivedFeature, trackStartedAtMs, trackEndedAtMs, candidates, retryWindow,
                    updateArchivedTrack);
            return;
        }
        String videoUrl = TrackerTabletLink.recordingShortUrl(
                CaltopoClient.GetTrackerCoordinationUrlPfx(),
                R2CActivity.MyDeviceName,
                recording.getSessionId()
        );
        if (videoUrl == null || videoUrl.trim().isEmpty()) return;
        String trackId = archivedFeature.optString("id", "");
        if (trackId.isEmpty()) return;
        try {
            JSONObject properties = archivedFeature.optJSONObject("properties");
            if (properties == null) {
                properties = new JSONObject();
                archivedFeature.put("properties", properties);
            }
            properties.put("description", videoUrl.trim());
            properties.put("updated", String.valueOf(System.currentTimeMillis()));
            properties.put("-updated-on", String.valueOf(System.currentTimeMillis()));
        } catch (JSONException error) {
            CTError(TAG, "Unable to add deferred video link to archived feature." + " (" + error.getClass().getSimpleName() + ")");
            return;
        }
        updateArchivedTrack.apply(
                archivedFeature,
                operation -> {
                    if (operation.success()) {
                        CTDebug(TAG, "Added deferred recording link to archived track " + trackId);
                        CaltopoMap.RequestMapRefreshNow();
                    } else {
                        retryDeferredVideoDescriptionUpdate(
                                archivedFeature, trackStartedAtMs, trackEndedAtMs, candidates, retryWindow,
                                updateArchivedTrack);
                    }
                }
        );
    }

    private static void retryDeferredVideoDescriptionUpdate(
            @NonNull JSONObject archivedFeature,
            long trackStartedAtMs,
            long trackEndedAtMs,
            @NonNull String[] candidates,
            @NonNull RecordingLinkRetryWindow retryWindow,
            @NonNull java.util.function.BiFunction<JSONObject, java.util.function.Consumer<CaltopoOp>, CaltopoOp> updateArchivedTrack
    ) {
        if (!retryWindow.shouldRetry(RecordingFinalizationActivity.shared.isActive(candidates))) {
            CTWarn(TAG, "Recording did not become available before the deferred CalTopo link deadline.");
            return;
        }
        DeferredVideoLinkExecutor.schedule(
                () -> attemptDeferredVideoDescriptionUpdate(
                        archivedFeature, trackStartedAtMs, trackEndedAtMs, candidates, retryWindow,
                        updateArchivedTrack),
                DEFERRED_VIDEO_LINK_RETRY_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Called by R2CMqttManager when ownership is granted or revoked.
     * Gaining ownership starts a new CalTopo livetrack segment for this drone.
     * Losing ownership stops publication immediately.
     */
    @Override
    public @NonNull String getRemoteId() {
        return myRemoteId;
    }

    @Override
    public int getQueuedPointCount() {
        return queuedPointCount.get();
    }

    public boolean ownsLiveTrackId(@Nullable String trackId) {
        return null != trackId && trackId.equals(liveTrackId);
    }

    @NonNull
    private String effectiveFleetDeviceId() {
        return CaltopoSession.liveTrackDeviceId(myRemoteId, CaltopoClient.GetConnectKey());
    }

    public boolean matchesFeatureDeviceId(@Nullable String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return false;
        String configuredDeviceId = CaltopoSession.liveTrackDeviceId(
                myRemoteId, CaltopoClient.GetConnectKey());
        String legacyDeviceId = CaltopoSession.liveTrackDeviceId(myRemoteId, "DRONE");
        return deviceId.equalsIgnoreCase(configuredDeviceId)
                || deviceId.equalsIgnoreCase(legacyDeviceId);
    }

    @Override
    public void setLocalOwner(boolean isOwner) {
        if (shuttingDown) return;
        OwnershipRequest request = new OwnershipRequest(isOwner);
        ownershipRequest.set(request);
        // ownershipRequest already gates ownsPublication(); no monitor needed for readers.
        CaltopoRecoveryWork.offMainStart(() -> setLocalOwnerOnWorker(request));
    }

    private synchronized void setLocalOwnerOnWorker(OwnershipRequest request) {
        if (shuttingDown || ownershipRequest.get() != request) return;
        boolean isOwner = request.owner;
        CTDebug(TAG, String.format(Locale.US,
                "setLocalOwner(%s): %s → %s", droneSpec.trackLabel(), localOwner, isOwner));
        localOwner = isOwner;
        if (isOwner && mapStatus == CaltopoMap.MapStatusListener.mapStatus.up) {
            backfillQueuedPointsFromWaypointTrackIfNeeded();
            ensureLiveTrackStarted();
        }
    }

    /* Return -1 if no corresponding point */
    public long getFirstTimestamp() {
        if (linePoints.isEmpty()) return -1;
        QueuedPoint point = linePoints.getFirst();
        return point.timestampMsec;
    }

    public static CaltopoLiveTrack GetLiveTrackForRemoteId(@NonNull String remoteId) {
        return LiveTrackByRemoteId.get(remoteId);
    }

    public static void AddLocalTrackListener(@NonNull LocalTrackListener listener) {
        LocalTrackListeners.addIfAbsent(listener);
    }

    public static void RemoveLocalTrackListener(@NonNull LocalTrackListener listener) {
        LocalTrackListeners.remove(listener);
    }

    public static void AddLocalTrackFinishedListener(@NonNull LocalTrackFinishedListener listener) {
        LocalTrackFinishedListeners.addIfAbsent(listener);
    }

    public static void RemoveLocalTrackFinishedListener(@NonNull LocalTrackFinishedListener listener) {
        LocalTrackFinishedListeners.remove(listener);
    }

    public static void NotifyLocalTrackPoint(@NonNull CtDroneSpec droneSpec,
                                             double lat, double lng, double altitudeMeters,
                                             long timestampMsec) {
        long startedAtMs = System.currentTimeMillis();
        if (LocalTrackListeners.isEmpty()) {
            CTDebug(ICON_LATENCY_TAG, String.format(Locale.US,
                    "track_notify_skipped remoteId=%s mappedId=%s reason=no_listeners droneTs=%d lat=%.6f lng=%.6f alt=%.1f",
                    droneSpec.getRemoteId(), droneSpec.getMappedId(), timestampMsec, lat, lng, altitudeMeters));
            return;
        }
        String remoteId = droneSpec.getRemoteId();
        String mappedId = droneSpec.getMappedId();
        for (LocalTrackListener listener : LocalTrackListeners) try {
            long listenerStartedAtMs = System.currentTimeMillis();
            listener.onLocalTrackPoint(remoteId, mappedId, lat, lng, altitudeMeters, timestampMsec);
            logSideEffectIfSlow("NotifyLocalTrackPoint.listener." + listener.getClass().getName(),
                    remoteId, mappedId, System.currentTimeMillis() - listenerStartedAtMs);
        } catch (Exception e) {
            CTError(TAG, "NotifyLocalTrackPoint() listener raised" + " (" + e.getClass().getSimpleName() + ")");
        }
        logSideEffectIfSlow("NotifyLocalTrackPoint.total", remoteId, mappedId,
                System.currentTimeMillis() - startedAtMs);
    }

    public static void NotifyLocalTrackFinished(@NonNull CtDroneSpec droneSpec, @NonNull String reason) {
        String remoteId = droneSpec.getRemoteId();
        String mappedId = droneSpec.getMappedId();
        for (LocalTrackFinishedListener listener : LocalTrackFinishedListeners) try {
            listener.onLocalTrackFinished(remoteId, mappedId, reason);
        } catch (Exception e) {
            CTError(TAG, "NotifyLocalTrackFinished() listener raised" + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    public void shutdown(long maxWaitInMilliseconds) {
        // Capture the departing map before ResetMapConnection clears its fields.
        final String destinationMap = CaltopoMap.GetMapId();
        shuttingDown = true;
        // Journal-only fallback used after DisconnectAfterFinishingOwned already tried
        // the live archive, or when teardown cannot wait on the network. Reconnect
        // recovery converts the LiveTrack exactly once.
        CaltopoRecoveryWork.offMainArchive(() -> shutdownOnWorker(destinationMap));
    }

    /**
     * Finish an owned LiveTrack for map disconnect using the normal archive path
     * (Shape to Drone Tracks + LiveTrack delete), with a bounded wait. On timeout or
     * unreachable CalTopo, keep the interrupted journal for reconnect recovery.
     * Does not end the physical flight. Must run off the main thread and must not
     * hold the track monitor across network I/O.
     */
    public void finishOwnedPublicationForDisconnect(
            long maxWaitMs, @Nullable String destinationMap, @Nullable String destinationFolder) {
        Looper main = Looper.getMainLooper();
        if (main != null && Looper.myLooper() == main) {
            throw new IllegalStateException("finishOwnedPublicationForDisconnect must run off Main");
        }
        final DisconnectFinishWork work;
        synchronized (this) {
            work = prepareDisconnectFinishLocked(destinationMap);
        }
        applyDisconnectFinishWork(work, maxWaitMs, destinationMap, destinationFolder);
    }

    /**
     * Lock-free status for Disconnect / map UI. Must never take the track monitor — finish,
     * archive, positionReport, and journal work hold that monitor and may run for seconds.
     */
    public boolean hasOwnedActivePublication() {
        // Owned queued geometry must finish as a Shape even when LiveTrack never received an id.
        return active && ownsPublication()
                && (liveTrackId != null || requestedLiveTrackId != null || queuedPointCount.get() > 0);
    }

    private static final class ShutdownWork {
        final JournalSnapshot snapshot;
        final String orphanDeleteId;
        final String orphanDeleteMap;
        final boolean notifyPeer;
        ShutdownWork(JournalSnapshot snapshot, String orphanDeleteId, String orphanDeleteMap,
                     boolean notifyPeer) {
            this.snapshot = snapshot;
            this.orphanDeleteId = orphanDeleteId;
            this.orphanDeleteMap = orphanDeleteMap;
            this.notifyPeer = notifyPeer;
        }
    }

    private static final class DisconnectFinishWork {
        final JournalSnapshot snapshot;
        final JSONObject feature;
        final String archiveId;
        final String orphanDeleteId;
        final String orphanDeleteMap;
        final boolean notifyPeer;
        @Nullable final String durableJournalId;
        DisconnectFinishWork(JournalSnapshot snapshot, JSONObject feature, String archiveId,
                             String orphanDeleteId, String orphanDeleteMap, boolean notifyPeer) {
            this(snapshot, feature, archiveId, orphanDeleteId, orphanDeleteMap, notifyPeer, null);
        }
        DisconnectFinishWork(JournalSnapshot snapshot, JSONObject feature, String archiveId,
                             String orphanDeleteId, String orphanDeleteMap, boolean notifyPeer,
                             @Nullable String durableJournalId) {
            this.snapshot = snapshot;
            this.feature = feature;
            this.archiveId = archiveId;
            this.orphanDeleteId = orphanDeleteId;
            this.orphanDeleteMap = orphanDeleteMap;
            this.notifyPeer = notifyPeer;
            this.durableJournalId = durableJournalId;
        }
    }

    private void shutdownOnWorker(String destinationMap) {
        final ShutdownWork work;
        synchronized (this) {
            work = prepareShutdownLocked(destinationMap);
        }
        // Journal I/O and orphan DELETE stay outside the track monitor.
        if (work.snapshot != null) {
            try {
                saveJournalSnapshot(work.snapshot);
            } catch (Exception e) {
                CTError(TAG, String.format(Locale.US, "shutdown(%s) journal persist failed:",
                        work.snapshot.label), e);
            }
        }
        if (work.orphanDeleteId != null) {
            try {
                runtime.getCalTopoSessionGateway().deleteLiveTrackOnMap(
                        work.orphanDeleteMap, work.orphanDeleteId,
                        deleteOp -> CaltopoRecoveryWork.offMainArchive(() -> {
                            if (deleteOp.success()) {
                                CaltopoInterruptedTrackJournal.remove(work.orphanDeleteId);
                            }
                        }));
            } catch (Exception e) {
                CTError(TAG, "shutdown(): deleteLiveTrackWithId() raised: "
                        + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        if (work.notifyPeer) notifyPeerCoordinatorTrackEnded(true);
    }

    private void applyDisconnectFinishWork(
            DisconnectFinishWork work, long maxWaitMs,
            @Nullable String destinationMap, @Nullable String destinationFolder) {
        if (work.snapshot != null) {
            try {
                saveJournalSnapshot(work.snapshot);
            } catch (Exception e) {
                CTError(TAG, String.format(Locale.US,
                        "disconnect-finish(%s) journal persist failed:", work.snapshot.label), e);
            }
        }
        if (work.orphanDeleteId != null) {
            try {
                runtime.getCalTopoSessionGateway().deleteLiveTrackOnMap(
                        work.orphanDeleteMap, work.orphanDeleteId,
                        deleteOp -> CaltopoRecoveryWork.offMainArchive(() -> {
                            if (deleteOp.success()) {
                                CaltopoInterruptedTrackJournal.remove(work.orphanDeleteId);
                            }
                        }));
            } catch (Exception e) {
                CTError(TAG, "disconnect-finish(): deleteLiveTrackWithId() raised: "
                        + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        if (work.feature != null && work.archiveId != null
                && destinationMap != null && !destinationMap.isEmpty()
                && destinationFolder != null && !destinationFolder.isEmpty()) {
            final String archivedLiveTrackId = work.archiveId;
            if (!CaltopoNetworkReachability.hasValidatedInternet()) {
                CTInfo(TAG, String.format(Locale.US,
                        "disconnect-finish(%s): deferred offline liveTrackId=%s",
                        work.snapshot != null ? work.snapshot.label : archivedLiveTrackId,
                        archivedLiveTrackId));
            } else {
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean archived = new AtomicBoolean(false);
            try {
                runtime.getCalTopoSessionGateway().cancelLiveTrackPoints(myRemoteId);
                CaltopoMap.ArchiveFeatureOnMap(
                        work.feature,
                        "LiveTrack",
                        System.currentTimeMillis(),
                        0L,
                        destinationMap, destinationFolder, runtime,
                        success -> {
                            archived.set(success);
                            done.countDown();
                        });
                long waitMs = Math.max(0L, maxWaitMs);
                if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
                    CTWarn(TAG, String.format(Locale.US,
                            "disconnect-finish(%s): archive timed out after %dms; journal retained",
                            work.snapshot != null ? work.snapshot.label : archivedLiveTrackId, waitMs));
                } else if (archived.get()) {
                    // Clear the durable checkpoint only after the archive finished, on
                    // this worker — not from a gateway callback that may hop to Main.
                    CaltopoInterruptedTrackJournal.remove(archivedLiveTrackId);
                    AwaitingMapFlights.published(archivedLiveTrackId);
                    if (work.durableJournalId != null) {
                        WaypointTrackJournal.markFinishedAndRemove(work.durableJournalId);
                    }
                } else {
                    CTWarn(TAG, String.format(Locale.US,
                            "disconnect-finish(%s): archive failed; journal retained",
                            work.snapshot != null ? work.snapshot.label : archivedLiveTrackId));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CTWarn(TAG, "disconnect-finish(): interrupted; journal retained");
            } catch (Exception e) {
                CTError(TAG, "disconnect-finish(): archive raised", e);
            }
            }
        }
        if (work.notifyPeer) notifyPeerCoordinatorTrackEnded(true);
    }

    private DisconnectFinishWork prepareDisconnectFinishLocked(@Nullable String destinationMap) {
        shuttingDown = true;
        boolean wasActive = active;
        // Owned queued points must still publish a finished Shape when LiveTrack never started.
        if (active && ownsPublication() && liveTrackId == null && requestedLiveTrackId == null
                && !linePoints.isEmpty()
                && destinationMap != null && !destinationMap.isEmpty()) {
            String deferredId = AwaitingMapFlights.publicationId(myRemoteId, destinationMap);
            if (deferredId != null && deferredId.isEmpty()) {
                String reserved = AwaitingMapFlights.reservedPublicationId(myRemoteId);
                if (reserved != null) deferredId = reserved;
                else deferredId = AwaitingMapFlights.bindConnectedMapIfOpenReview(myRemoteId, destinationMap);
            }
            if (deferredId == null || !deferredId.isEmpty()) {
                requestedLiveTrackId = deferredId != null ? deferredId : java.util.UUID.randomUUID().toString();
                publicationMapId = destinationMap;
            }
        }
        String archiveId = liveTrackId != null ? liveTrackId : requestedLiveTrackId;
        String journalId = durableJournalFlightId;
        if (active && archiveId != null) {
            CTDebug(TAG, String.format(Locale.US, "disconnect-finish. Terminating '%s'",
                    droneSpec.trackLabel()));
            if (liveTrackId != null) CaltopoMap.RemoveLiveTrack(liveTrackId);
            if (!ownsPublication()) {
                String map = publicationMapId != null ? publicationMapId : destinationMap;
                localOwner = false;
                clearLiveTrackState();
                return new DisconnectFinishWork(null, null, null, archiveId, map, wasActive, journalId);
            }
            JournalSnapshot snapshot = journalSnapshot("");
            JSONObject feature = buildArchiveFeatureLocked(archiveId);
            localOwner = false;
            clearLiveTrackState();
            return new DisconnectFinishWork(snapshot, feature, archiveId, null, null, wasActive, journalId);
        }
        localOwner = false;
        clearLiveTrackState();
        return new DisconnectFinishWork(null, null, null, null, null, wasActive, journalId);
    }

    @Nullable
    private JSONObject buildArchiveFeatureLocked(@NonNull String archiveId) {
        int size = linePoints.size();
        if (size == 0) return null;
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < size; i++) {
            QueuedPoint point = linePoints.get(i);
            JSONArray pointArray = new JSONArray();
            pointArray.put(String.format(Locale.US, "%.7f", point.lng));
            pointArray.put(String.format(Locale.US, "%.7f", point.lat));
            pointArray.put(String.format(Locale.US, "%f", point.ele));
            jsonArray.put(pointArray);
        }
        JSONObject feature = new JSONObject();
        JSONObject geometry = new JSONObject();
        try {
            feature.put("id", archiveId);
            feature.put("type", "Feature");
            geometry.put("coordinates", jsonArray);
            geometry.put("type", "LineString");
            feature.put("geometry", geometry);
            JSONObject properties = new JSONObject();
            properties.put("title", segmentTrackLabel);
            properties.put("class", "LiveTrack");
            feature.put("properties", properties);
        } catch (JSONException e) {
            CTError(TAG, "disconnect-finish feature build raised", e);
            return null;
        }
        return feature;
    }

    private ShutdownWork prepareShutdownLocked(String destinationMap) {
        boolean wasActive = active;
        String archiveId = liveTrackId != null ? liveTrackId : requestedLiveTrackId;
        if (active && archiveId != null) {
            CTDebug(TAG, String.format(Locale.US, "shutdown(0). Terminating '%s'",
                    droneSpec.trackLabel()));
            if (liveTrackId != null) CaltopoMap.RemoveLiveTrack(liveTrackId);
            if (!ownsPublication()) {
                String map = publicationMapId != null ? publicationMapId : destinationMap;
                localOwner = false;
                clearLiveTrackState();
                return new ShutdownWork(null, archiveId, map, wasActive);
            }
            // Owner: durable checkpoint only. Skip capturedVideoUrl / ArchiveFeatureOnMap
            // here so catalog SAF work and syncOp cannot pin the recovery worker.
            JournalSnapshot snapshot = journalSnapshot("");
            localOwner = false;
            clearLiveTrackState();
            return new ShutdownWork(snapshot, null, null, wasActive);
        }
        localOwner = false;
        clearLiveTrackState();
        return new ShutdownWork(null, null, null, wasActive);
    }

    /** CaltopoMap periodically checks for updates to map features
     *  and forwards the current label for our track to us so we can see
     *  if the user has requested a different label be used for this drone.
     *
     * @param caltopoTrackLabel Usually of the form <label>_datetimestamp,
     *                          so look at everything before the '_'.
     */
    public void checkCaltopoTrackLabel(@NonNull String caltopoTrackLabel) {
        String trackLabel = segmentTrackLabel;
        if (trackLabel.equals(caltopoTrackLabel)) return; // no change.
        String newLabel = caltopoTrackLabel;
        int indexOfChar = caltopoTrackLabel.indexOf('_');
        if (indexOfChar < 0) indexOfChar = caltopoTrackLabel.indexOf('-');
        if (indexOfChar > 0) {
            newLabel = caltopoTrackLabel.substring(0, indexOfChar);
        }
        if (newLabel.isEmpty()) {
            CaltopoClient.CTWarn(TAG, String.format(Locale.US,
                    "checkCaltopoTrackLabel(): empty title observed for remoteId=%s liveTrackId=%s localOwner=%s mappedId='%s' trackLabel='%s' rawTitle='%s'",
                    myRemoteId, liveTrackId, localOwner, droneSpec.getMappedId(), trackLabel, caltopoTrackLabel));
        }
        String dsMappedId = droneSpec.setMappedId(newLabel);
        CTDebug(TAG, String.format(Locale.US,
                        "checkCaltopoTrackLabel(): Changing track name from '%s' to '%s', ds returned: '%s'",
                trackLabel, newLabel, dsMappedId));
    }

    /**  Archive this track segment on Caltopo if we're the owner.
     */
    public void archiveTrackOnCaltopo(long maxWaitInMilliseconds) {
        final String destinationMap = CaltopoMap.GetMapId();
        final String destinationFolder = CaltopoMap.GetArchiveFolderId();
        CaltopoRecoveryWork.offMainArchive(() -> archiveTrackOnWorker(maxWaitInMilliseconds, destinationMap, destinationFolder));
    }

    private void archiveTrackOnWorker(long maxWaitInMilliseconds, String destinationMap, String destinationFolder) {
        final ArchiveWork work;
        synchronized (this) {
            work = prepareArchiveLocked(destinationMap);
        }
        applyArchiveWork(work, maxWaitInMilliseconds, destinationMap, destinationFolder);
    }

    private static final class ArchiveWork {
        final boolean skip;
        final String trackLabel;
        final String orphanDeleteId;
        final String orphanDeleteMap;
        final JournalSnapshot journalSnapshot;
        final JSONObject feature;
        final String archiveId;
        final int pointCount;
        final long trackStartedAtMs;
        final long trackEndedAtMs;
        final String[] trackCandidates;
        final boolean resetPhysicalSegment;
        final String physicalSegmentLabel;
        @Nullable final String durableJournalId;
        ArchiveWork(boolean skip, String trackLabel, String orphanDeleteId, String orphanDeleteMap,
                    JournalSnapshot journalSnapshot, JSONObject feature, String archiveId, int pointCount,
                    long trackStartedAtMs, long trackEndedAtMs, String[] trackCandidates,
                    boolean resetPhysicalSegment, String physicalSegmentLabel) {
            this(skip, trackLabel, orphanDeleteId, orphanDeleteMap, journalSnapshot, feature, archiveId,
                    pointCount, trackStartedAtMs, trackEndedAtMs, trackCandidates,
                    resetPhysicalSegment, physicalSegmentLabel, null);
        }
        ArchiveWork(boolean skip, String trackLabel, String orphanDeleteId, String orphanDeleteMap,
                    JournalSnapshot journalSnapshot, JSONObject feature, String archiveId, int pointCount,
                    long trackStartedAtMs, long trackEndedAtMs, String[] trackCandidates,
                    boolean resetPhysicalSegment, String physicalSegmentLabel,
                    @Nullable String durableJournalId) {
            this.skip = skip;
            this.trackLabel = trackLabel;
            this.orphanDeleteId = orphanDeleteId;
            this.orphanDeleteMap = orphanDeleteMap;
            this.journalSnapshot = journalSnapshot;
            this.feature = feature;
            this.archiveId = archiveId;
            this.pointCount = pointCount;
            this.trackStartedAtMs = trackStartedAtMs;
            this.trackEndedAtMs = trackEndedAtMs;
            this.trackCandidates = trackCandidates;
            this.resetPhysicalSegment = resetPhysicalSegment;
            this.physicalSegmentLabel = physicalSegmentLabel;
            this.durableJournalId = durableJournalId;
        }
    }

    /** Snapshot + clear publication state under the monitor; network/disk stay outside. */
    @Nullable
    private ArchiveWork prepareArchiveLocked(@Nullable String destinationMap) {
        String trackLabel = segmentTrackLabel;
        if (!ownsPublication()) {
            if (liveTrackId != null || requestedLiveTrackId != null) {
                String orphanedLiveTrackId = liveTrackId != null ? liveTrackId : requestedLiveTrackId;
                String map = publicationMapId != null ? publicationMapId : destinationMap;
                CTWarn(TAG, String.format(Locale.US,
                        "archiveTrackOnCaltopo(): no longer owner; deleting orphaned live track '%s' for remoteId=%s",
                        liveTrackId, myRemoteId));
                boolean resetPhysical = prepareResetPhysicalSegmentLocked();
                String physicalLabel = segmentTrackLabel;
                clearLiveTrackStateLocked();
                return new ArchiveWork(false, trackLabel, orphanedLiveTrackId, map,
                        null, null, null, 0, 0L, 0L, null, resetPhysical, physicalLabel);
            }
            CTDebug(TAG, "archiveTrackOnCaltopo(): not the owner — skipping.");
            boolean resetPhysical = prepareResetPhysicalSegmentLocked();
            String physicalLabel = segmentTrackLabel;
            clearLiveTrackStateLocked();
            return new ArchiveWork(true, trackLabel, null, null, null, null, null, 0,
                    0L, 0L, null, resetPhysical, physicalLabel);
        }
        int size = linePoints.size();
        String archiveId = liveTrackId != null ? liveTrackId : requestedLiveTrackId;
        if (0 == size || archiveId == null) {
            CTDebug(TAG, String.format(Locale.US,
                    "archiveTrackOnCaltopo(%s): w/no waypoints ignored.", trackLabel));
            boolean resetPhysical = prepareResetPhysicalSegmentLocked();
            String physicalLabel = segmentTrackLabel;
            clearLiveTrackStateLocked();
            return new ArchiveWork(true, trackLabel, null, null, null, null, null, 0,
                    0L, 0L, null, resetPhysical, physicalLabel);
        }
        if (publicationMapId != null && destinationMap != null && !publicationMapId.equals(destinationMap)) {
            boolean resetPhysical = prepareResetPhysicalSegmentLocked();
            String physicalLabel = segmentTrackLabel;
            clearLiveTrackStateLocked();
            return new ArchiveWork(true, trackLabel, null, null, null, null, null, 0,
                    0L, 0L, null, resetPhysical, physicalLabel);
        }
        JSONObject feature = buildArchiveFeatureLocked(archiveId);
        JournalSnapshot snapshot = journalSnapshot("");
        long startedAtMs = trackObservedStartedAtMs;
        long endedAtMs = trackObservedEndedAtMs;
        String journalId = durableJournalFlightId;
        String[] candidates = new String[] {
                droneSpec.getMappedId(),
                droneSpec.getRemoteId(),
                segmentTrackLabel.split("_", 2)[0]
        };
        CTDebug(TAG, String.format(Locale.US, "archiveTrackOnCaltopo(%s): Archiving track with %d points.",
                trackLabel, size));
        boolean resetPhysical = prepareResetPhysicalSegmentLocked();
        String physicalLabel = segmentTrackLabel;
        localOwner = false;
        clearLiveTrackStateLocked();
        return new ArchiveWork(false, trackLabel, null, null, snapshot, feature, archiveId, size,
                startedAtMs, endedAtMs, candidates, resetPhysical, physicalLabel, journalId);
    }

    private boolean prepareResetPhysicalSegmentLocked() {
        boolean isActive = droneSpec.isActive();
        CTDebug(TAG, String.format(Locale.US, "resetLiveTrack(%s): resetting %sactive track",
                droneSpec.trackLabel(), isActive ? "" : "in"));
        // Map publication shutdown is not physical flight termination. Keep the
        // confirmed pilot, local geometry and per-flight consent until RID/video ends.
        return isActive && !shuttingDown && segmentTrackLabel.equals(droneSpec.trackLabel());
    }

    private void applyArchiveWork(
            @Nullable ArchiveWork work, long maxWaitInMilliseconds,
            @Nullable String destinationMap, @Nullable String destinationFolder) {
        if (work == null) return;
        // cancel / catalog / journal / Shape POST must not hold the track monitor.
        runtime.getCalTopoSessionGateway().cancelLiveTrackPoints(myRemoteId);
        if (work.resetPhysicalSegment) {
            WaypointTrack.ArchiveTrack(work.physicalSegmentLabel);
            droneSpec.reset();
        }
        if (work.orphanDeleteId != null) {
            try {
                runtime.getCalTopoSessionGateway().deleteLiveTrackOnMap(
                        work.orphanDeleteMap, work.orphanDeleteId,
                        deleteOp -> CaltopoRecoveryWork.offMainArchive(() -> {
                            if (deleteOp.success()) {
                                CaltopoInterruptedTrackJournal.remove(work.orphanDeleteId);
                            }
                        }));
            } catch (Exception e) {
                CTError(TAG, "archiveTrackOnCaltopo(): deleteLiveTrackWithId() raised: "
                        + " (" + e.getClass().getSimpleName() + ")");
            }
            return;
        }
        if (work.skip || work.feature == null || work.archiveId == null
                || destinationMap == null || destinationMap.isEmpty()
                || destinationFolder == null || destinationFolder.isEmpty()) {
            return;
        }
        // Never scan the recording catalog / MediaMetadataRetriever / OperationalStorage
        // prepare on the archive lane — that stalled Mn4Pr finish for minutes and blocked the
        // next flight's LiveTrack start when start shared the same single worker. Video links
        // attach asynchronously after the Shape POST.
        String archiveDescription = "";
        JSONObject feature = work.feature;
        if (work.journalSnapshot != null) {
            try {
                saveJournalSnapshot(work.journalSnapshot);
            } catch (Exception e) {
                CTError(TAG, String.format(Locale.US, "archiveTrackOnCaltopo(%s) journal persist failed:",
                        work.trackLabel), e);
            }
        }
        String archivedLiveTrackId = work.archiveId;
        // Lost/unvalidated Internet: durable journal only. Do not start a Shape POST that
        // can hang on a dead socket and hide the entry from connectivity-restore flush.
        if (!CaltopoNetworkReachability.hasValidatedInternet()) {
            CTInfo(TAG, String.format(Locale.US,
                    "archiveTrackOnCaltopo(%s): deferred offline points=%d liveTrackId=%s",
                    work.trackLabel, work.pointCount, archivedLiveTrackId));
            return;
        }
        final java.util.function.BiFunction<JSONObject, java.util.function.Consumer<CaltopoOp>, CaltopoOp> updateArchivedTrack =
                runtime.getCalTopoSessionGateway().prepareArchivedTrackUpdate(destinationMap, archivedLiveTrackId);
        final long archivedTrackStartedAtMs = work.trackStartedAtMs;
        final long archivedTrackEndedAtMs = work.trackEndedAtMs;
        final String[] archivedTrackCandidates = work.trackCandidates;
        // Shape POST stays asynchronous on the session pool. Bound only when the caller
        // requests a wait (disconnect); finishTrack must return so NotifyLocalTrackFinished
        // and the next archive job are not stuck behind a hung CalTopo edit.
        long archiveWaitMs = maxWaitInMilliseconds > 0
                ? Math.min(maxWaitInMilliseconds, ARCHIVE_NETWORK_TIMEOUT_MS)
                : 0L;
        CountDownLatch archiveDone = archiveWaitMs > 0 ? new CountDownLatch(1) : null;
        CaltopoMap.ArchiveFeatureOnMap(
                feature,
                "LiveTrack",
                System.currentTimeMillis(),
                0L,
                destinationMap, destinationFolder, runtime,
                success -> CaltopoRecoveryWork.offMainArchive(() -> {
                    if (archiveDone != null) archiveDone.countDown();
                    if (!success) return;
                    CaltopoInterruptedTrackJournal.remove(archivedLiveTrackId);
                    AwaitingMapFlights.published(archivedLiveTrackId);
                    if (work.durableJournalId != null) {
                        WaypointTrackJournal.markFinishedAndRemove(work.durableJournalId);
                    }
                    if (archiveDescription.isEmpty()) {
                        scheduleDeferredVideoDescriptionUpdate(
                                feature,
                                archivedTrackStartedAtMs,
                                archivedTrackEndedAtMs,
                                archivedTrackCandidates,
                                updateArchivedTrack
                        );
                    }
                }));
        if (archiveDone != null) {
            try {
                if (!archiveDone.await(archiveWaitMs, TimeUnit.MILLISECONDS)) {
                    CTWarn(TAG, String.format(Locale.US,
                            "archiveTrackOnCaltopo(%s): Shape POST timed out after %dms; interrupted journal retained",
                            work.trackLabel, archiveWaitMs));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CTWarn(TAG, String.format(Locale.US,
                        "archiveTrackOnCaltopo(%s): interrupted waiting for Shape POST", work.trackLabel));
            }
        }
    }

    private synchronized void resetLiveTrack() {
        boolean resetPhysical = prepareResetPhysicalSegmentLocked();
        String physicalLabel = segmentTrackLabel;
        clearLiveTrackStateLocked();
        if (resetPhysical) {
            WaypointTrack.ArchiveTrack(physicalLabel);
            droneSpec.reset();
        }
    }

    private synchronized void clearLiveTrackState() {
        clearLiveTrackStateLocked();
    }

    private void clearLiveTrackStateLocked() {
        publicationGeneration++;
        requestedLiveTrackId = null;
        publicationMapId = null;
        startInFlight = false;
        nextStartAttemptAtMs = 0L;
        linePoints.clear();
        queuedPointCount.set(0);
        liveTrackId = null;
        durableJournalFlightId = null;
        linePointsSentCount = linePointsConfirmedCount = consecutiveUpdateFails = 0;
        startLiveTrackOp = null;
        active = false;
        lastInterruptedJournalWriteMs = 0L;
        lastSeiPositionTimestampMs = 0L;
        thumbnailMetadataRefreshOp = null;
        lastThumbnailMetadataRevision = "";
    }

    public String getTrackLabel() {
        return segmentTrackLabel + (isActive()? "":"(inactive)");
    }

    public CtDroneSpec getDroneSpec() {
        return droneSpec;
    }

    public void renameTrackCompleted(CaltopoOp renameTrackOp) {
        if (renameTrackOp.fail()) {
            CTError(TAG, "renameTrackCompleted(): Failed to rename LiveTrack: " + renameTrackOp.responseString());
        } else {
            CTDebug(TAG, "renameTrackCompleted(): succeeded: " + renameTrackOp.responseString());
        }
    }

    public void renameTrack() {
        final String label = droneSpec.trackLabel();
        CaltopoRecoveryWork.offMainStart(() -> renameTrackOnWorker(label));
    }

    private void renameTrackOnWorker(String label) {
        final JSONObject feature;
        final String renameLiveTrackId;
        synchronized (this) {
            if (label.isEmpty() || !active) return;
            segmentTrackLabel = label;
            // Just edit the current live track - replacing the title.
            // N.B. must continue to use the original track name when publishing tracks...
            if (null == startLiveTrackOp) {
                CTError(TAG, "renameTrack(): received on inactive track.");
                return;
            }
            if (!startLiveTrackOp.isDone()) return;
            renameLiveTrackId = liveTrackId;
            if (renameLiveTrackId == null) return;
            try {
                long timeNowInMilliseconds = System.currentTimeMillis();
                String timeString = String.valueOf(timeNowInMilliseconds);
                feature = startLiveTrackOp.responseJson;
                JSONObject prop = feature.optJSONObject("properties");
                if (null == prop) {
                    prop = new JSONObject();
                    feature.put("properties", prop);
                }
                prop.put("title", segmentTrackLabel);
                prop.put("updated", timeString);
                prop.put("-updated-on", timeString);
            } catch (Exception e) {
                CTError(TAG, "renameTrack() raised." + " (" + e.getClass().getSimpleName() + ")");
                return;
            }
        }
        try {
            runtime.getCalTopoSessionGateway()
                    .editObjectWithId("LiveTrack", renameLiveTrackId, feature, this::renameTrackCompleted);
        } catch (Exception e) {
            CTError(TAG, "renameTrack() raised." + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Start the CalTopo LiveTrack exactly once whenever local ownership, map-up, and
     * a missing liveTrackId all hold. Safe to call from owner grants, map-up, waypoint
     * queueing, and backfill; duplicate calls are no-ops while a start is in flight.
     * Journal I/O and the create POST run outside the track monitor.
     */
    private void ensureLiveTrackStarted() {
        final LiveTrackStartRequest request;
        synchronized (this) {
            request = beginLiveTrackStartLocked();
        }
        if (request != null) {
            // Always queue: callers may still hold the track monitor (queueWaypoint) or be
            // on the recovery worker where offMain would run inline under that monitor.
            CaltopoRecoveryWork.executeStart(() -> startLiveTrackAfterPersist(request));
        }
    }

    private void startNewTrack() {
        ensureLiveTrackStarted();
    }

    private static final class LiveTrackStartRequest {
        final String requestedId;
        final long generation;
        final String trackLabel;
        final String folderId;
        final int queuedPoints;
        LiveTrackStartRequest(String requestedId, long generation, String trackLabel,
                              String folderId, int queuedPoints) {
            this.requestedId = requestedId;
            this.generation = generation;
            this.trackLabel = trackLabel;
            this.folderId = folderId;
            this.queuedPoints = queuedPoints;
        }
    }

    /** @return start request when this call claimed the in-flight slot; null when nothing to do. */
    @Nullable
    private LiveTrackStartRequest beginLiveTrackStartLocked() {
        if (shuttingDown || !active) return null;
        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) return null;
        if (!ownsPublication()) return null;
        if (liveTrackId != null || startInFlight) return null;
        if (!CaltopoNetworkReachability.hasValidatedInternet()) {
            CTDebug(TAG, String.format(Locale.US,
                    "ensureLiveTrackStarted(%s): deferred offline", droneSpec.trackLabel()));
            return null;
        }
        if (System.currentTimeMillis() < nextStartAttemptAtMs) return null;

        String mapId = CaltopoMap.GetMapId();
        if (mapId == null || mapId.isEmpty()) return null;
        String deferredId = AwaitingMapFlights.publicationId(myRemoteId, mapId);
        if (deferredId != null && deferredId.isEmpty()) {
            // Intent write still pending, or an empty-map review was captured before connect.
            String reserved = AwaitingMapFlights.reservedPublicationId(myRemoteId);
            if (reserved != null) {
                deferredId = reserved;
            } else {
                deferredId = AwaitingMapFlights.bindConnectedMapIfOpenReview(myRemoteId, mapId);
                if (deferredId == null) {
                    // Still blocked (decided for another map, or unfinished writer).
                    CTDebug(TAG, String.format(Locale.US,
                            "ensureLiveTrackStarted(%s): deferred publication still blocking map=%s",
                            droneSpec.trackLabel(), mapId));
                    return null;
                }
            }
        }
        if (requestedLiveTrackId == null) {
            requestedLiveTrackId = deferredId != null ? deferredId : java.util.UUID.randomUUID().toString();
            publicationMapId = mapId;
        }
        String startFolderId = folderId != null ? folderId : CaltopoMap.GetFolderId();
        folderId = startFolderId;
        startInFlight = true;
        active = true;
        return new LiveTrackStartRequest(requestedLiveTrackId, publicationGeneration,
                segmentTrackLabel, startFolderId, linePoints.size());
    }

    private void startLiveTrackAfterPersist(@NonNull LiveTrackStartRequest request) {
        // Journal persistence must not gate the LiveTrack POST. Publication and archive
        // hydration are independent lanes; a transient journal miss is retried later.
        if (!persistInterruptedPublication("", true)) {
            CTWarn(TAG, String.format(Locale.US,
                    "startNewTrack(%s): interrupted journal not durable yet; starting LiveTrack anyway",
                    request.trackLabel));
        }
        final String requestedId;
        final long generation;
        final String trackLabel;
        final String folderId;
        synchronized (this) {
            if (request.generation != publicationGeneration
                    || !request.requestedId.equals(requestedLiveTrackId)) return;
            if (!ownsPublication() || shuttingDown || !active) {
                startInFlight = false;
                return;
            }
            requestedId = request.requestedId;
            generation = request.generation;
            trackLabel = request.trackLabel;
            folderId = request.folderId;
            CTDebug(TAG, String.format(Locale.US,
                    "startNewTrack(%s): Starting LiveTrack w/label:%s mappedId='%s' localOwner=%s queuedPoints=%d in folder:%s",
                    effectiveFleetDeviceId(), trackLabel, droneSpec.getMappedId(), localOwner,
                    request.queuedPoints, folderId));
        }
        try {
            CaltopoOp op = runtime.getCalTopoSessionGateway()
                    .startLiveTrack(requestedId, myRemoteId, trackLabel, folderId,
                            null, null, completed -> CaltopoRecoveryWork.offMainStart(() -> {
                                synchronized (CaltopoLiveTrack.this) {
                                    if (generation != publicationGeneration || !requestedId.equals(requestedLiveTrackId)) return;
                                    startLiveTrackComplete(completed);
                                }
                            }));
            synchronized (this) {
                if (generation == publicationGeneration && requestedId.equals(requestedLiveTrackId)) {
                    startLiveTrackOp = op;
                }
            }
        } catch (Exception e) {
            synchronized (this) {
                if (generation == publicationGeneration) {
                    startInFlight = false;
                    nextStartAttemptAtMs = System.currentTimeMillis() + 20_000L;
                }
            }
            CTError(TAG, "startNewTrack(): startLiveTrack() raised: " + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void backfillQueuedPointsFromWaypointTrackIfNeeded() {
        if (liveTrackId != null || startLiveTrackOp != null || linePointsSentCount != 0) return;

        List<WaypointTrack.TrackPoint> snapshot = WaypointTrack.GetTrackPointsSnapshot(droneSpec);
        if (snapshot.size() <= linePoints.size()) return;

        linePoints.clear();
        for (WaypointTrack.TrackPoint point : snapshot) {
            linePoints.add(new QueuedPoint(
                    point.lat,
                    point.lng,
                    point.ele,
                    point.timestampMsec,
                    null,
                    null));
        }
        queuedPointCount.set(linePoints.size());
        CTDebug(TAG, String.format(Locale.US,
                "backfillQueuedPointsFromWaypointTrackIfNeeded(%s): queued %d pre-connect point(s).",
                droneSpec.trackLabel(), linePoints.size()));
    }

    public void finishTrack(@NonNull String reason) {
        final String destinationMap = CaltopoMap.GetMapId();
        final String destinationFolder = CaltopoMap.GetArchiveFolderId();
        CaltopoRecoveryWork.offMainArchive(() -> finishTrackOnWorker(reason, destinationMap, destinationFolder));
    }

    private void finishTrackOnWorker(@NonNull String reason, String destinationMap, String destinationFolder) {
        final boolean notifyFinishedOnly;
        final boolean wasActive;
        final String removeLiveTrackId;
        final ArchiveWork archiveWork;
        synchronized (this) {
            if (!active) {
                // Map disconnect already persisted the interrupted journal and cleared
                // publication state. Reconnect recovery converts the LiveTrack once.
                notifyFinishedOnly = droneSpec.isActive();
                wasActive = false;
                removeLiveTrackId = null;
                archiveWork = null;
            } else {
                notifyFinishedOnly = false;
                wasActive = true;
                CTDebug(TAG, String.format(Locale.US, "finishTrack(%s): %s", getTrackLabel(), reason));
                // Queued points from a flight that never received a LiveTrack id must still publish
                // through the archive when the map is up, not only land in AwaitingMapFlights.
                if (liveTrackId == null && requestedLiveTrackId == null && !linePoints.isEmpty()
                        && ownsPublication()
                        && destinationMap != null && !destinationMap.isEmpty()) {
                    String deferredId = AwaitingMapFlights.publicationId(myRemoteId, destinationMap);
                    if (deferredId != null && deferredId.isEmpty()) {
                        String reserved = AwaitingMapFlights.reservedPublicationId(myRemoteId);
                        if (reserved != null) deferredId = reserved;
                        else deferredId = AwaitingMapFlights.bindConnectedMapIfOpenReview(myRemoteId, destinationMap);
                    }
                    if (deferredId == null || !deferredId.isEmpty()) {
                        requestedLiveTrackId = deferredId != null ? deferredId : java.util.UUID.randomUUID().toString();
                        publicationMapId = destinationMap;
                    }
                }
                removeLiveTrackId = liveTrackId;
                if (liveTrackId != null || requestedLiveTrackId != null) {
                    archiveWork = prepareArchiveLocked(destinationMap);
                } else {
                    archiveWork = null;
                    localOwner = false;
                    clearLiveTrackState();
                }
            }
        }
        if (notifyFinishedOnly) {
            NotifyLocalTrackFinished(droneSpec, reason);
            return;
        }
        if (!wasActive) return;
        if (removeLiveTrackId != null) {
            CaltopoMap.RemoveLiveTrack(removeLiveTrackId);
        }
        if (archiveWork != null) {
            try {
                applyArchiveWork(archiveWork, 0L, destinationMap, destinationFolder);
            } catch (Exception e) {
                CTError(TAG, String.format(Locale.US, "finishTrack(%s) '%s' Caltopo cleanup failed:",
                        droneSpec.trackLabel(), reason), e);
            }
        }
        notifyPeerCoordinatorTrackEnded(true);
        NotifyLocalTrackFinished(droneSpec, reason);
    }

    private void notifyPeerCoordinatorTrackEnded(boolean wasActive) {
        if (!wasActive || droneSpec.isLocalArchiveOnly()) return;
        runtime.getPeerCoordinator().onDroneLost(myRemoteId);
    }

    public boolean isActive() {return active; }

    public static boolean HasActiveLocalTrackForMappedId(@Nullable String mappedId) {
        String normalized = mappedId == null ? "" : mappedId.trim();
        if (normalized.isEmpty()) return false;
        synchronized (LiveTrackByRemoteId) {
            for (CaltopoLiveTrack liveTrack : LiveTrackByRemoteId.values()) {
                if (liveTrack.active
                        && liveTrack.ownsPublication()
                        && normalized.equalsIgnoreCase(liveTrack.droneSpec.getMappedId().trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Publish a newly captured video thumbnail without waiting for another RID waypoint. */
    public static void RefreshActiveVideoCameraMetadata() {
        for (CaltopoLiveTrack liveTrack : new LinkedList<>(LiveTrackByRemoteId.values())) {
            try {
                liveTrack.refreshActiveVideoCameraMetadata();
            } catch (Exception error) {
                CTError(TAG, "Unable to refresh CalTopo video thumbnail metadata" + " (" + error.getClass().getSimpleName() + ")");
            }
        }
    }

    /** Re-register all active live tracks with R2CMqttManager after a map reconnect. */
    public static void ReevalUnknownAndPendingTracks() {
        for (CaltopoLiveTrack liveTrack : LiveTrackByRemoteId.values()) {
            if (!liveTrack.ownsPublication()) {
                double dist = CaltopoMap.DistanceFromMeInMeters(
                        liveTrack.droneSpec.lastLat, liveTrack.droneSpec.lastLng);
                long fts = liveTrack.getFirstTimestamp();
                R2cRuntimeRegistry.getDefaultRuntime().getPeerCoordinator()
                        .onLiveTrackCreated(liveTrack, liveTrack.droneSpec, dist, fts);
            }
        }
    }

    /**
     * Internet restored: retry LiveTrack start / point flush for owned active tracks.
     * Must be invoked off Main; does not run archive or interrupted-journal recovery.
     */
    public static void wakePublicationAfterInternetRestored() {
        CaltopoRecoveryWork.requireOffMain();
        for (CaltopoLiveTrack liveTrack : new LinkedList<>(LiveTrackByRemoteId.values())) {
            try {
                CaltopoRecoveryWork.offMainStart(liveTrack::ensureLiveTrackStarted);
                CaltopoRecoveryWork.offMainStart(() -> liveTrack.forwardNextWaypoints(null));
            } catch (Exception error) {
                CTWarn(TAG, "wakePublicationAfterInternetRestored raised for "
                        + liveTrack.myRemoteId, error);
            }
        }
    }

    /** Owned active LiveTracks that still need start/point flush after reconnect. */
    public static int countOwnedActivePublications() {
        int count = 0;
        for (CaltopoLiveTrack liveTrack : LiveTrackByRemoteId.values()) {
            if (liveTrack.hasOwnedActivePublication()) count++;
        }
        return count;
    }

    private synchronized void startLiveTrackComplete(CaltopoOp op) {
        startInFlight = false;
        startLiveTrackOp = op;
        String trackLabel = droneSpec.trackLabel();
        if (op.fail()) {
            CTError(TAG, String.format(Locale.US,
                    "Not able to open LiveTrack for:'%s' - responseCode:%d, response: %s",
                    effectiveFleetDeviceId(), op.responseCode, op.responseString()));
            // The server may have created it. Preserve the ID and buffered points.
            nextStartAttemptAtMs = System.currentTimeMillis() + 20_000L;
            return;
        } else try {
            CTDebug(TAG, "startLiveTrackComplete(): succeeded. " + op);
            String returnedId = op.id();
            if (requestedLiveTrackId != null && !requestedLiveTrackId.equalsIgnoreCase(returnedId)) {
                nextStartAttemptAtMs = Long.MAX_VALUE;
                CTError(TAG, "LiveTrack response did not confirm the requested ID; publication paused.");
                return;
            }
            liveTrackId = requestedLiveTrackId != null ? requestedLiveTrackId : returnedId;
            scheduleInterruptedPublication(true);
            CTDebug(TAG, String.format(Locale.US, "startLiveTrackComplete(%s): liveTrackId: '%s'",
                    trackLabel, liveTrackId));
            if (!ownsPublication() || shuttingDown || !active) {
                if (liveTrackId != null && !liveTrackId.isEmpty()) {
                    CaltopoMap.AddLiveTrack(liveTrackId, this);
                }
                CTDebug(TAG, String.format(Locale.US,
                        "startLiveTrackComplete(%s): ownership no longer local; retaining liveTrackId for orphan cleanup and leaving queued points buffered.",
                        trackLabel));
                startLiveTrackOp = null;
                return;
            }
            CaltopoMap.AddLiveTrack(liveTrackId, this);
        } catch (Exception e) {
            CTError(TAG, "startLiveTrackComplete(): raised:" + " (" + e.getClass().getSimpleName() + ")");
        }
        forwardNextWaypoints(null);
    }

    public void publishDirect(double lat, double lng, long altitudeInMeters, long droneTimestampInMillisec) {
        CaltopoRecoveryWork.offMainStart(() -> publishDirectOnWorker(lat, lng, altitudeInMeters, droneTimestampInMillisec));
    }

    private void publishDirectOnWorker(double lat, double lng, long altitudeInMeters, long droneTimestampInMillisec) {
        long startedAtMs = System.currentTimeMillis();
        queueWaypoint(
                lat,
                lng,
                altitudeInMeters,
                droneTimestampInMillisec,
                droneSpec.getLastPositionTelemetry(),
                true
        );
        logSideEffectIfSlow("publishDirect.total", myRemoteId, droneSpec.getMappedId(),
                System.currentTimeMillis() - startedAtMs);
    }

    @Override
    public void onPeerWaypoint(
            @NonNull String sourceZoneId,
            double lat,
            double lng,
            double altitudeMeters,
            long timestampMsec,
            @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        CaltopoRecoveryWork.offMainStart(() -> onPeerWaypointOnWorker(sourceZoneId, lat, lng, altitudeMeters, timestampMsec, telemetry));
    }

    private void onPeerWaypointOnWorker(String sourceZoneId, double lat, double lng, double altitudeMeters,
                                       long timestampMsec, @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        CTDebug(TAG, String.format(Locale.US,
                "onPeerWaypoint(%s <- %s): %.6f,%.6f alt=%.1f ts=%d",
                droneSpec.trackLabel(), sourceZoneId, lat, lng, altitudeMeters, timestampMsec));
        droneSpec.notePeerTelemetryReceived(System.currentTimeMillis());
        queueWaypoint(lat, lng, altitudeMeters, timestampMsec, telemetry, false);
    }

    private void queueWaypoint(
            double lat,
            double lng,
            double altitudeMeters,
            long timestampMsec,
            @Nullable CtDroneSpec.PositionTelemetry telemetry,
            boolean notifyCoordinator) {
        long startedAtMs = System.currentTimeMillis();
        StreamCameraTelemetrySample cameraTelemetry = currentCameraTelemetry(lat, lng, altitudeMeters);
        double effectiveLat = preferredLatitude(cameraTelemetry, lat);
        double effectiveLng = preferredLongitude(cameraTelemetry, lng);
        double effectiveAltitudeMeters = preferredAltitude(cameraTelemetry, altitudeMeters);
        if (cameraTelemetry != null && cameraTelemetry.getCourseDeg() != null) {
            CtDroneSpec.PositionTelemetry prior = telemetry != null
                    ? telemetry
                    : droneSpec.getLastPositionTelemetry();
            telemetry = new CtDroneSpec.PositionTelemetry(
                    prior == null ? null : prior.aircraftAltitudeRateFpm,
                    prior == null ? null : prior.aircraftGsKnots,
                    cameraTelemetry.getCourseDeg());
        }
        final boolean shouldForward;
        final boolean shouldStart;
        final boolean shouldNotifyCoordinator = notifyCoordinator;
        final CtDroneSpec.PositionTelemetry telemetryForCoordinator = telemetry;
        synchronized (this) {
            QueuedPoint previousPoint = linePoints.peekLast();
            if (isDuplicateOfPreviousPoint(previousPoint, effectiveLat, effectiveLng,
                    effectiveAltitudeMeters, timestampMsec)) {
                CTDebug(TAG, String.format(Locale.US,
                        "queueWaypoint(%s): dropping duplicate point %.6f,%.6f alt=%.1f ts=%d",
                        droneSpec.trackLabel(), lat, lng, altitudeMeters, timestampMsec));
                return;
            }
            linePoints.add(new QueuedPoint(effectiveLat, effectiveLng, effectiveAltitudeMeters, timestampMsec,
                    telemetry, cameraTelemetry));
            queuedPointCount.set(linePoints.size());
            trackObservedEndedAtMs = System.currentTimeMillis();
            // Per-point durable journal — do not wait for liveTrackId / requestedLiveTrackId.
            appendDurableJournalPointLocked(effectiveLat, effectiveLng, effectiveAltitudeMeters,
                    timestampMsec, System.currentTimeMillis(), "live");
            scheduleInterruptedPublication(false);
            CTDebug(TAG, String.format(Locale.US,
                    "queueWaypoint(%s/localOwner=%s/%s): waypoint queued. size=%d sent=%d confirmed=%d errors=%d notify=%s",
                    droneSpec.trackLabel(), localOwner, mapStatus.toString(), linePoints.size(),
                    linePointsSentCount, linePointsConfirmedCount, consecutiveUpdateFails, notifyCoordinator));
            boolean mapUp = mapStatus == CaltopoMap.MapStatusListener.mapStatus.up;
            boolean owner = ownsPublication();
            shouldForward = mapUp && owner && liveTrackId != null;
            shouldStart = mapUp && owner && liveTrackId == null;
        }
        long notifyStartedAtMs = System.currentTimeMillis();
        notifyLocalTrackPoint(effectiveLat, effectiveLng, effectiveAltitudeMeters, timestampMsec);
        logSideEffectIfSlow("queueWaypoint.notifyLocalTrackPoint", myRemoteId, droneSpec.getMappedId(),
                System.currentTimeMillis() - notifyStartedAtMs);

        if (shouldNotifyCoordinator) {
            long coordinatorStartedAtMs = System.currentTimeMillis();
            double distMeters = CaltopoMap.DistanceFromMeInMeters(effectiveLat, effectiveLng);
            runtime.getPeerCoordinator()
                    .onWaypointReceived(droneSpec, effectiveLat, effectiveLng, effectiveAltitudeMeters,
                            distMeters, timestampMsec, telemetryForCoordinator);
            logSideEffectIfSlow("queueWaypoint.peerCoordinator.onWaypointReceived", myRemoteId, droneSpec.getMappedId(),
                    System.currentTimeMillis() - coordinatorStartedAtMs);
        }

        if (shouldForward) {
            long forwardStartedAtMs = System.currentTimeMillis();
            forwardNextWaypoints(null);
            logSideEffectIfSlow("queueWaypoint.forwardNextWaypoints", myRemoteId, droneSpec.getMappedId(),
                    System.currentTimeMillis() - forwardStartedAtMs);
        } else if (shouldStart) {
            long startStartedAtMs = System.currentTimeMillis();
            ensureLiveTrackStarted();
            logSideEffectIfSlow("queueWaypoint.startNewTrack", myRemoteId, droneSpec.getMappedId(),
                    System.currentTimeMillis() - startStartedAtMs);
        } else {
            logSideEffectIfSlow("queueWaypoint.total.noPublish", myRemoteId, droneSpec.getMappedId(),
                    System.currentTimeMillis() - startedAtMs);
            return;
        }
        logSideEffectIfSlow("queueWaypoint.total", myRemoteId, droneSpec.getMappedId(),
                System.currentTimeMillis() - startedAtMs);
    }

    /**
     * Resolve / open the append-only WaypointTrackJournal for this segment.
     * Prefer durable publication ids; otherwise a stable local RidFlightId so recovery
     * can promote into AwaitingMapFlights without waiting for CalTopo LiveTrack create.
     * Must hold the track monitor.
     */
    @NonNull
    private String ensureDurableJournalOpenLocked() {
        if (durableJournalFlightId != null && !durableJournalFlightId.isEmpty()) {
            return durableJournalFlightId;
        }
        String mapId = publicationMapId != null ? publicationMapId : CaltopoMap.GetMapId();
        if (mapId == null) mapId = "";
        String flightId = null;
        if (liveTrackId != null && !liveTrackId.isEmpty()) {
            flightId = liveTrackId;
        } else if (requestedLiveTrackId != null && !requestedLiveTrackId.isEmpty()) {
            flightId = requestedLiveTrackId;
        } else {
            String publication = AwaitingMapFlights.publicationId(myRemoteId, mapId);
            if (publication != null && !publication.isEmpty()) {
                flightId = publication;
            } else {
                String reserved = AwaitingMapFlights.reservedPublicationId(myRemoteId);
                if (reserved != null && !reserved.isEmpty()) flightId = reserved;
            }
        }
        if (flightId == null || flightId.isEmpty()) {
            long started = trackObservedStartedAtMs > 0 ? trackObservedStartedAtMs : System.currentTimeMillis();
            String aircraft = myRemoteId != null && !myRemoteId.isEmpty() ? myRemoteId : segmentTrackLabel;
            flightId = RidFlightId.make(aircraft, started);
        }
        durableJournalFlightId = flightId;
        WaypointTrackJournal.ensureOpen(
                flightId,
                myRemoteId == null ? "" : myRemoteId,
                segmentTrackLabel,
                mapId,
                AwaitingMapFlights.selectedPublicationScope());
        return flightId;
    }

    /** Must hold the track monitor. I/O is queued off this thread by WaypointTrackJournal. */
    private void appendDurableJournalPointLocked(
            double lat, double lng, double ele, long timestampMsec, long receivedAtMs, @NonNull String source) {
        String flightId = ensureDurableJournalOpenLocked();
        WaypointTrackJournal.appendPoint(
                flightId, lat, lng, ele, timestampMsec, receivedAtMs, source, true);
    }

    /** Called with the track monitor; only copies in-memory geometry here. */
    @Nullable
    private JournalSnapshot journalSnapshot(@NonNull String description) {
        String journalId = liveTrackId != null ? liveTrackId : requestedLiveTrackId;
        if (journalId == null || journalId.isEmpty()) return null;
        JSONArray points = new JSONArray();
        try {
            for (QueuedPoint point : linePoints) {
                points.put(new JSONArray().put(point.lng).put(point.lat).put(point.ele));
            }
        } catch (JSONException error) {
            CTError(TAG, "Could not serialize interrupted LiveTrack points", error);
            return null;
        }
        return new JournalSnapshot(++journalRevision,
                publicationMapId != null ? publicationMapId : CaltopoMap.GetMapId(),
                myRemoteId, journalId, segmentTrackLabel, description, points);
    }

    private boolean saveJournalSnapshot(JournalSnapshot snapshot) {
        CaltopoRecoveryWork.requireOffMain();
        synchronized (journalWriteLock) {
            // A delayed periodic write must never overwrite a forced final archive checkpoint.
            if (snapshot.revision <= persistedJournalRevision) return true;
            boolean saved = CaltopoInterruptedTrackJournal.save(snapshot.mapId, snapshot.remoteId,
                    snapshot.trackId, snapshot.label, snapshot.description, snapshot.points);
            if (saved) {
                persistedJournalRevision = snapshot.revision;
                try { AwaitingMapFlights.notePublication(snapshot.remoteId, snapshot.mapId); }
                catch (Exception error) { CTError(TAG, "Could not persist publication handoff", error); }
            }
            return saved;
        }
    }

    private void scheduleInterruptedPublication(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastInterruptedJournalWriteMs < 5_000L) return;
        if (liveTrackId == null && requestedLiveTrackId == null) return;
        lastInterruptedJournalWriteMs = now;
        final long generation = publicationGeneration;
        CaltopoRecoveryWork.checkpoint(this, () -> {
            final JournalSnapshot snapshot;
            synchronized (this) {
                if (generation != publicationGeneration) return;
                snapshot = journalSnapshot("");
            }
            // Disk/SAF I/O never holds the track monitor or the RID ingest worker.
            if (snapshot != null) saveJournalSnapshot(snapshot);
        });
    }

    private boolean persistInterruptedPublication(@NonNull String description, boolean force) {
        CaltopoRecoveryWork.requireOffMain();
        long now = System.currentTimeMillis();
        if (!force && now - lastInterruptedJournalWriteMs < 5_000L) return false;
        JournalSnapshot snapshot = journalSnapshot(description);
        if (snapshot == null || !saveJournalSnapshot(snapshot)) return false;
        lastInterruptedJournalWriteMs = now;
        return true;
    }

    /** forwardNextWaypoints():
     *  Pull waypoints off the queue and forward to Caltopo
     */
    public void forwardNextWaypoints(@Nullable CaltopoOp lastOp) {
        CaltopoRecoveryWork.offMainStart(() -> forwardNextWaypointsOnWorker(lastOp));
    }

    private void forwardNextWaypointsOnWorker(@Nullable CaltopoOp lastOp) {
        if (!CaltopoNetworkReachability.hasValidatedInternet()) {
            CTDebug(TAG, "forwardNextWaypoints(): deferred offline");
            return;
        }
        final QueuedPoint pointToSend;
        final long generation;
        final int sentOrdinal;
        final StreamCameraTelemetrySample cameraTelemetry;
        synchronized (this) {
            // Once a LiveTrack exists, keep flushing even while a durable intent write is pending.
            if (liveTrackId == null) {
                String destinationChoice = AwaitingMapFlights.publicationId(myRemoteId, CaltopoMap.GetMapId());
                if (destinationChoice != null && destinationChoice.isEmpty()) return;
            }
            if (shuttingDown || !active || liveTrackId == null) {
                CTDebug(TAG, "forwardNextWaypoints(): Not active.");
                return; // Don't send any more waypoints at this time.
            }
            if (!ownsPublication()) {
                CTDebug(TAG, String.format(Locale.US,
                        "forwardNextWaypoints(%s): not local owner; leaving %d queued point(s) buffered.",
                        droneSpec.trackLabel(),
                        Math.max(0, linePoints.size() - linePointsSentCount)));
                return;
            }
            if (null != lastOp && lastOp.isDone()) {
                linePointsConfirmedCount++;
                long rtt = lastOp.roundTripTimeInMsec();
                if (BuildConfig.DEBUG) {
                    CTDebug(TAG, String.format(Locale.US,
                            "positionReport response: code=%d success=%s rttMs=%d",
                            lastOp.responseCode, lastOp.success(), rtt));
                }
                CaltopoRttInMsec.next(rtt);
                runtime.getPeerCoordinator().updateCaltopoRtt(rtt);
                if (lastOp.fail()) {
                    consecutiveUpdateFails++;
                    CTError(TAG, "forwardNextWaypoints(): addLiveTrackPoint failed: " + lastOp);
                    if (consecutiveUpdateFails > 2) {
                        CTError(TAG, "forwardNextWaypoints(): connection unavailable; retaining LiveTrack identity. ");
                        return;
                    }
                } else {
                    consecutiveUpdateFails = 0;
                }
            }
            int pointCount = linePoints.size();
            if (linePointsSentCount >= pointCount) {
                pointToSend = null;
                generation = publicationGeneration;
                sentOrdinal = linePointsSentCount;
                cameraTelemetry = null;
            } else {
                // Live reporting follows the latest position; the complete list is kept for the Shape.
                linePointsSentCount = pointCount;
                pointToSend = linePoints.getLast();
                generation = publicationGeneration;
                sentOrdinal = linePointsSentCount;
                cameraTelemetry = pointToSend.cameraTelemetry;
            }
        }
        if (pointToSend == null) return;
        CaltopoCameraMetadata cameraMetadata = activeVideoCameraMetadata(cameraTelemetry);
        try {
            CTDebug(TAG, String.format(Locale.US, "forwardNextWaypoint(%s#%d): adding %.7f,%.7f@%dm to LiveTrack.",
                    effectiveFleetDeviceId(), sentOrdinal, pointToSend.lat, pointToSend.lng, (long) pointToSend.ele));
            runtime.getCalTopoSessionGateway()
                    .addLiveTrackPoint(myRemoteId, pointToSend.lat, pointToSend.lng, pointToSend.ele,
                            pointToSend.telemetry, cameraMetadata,
                            op -> CaltopoRecoveryWork.offMainStart(() -> {
                                if (generation == publicationGeneration) forwardNextWaypoints(op);
                            }));
        } catch (Exception e) {
            CTError(TAG, "forwardNextWaypoints(): addLiveTrackPoint() raised: " + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    @Nullable
    private CaltopoCameraMetadata activeVideoCameraMetadata(
            @Nullable StreamCameraTelemetrySample cameraTelemetry) {
        String mappedId = droneSpec.getMappedId().trim();
        if (mappedId.isEmpty()) return null;
        ManagedVideoStreamAdvertisement publishingVideo = runtime.getPeerCoordinator()
                .getManagedVideoStreams()
                .stream()
                .filter(stream -> "live".equals(stream.mediaKind))
                .filter(stream -> stream.droneDesignator.trim().equalsIgnoreCase(mappedId))
                .findFirst()
                .orElse(null);
        if (publishingVideo == null) return null;
        String tabletUrl = TrackerTabletLink.shortUrl(
                CaltopoClient.GetTrackerCoordinationUrlPfx(),
                R2CActivity.MyDeviceName
        );
        String thumbnailUrl = publishingVideo.thumbnailRevision.isEmpty()
                ? null
                : TrackerTabletLink.thumbnailUrl(
                        CaltopoClient.GetTrackerCoordinationUrlPfx(),
                        R2CActivity.MyDeviceName,
                        publishingVideo.sessionId,
                        publishingVideo.thumbnailRevision
                );
        return tabletUrl == null
                ? null
                : new CaltopoCameraMetadata(
                        tabletUrl,
                        thumbnailUrl,
                        cameraTelemetry == null ? null : cameraTelemetry.getAzimuthDeg(),
                        cameraTelemetry == null ? null : cameraTelemetry.getTiltDeg(),
                        cameraTelemetry == null ? null : cameraTelemetry.getHorizontalFovDeg(),
                        cameraTelemetry == null ? null : cameraTelemetry.getVerticalFovDeg());
    }

    private void refreshActiveVideoCameraMetadata() {
        final QueuedPoint point;
        final String mappedId;
        final String revision;
        synchronized (this) {
            if (!active || shuttingDown || !ownsPublication() ||
                    mapStatus != CaltopoMap.MapStatusListener.mapStatus.up ||
                    liveTrackId == null || linePoints.isEmpty()) return;
            if (thumbnailMetadataRefreshOp != null && !thumbnailMetadataRefreshOp.isDone()) return;

            mappedId = droneSpec.getMappedId().trim();
            if (mappedId.isEmpty()) return;
            ManagedVideoStreamAdvertisement publishingVideo = runtime.getPeerCoordinator()
                    .getManagedVideoStreams()
                    .stream()
                    .filter(stream -> "live".equals(stream.mediaKind))
                    .filter(stream -> stream.droneDesignator.trim().equalsIgnoreCase(mappedId))
                    .findFirst()
                    .orElse(null);
            if (publishingVideo == null || publishingVideo.thumbnailRevision.isEmpty() ||
                    publishingVideo.thumbnailRevision.equals(lastThumbnailMetadataRevision)) return;

            point = linePoints.peekLast();
            if (point == null) return;
            revision = publishingVideo.thumbnailRevision;
            // Claim the revision under the monitor so a concurrent refresh does not double-POST.
            lastThumbnailMetadataRevision = revision;
        }
        CaltopoCameraMetadata cameraMetadata = activeVideoCameraMetadata(point.cameraTelemetry);
        if (cameraMetadata == null || cameraMetadata.thumbnailUrl == null) {
            synchronized (this) {
                if (revision.equals(lastThumbnailMetadataRevision)) lastThumbnailMetadataRevision = "";
            }
            return;
        }
        CaltopoOp op = runtime.getCalTopoSessionGateway().addLiveTrackPoint(
                myRemoteId,
                point.lat,
                point.lng,
                point.ele,
                point.telemetry,
                cameraMetadata,
                null);
        synchronized (this) {
            thumbnailMetadataRefreshOp = op;
        }
        CTDebug(TAG, String.format(Locale.US,
                "thumbnail metadata refreshed remoteId=%s mappedId=%s revision=%s",
                myRemoteId, mappedId, revision));
    }

    @Nullable
    private StreamCameraTelemetrySample currentCameraTelemetry(
            double ridLatitude,
            double ridLongitude,
            double ridAltitudeMeters) {
        String mappedId = droneSpec.getMappedId().trim();
        return mappedId.isEmpty()
                ? null
                : StreamCameraTelemetryRegistry.INSTANCE.freshOperationalPosition(mappedId, System.currentTimeMillis());
    }

    private void publishFreshSeiPositionIfAvailable() {
        final double lat;
        final double lng;
        final double altitude;
        final long receivedAtMs;
        final CtDroneSpec.PositionTelemetry telemetry;
        final Long seiDroneTimeMs;
        synchronized (this) {
            if (!active || shuttingDown || !ownsPublication() ||
                    mapStatus != CaltopoMap.MapStatusListener.mapStatus.up) return;
            StreamCameraTelemetrySample sample = currentCameraTelemetry(
                    droneSpec.lastLat, droneSpec.lastLng, droneSpec.lastAlt);
            if (sample == null || sample.getLatitudeDeg() == null || sample.getLongitudeDeg() == null ||
                    sample.getReceivedAtMs() <= lastSeiPositionTimestampMs) return;
            altitude = preferredAltitude(sample, droneSpec.lastAlt);
            // Share the same gate as CaltopoClient.newWaypoint, rather than adding a
            // second stream of recorded positions between its accepted waypoints.
            if (!droneSpec.shouldRecordWaypoint(sample.getLatitudeDeg(), sample.getLongitudeDeg(),
                    sample.getReceivedAtMs(), CaltopoClient.GetMinDistanceInFeet(),
                    CtDroneSpec.TransportTypeEnum.DJI_STREAM)) return;
            seiDroneTimeMs = sample.getDroneTimeMs();
            lat = sample.getLatitudeDeg();
            lng = sample.getLongitudeDeg();
            receivedAtMs = sample.getReceivedAtMs();
            CtDroneSpec.PositionTelemetry prior = droneSpec.getLastPositionTelemetry();
            telemetry = new CtDroneSpec.PositionTelemetry(
                    prior == null ? null : prior.aircraftAltitudeRateFpm,
                    prior == null ? null : prior.aircraftGsKnots,
                    sample.getCourseDeg() != null
                            ? sample.getCourseDeg()
                            : (prior == null ? null : prior.aircraftTrackDeg));
            lastSeiPositionTimestampMs = receivedAtMs;
        }
        WaypointTrack.AddWaypointForTrack(droneSpec, lat, lng,
                Math.round(altitude), seiDroneTimeMs != null ? seiDroneTimeMs : receivedAtMs,
                receivedAtMs, "dji-stream", seiDroneTimeMs != null);
        queueWaypoint(lat, lng, altitude, receivedAtMs, telemetry, false);
    }

    private double preferredLatitude(@Nullable StreamCameraTelemetrySample sample, double fallback) {
        return sample != null && sample.getLatitudeDeg() != null
                ? sample.getLatitudeDeg()
                : fallback;
    }

    private double preferredLongitude(@Nullable StreamCameraTelemetrySample sample, double fallback) {
        return sample != null && sample.getLongitudeDeg() != null
                ? sample.getLongitudeDeg()
                : fallback;
    }

    private double preferredAltitude(@Nullable StreamCameraTelemetrySample sample, double fallback) {
        if (sample == null) return fallback;
        Double altitude = PeerTrafficAltitudeNormalizer.reportedAltitudeForRelativeUp(
                myRemoteId, sample.getRelativeUpMeters());
        return altitude != null && Double.isFinite(altitude) ? altitude : fallback;
    }

    private boolean isDuplicateOfPreviousPoint(
            @Nullable QueuedPoint previousPoint,
            double lat,
            double lng,
            double altitudeMeters,
            long timestampMsec) {
        if (previousPoint == null) return false;
        if (previousPoint.timestampMsec != timestampMsec) return false;
        return Math.abs(previousPoint.lat - lat) <= DUPLICATE_COORD_EPSILON &&
                Math.abs(previousPoint.lng - lng) <= DUPLICATE_COORD_EPSILON &&
                Math.abs(previousPoint.ele - altitudeMeters) <= DUPLICATE_ALT_EPSILON_METERS;
    }

    private void notifyLocalTrackPoint(double lat, double lng, double altitudeMeters, long timestampMsec) {
        long startedAtMs = System.currentTimeMillis();
        if (LocalTrackListeners.isEmpty()) {
            CTDebug(ICON_LATENCY_TAG, String.format(Locale.US,
                    "track_notify_skipped remoteId=%s mappedId=%s reason=no_listeners droneTs=%d lat=%.6f lng=%.6f alt=%.1f",
                    myRemoteId, droneSpec.getMappedId(), timestampMsec, lat, lng, altitudeMeters));
            return;
        }
        String mappedId = droneSpec.getMappedId();
        for (LocalTrackListener listener : LocalTrackListeners) try {
            long listenerStartedAtMs = System.currentTimeMillis();
            listener.onLocalTrackPoint(myRemoteId, mappedId, lat, lng, altitudeMeters, timestampMsec);
            logSideEffectIfSlow("notifyLocalTrackPoint.listener." + listener.getClass().getName(),
                    myRemoteId, mappedId, System.currentTimeMillis() - listenerStartedAtMs);
        } catch (Exception e) {
            CTError(TAG, "notifyLocalTrackPoint() listener raised" + " (" + e.getClass().getSimpleName() + ")");
        }
        logSideEffectIfSlow("notifyLocalTrackPoint.total", myRemoteId, mappedId,
                System.currentTimeMillis() - startedAtMs);
    }
}
