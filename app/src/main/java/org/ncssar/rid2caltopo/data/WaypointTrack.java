/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */


package org.ncssar.rid2caltopo.data;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;
import static org.ncssar.rid2caltopo.data.CaltopoClient.GetTodaysTrackDir;

import org.json.*;
import org.ncssar.rid2caltopo.BuildConfig;
import org.ncssar.rid2caltopo.app.R2CActivity;
import org.ncssar.rid2caltopo.app.R2CApplication;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

/*
 * Module for building waypoint-based Caltopo tracks on the fly as waypoints come in.
 * This is to support rapid archival to a geo-json file on application termination.
 *
 * Compare one waypoint to the next to determine if there is significant enough change
 * in location to warrant archiving the new point - per MinDistanceInFeet parameter.
 *
  * Sample Caltopo .json file format:
  * filename: <mappedID><startTimestamp>.json
 * 
 * {
 *   "type": "FeatureCollection",
 *   "features": [
 *     {
 *       "type": "Feature",
 *       "properties": {
 *         "title": "<mappedID><startTimestamp>"
 *       },
 *       "geometry": {
 *         "type": "LineString",
 *         "coordinates": [
 *           [
 *             -121.09279,
 *             39.2966,
 *             358,
 *             1752642725896
 *           ],
 *           [
 *             -121.09279,
 *             39.2966,
 *             358,
 *             1752642726897
 *           ]
 *         ]
 *       }   // geometry feature
 *     }     // Feature
 *   ]       // Feature array   
 * }         // geojson FeatureCollection
 *
 */


public class WaypointTrack {
    public static class TrackPoint {
        public final double lat;
        public final double lng;
        public final double ele;
        public final long timestampMsec;
        public final long receivedAtMsec;

        public TrackPoint(double lat, double lng, double ele, long timestampMsec) {
            this(lat, lng, ele, timestampMsec, timestampMsec);
        }

        public TrackPoint(double lat, double lng, double ele, long timestampMsec, long receivedAtMsec) {
            this.lat = lat;
            this.lng = lng;
            this.ele = ele;
            this.timestampMsec = timestampMsec;
            this.receivedAtMsec = receivedAtMsec;
        }
    }

	public static int WaypointCount = 0;
	private static final String TAG = "WaypointTrack";
    private static final String ReportedFilenames = "r2c_reported.txt";
    private static final int MAX_GEOJSON_STATS_RETRIES = 3;
    private static final long GEOJSON_RETRY_BASE_DELAY_MS = 500;
    private static final long GEOJSON_PUBLISH_TIMEOUT_SECONDS = 20;
    private static final long ACTIVE_TRACK_GRACE_MS = 1000 * 60 * 60;
    private static final DateTimeFormatter TRACK_DIRECTORY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.US);
    public static final int GEOJSON_STATS_UPLOAD_SKIPPED = -1;

    private static final String GEOJSON_MIME_TYPE = "application/geo+json";
	// map trackLabel to WaypointTrack.
	private static final java.util.concurrent.ConcurrentHashMap<String, WaypointTrack> TrackMap = new java.util.concurrent.ConcurrentHashMap<>();
    @Nullable
    private static ExecutorService TrackArchiveExecutorPool = null;

    private CtDroneSpec droneSpec;
    
    private String incident;
    private String opPeriod;
    private String mapId;

	private JSONArray coordinates;
	private String trackLabel;
	// startTimeStr is the time the track was started - used in track archive.
	private String startTimeStr;
    private DocumentFile dataFilepath;
    private String fileName;
    // Aligned with coordinates: app receive time (diagnostic only), drone-clock flag and source.
    private final List<Long> pointReceivedAtMs = new ArrayList<>();
    // Published append-only render history: worker snapshots never lock the
    // live ingest/archive monitor or traverse concurrently-mutated JSON.
    private static final class RenderPointNode {
        final TrackPoint point;
        final RenderPointNode previous;
        RenderPointNode(TrackPoint point, RenderPointNode previous) {
            this.point = point; this.previous = previous;
        }
    }
    private volatile RenderPointNode renderTail;
    private final List<Boolean> pointDroneClock = new ArrayList<>();
    private final List<String> pointSources = new ArrayList<>();
    // Flight start (app clock, Unix ms) and the stable flight id derived from it and the drone's
    // remote id with the iOS scheme (RidFlightId), so both platforms name the same flight alike.
    private final long startedAtMs;
    private final String deferredPublicationId;
    private boolean archivePrepared = false;
    /** This tablet owned the segment while it flew. A later takeover must not downgrade it. */
    private boolean closedAsPriorOwner = false;
    /** Points recorded before this tablet was granted the lease. Kept locally, never uploaded. */
    private boolean closedAsShadow = false;
    /** At least one point was stored while another tablet held the owner lease. */
    private boolean recordedAsShadow = false;
    /** Points before this stay in memory for the map and clues, and are left out of the file. */
    private long ownedFromMs = 0L;
    private String archivedOwner = "";
    private String archivedFlightReadinessJson = "{}";
    private String archivedModel = "";
    private String archivedOrg = "";
    private String archivedRemoteId = "";
    private String archivedMappedId = "";
    private boolean archivedLocalOnly = false;
    private boolean archivedOkToLog = false;
    private boolean archivedFlightConfirmed = false;
    private boolean archivedTrackerUploadAuthorized = false;
    private boolean archivedTrackerClaimPending = false;
    private String archivedClaimState = FlightOwnershipContext.NONE;
    private double archivedDistanceInFeet = 0.0;

	public WaypointTrack(@NonNull String trackLabel, @NonNull CtDroneSpec droneSpec) {
		SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US);
		startedAtMs = System.currentTimeMillis();
		startTimeStr = sdf.format(new Date(startedAtMs));
		String remoteId = droneSpec.getRemoteId();
		deferredPublicationId = RidFlightId.make(
				remoteId == null || remoteId.isEmpty() ? trackLabel : remoteId, startedAtMs);
        this.droneSpec = droneSpec;
		this.trackLabel = trackLabel;
		this.coordinates = new JSONArray();
		this.incident = CaltopoClient.GetIncident();
        this.opPeriod = CaltopoClient.GetOpPeriod();
        this.mapId = CaltopoMap.GetMapId();
		CTDebug(TAG, String.format("AddWaypointForTrack(%s): Starting new track.", trackLabel));
        try {
            R2cRuntimeRegistry.getDefaultRuntime().getPeerCoordinator()
                    .noteFlightStart(remoteId == null ? "" : remoteId, startedAtMs);
        } catch (Exception ignored) { }
        WaypointTrackJournal.ensureOpen(
                deferredPublicationId,
                remoteId == null ? "" : remoteId,
                trackLabel,
                mapId == null ? "" : mapId,
                AwaitingMapFlights.selectedPublicationScope(),
                startedAtMs);
	}


	public static void AddWaypointForTrack(@NonNull CtDroneSpec droneSpec, double lat, double lng,
										   long altitude, long timestampInMillisec) {
		AddWaypointForTrack(droneSpec, lat, lng, altitude, timestampInMillisec,
				System.currentTimeMillis(), "rid", true);
	}

	/**
	 * timestampInMillisec is the drone's clock (RID timestamp or SEI frame PTS mapped by the stream
	 * drone clock); receivedAtMs is the app's receive time, recorded only as a diagnostic.
	 */
	public static void AddWaypointForTrack(@NonNull CtDroneSpec droneSpec, double lat, double lng,
										   long altitude, long timestampInMillisec, long receivedAtMs,
										   @NonNull String source, boolean droneClock) {
		String trackLabel = droneSpec.trackLabel();
		WaypointTrack track = TrackMap.computeIfAbsent(trackLabel, key -> new WaypointTrack(key, droneSpec));
		track.addWaypoint(lat, lng, altitude, timestampInMillisec, receivedAtMs, source, droneClock);
	}

    /** Track points with drone-clock times for clue binding; empty when the drone has no live track. */
    @NonNull
    public static List<ClueBindingPoint> GetBindingPointsSnapshot(@NonNull CtDroneSpec droneSpec) {
        WaypointTrack track = TrackMap.get(droneSpec.trackLabel());
        if (track == null) return new ArrayList<>();
        return track.getBindingPointsSnapshot();
    }

    /** Id of the drone's live track (its awaiting-map journal id), or null when none is recording. */
    @Nullable
    public static String GetLiveFlightId(@NonNull CtDroneSpec droneSpec) {
        WaypointTrack track = TrackMap.get(droneSpec.trackLabel());
        return track == null ? null : track.deferredPublicationId;
    }

    /** Binding points of the live track with this id, or null once it has been archived. */
    @Nullable
    public static List<ClueBindingPoint> GetBindingPointsForFlight(@NonNull String flightId) {
        for (WaypointTrack track : new ArrayList<>(TrackMap.values())) {
            if (track.deferredPublicationId.equals(flightId)) return track.getBindingPointsSnapshot();
        }
        return null;
    }

    public static boolean IsLiveFlight(@NonNull String flightId) {
        return GetBindingPointsForFlight(flightId) != null;
    }

    public static void CapturePublicationIntent(CtDroneSpec drone) {
        WaypointTrack track = TrackMap.get(drone.trackLabel());
        if (track != null && track.droneSpec.getRemoteId().equals(drone.getRemoteId()))
            track.recordPublicationIntent(false, true);
    }

    private static final class PublicationScope {
        final String map, team;
        PublicationScope(String map, String team) { this.map = map; this.team = team; }
    }
    private final java.util.concurrent.atomic.AtomicReference<PublicationScope> publicationIntentScope =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean publicationIntentFinished =
            new java.util.concurrent.atomic.AtomicBoolean();

    private boolean publicationIntentRecorded; // recovery worker only

    private void recordPublicationIntent(boolean finished) {
        recordPublicationIntent(finished, false);
    }

    static void NoteOwnershipContextForRemote(@NonNull String remoteId) {
        if (remoteId.isEmpty()) return;
        for (WaypointTrack track : new ArrayList<>(TrackMap.values())) {
            if (remoteId.equals(track.droneSpec.getRemoteId())) track.noteOwnershipContext();
        }
    }

    private void noteOwnershipContext() {
        WaypointTrackJournal.noteContext(deferredPublicationId, FlightOwnershipContext.capture(droneSpec));
    }

    private void recordPublicationIntent(boolean finished, boolean confirmIntent) {
        FlightOwnershipContext.Snapshot liveContext = FlightOwnershipContext.capture(droneSpec);
        final boolean ownershipReviewPending = finished ? archivedTrackerClaimPending : liveContext.getRequiresHold();
        final String recordedPilot = finished
                ? (archivedFlightConfirmed && !archivedLocalOnly ? archivedOwner : "")
                : liveContext.getRecordedPilot();
        final String claim = finished ? archivedClaimState : liveContext.getClaim();
        noteOwnershipContext();
        // Capture consent's destination before dispatch, including when no map is selected.
        if (publicationIntentScope.compareAndSet(null,
                new PublicationScope(CaltopoMap.GetMapId(), AwaitingMapFlights.selectedPublicationScope()))) {
            AwaitingMapFlights.reservePublication(droneSpec.getRemoteId(), deferredPublicationId);
        }
        if (finished) publicationIntentFinished.set(true);
        // Confirm with a connected map: mark publicationStarted immediately so a near-immediate
        // flight end cannot promote bound → deferred publish before the durable write lands.
        if (confirmIntent && !ownershipReviewPending) {
            AwaitingMapFlights.markPublicationStarted(droneSpec.getRemoteId());
            String remoteId = droneSpec.getRemoteId(), label = trackLabel, flightId = deferredPublicationId;
            CaltopoRecoveryWork.executeStart(() -> CaltopoLiveTrack.seedConfirmedPublication(remoteId, flightId, label));
        }
        // Publication intent must use the start lane so a stalled archive cannot block
        // awaiting-map durability for the next airborne flight.
        CaltopoRecoveryWork.checkpointStart(this, () -> {
            try {
                final JSONArray points;
                synchronized (this) { points = new JSONArray(coordinates.toString()); }
                PublicationScope scope = publicationIntentScope.get();
                if (!publicationIntentRecorded) {
                    // A slow provider may delay the first write until the flight has ended.
                    AwaitingMapFlights.recordOwned(deferredPublicationId, droneSpec.getRemoteId(), trackLabel,
                            points, false, scope.map, scope.team, ownershipReviewPending,
                            recordedPilot, claim, true, false);
                    publicationIntentRecorded = true;
                }
                AwaitingMapFlights.recordOwned(deferredPublicationId, droneSpec.getRemoteId(), trackLabel,
                        points, publicationIntentFinished.get(), scope.map, scope.team, ownershipReviewPending,
                        recordedPilot, claim, true, false);
                if (confirmIntent && !ownershipReviewPending && !scope.map.isEmpty()) {
                    AwaitingMapFlights.notePublication(droneSpec.getRemoteId(), scope.map, trackLabel);
                }
            } catch (Exception error) {
                CTError(TAG, "Could not persist publication intent; local track recording continues", error);
            }
        });
    }

    @NonNull
    public static List<TrackPoint> GetTrackPointsSnapshot(@NonNull CtDroneSpec droneSpec) {
        return GetTrackPointsSnapshotForLabel(droneSpec.trackLabel());
    }

    @Nullable
    public static TrackPoint GetLatestTrackPoint(@NonNull CtDroneSpec droneSpec) {
        WaypointTrack track = TrackMap.get(droneSpec.trackLabel());
        RenderPointNode tail = track == null ? null : track.renderTail;
        return tail == null ? null : tail.point;
    }

    /** Worker callers freeze the segment label before dispatch, not the mutable drone object. */
    @NonNull
    public static List<TrackPoint> GetTrackPointsSnapshotForLabel(@NonNull String label) {
        WaypointTrack track = TrackMap.get(label);
        if (track == null) return new ArrayList<>();
        return track.getTrackPointsSnapshot();
    }

    /** Tiny lock-free status read; unchanged history need not be copied for every UI update. */
    @Nullable
    public static Object GetTrackRenderRevision(@NonNull String label) {
        WaypointTrack track = TrackMap.get(label);
        return track == null ? null : track.renderTail;
    }

    public static void RenameTrack(
            @NonNull String oldTrackLabel,
            @NonNull String newTrackLabel,
            @NonNull CtDroneSpec droneSpec
    ) {
        if (oldTrackLabel.equals(newTrackLabel)) return;
        WaypointTrack track = TrackMap.remove(oldTrackLabel);
        if (track == null) return;

        WaypointTrack existing = TrackMap.get(newTrackLabel);
        if (existing != null) {
            existing.absorb(track, droneSpec);
            CTDebug(TAG, String.format(Locale.US,
                    "RenameTrack(%s -> %s): merged into existing track.", oldTrackLabel, newTrackLabel));
            return;
        }

        track.trackLabel = newTrackLabel;
        track.droneSpec = droneSpec;
        TrackMap.put(newTrackLabel, track);
        CTDebug(TAG, String.format(Locale.US,
                "RenameTrack(%s -> %s): track relabeled.", oldTrackLabel, newTrackLabel));
    }

    /** Rekey a visible label without merging or scanning either track on the calling thread. */
    @NonNull
    public static String RenameTrackForFlightLabel(@NonNull String oldLabel, @NonNull String requestedLabel,
                                                    @NonNull CtDroneSpec droneSpec) {
        if (oldLabel.equals(requestedLabel)) return oldLabel;
        WaypointTrack track = TrackMap.get(oldLabel);
        String candidate = requestedLabel;
        WaypointTrack occupied = TrackMap.get(candidate);
        if (occupied != null && occupied != track) {
            candidate = requestedLabel + "-" + droneSpec.getRemoteId();
            occupied = TrackMap.get(candidate);
            if (occupied != null && occupied != track) return oldLabel;
        }
        if (track == null) return candidate;
        WaypointTrack previous = TrackMap.putIfAbsent(candidate, track);
        if (previous != null && previous != track) return oldLabel;
        track.trackLabel = candidate;
        track.droneSpec = droneSpec;
        TrackMap.remove(oldLabel, track);
        return candidate;
    }

	public static void ArchiveTrack(@NonNull String trackLabel) {
		WaypointTrack track = TrackMap.remove(trackLabel);
		if (null != track) {
            track.prepareForArchive();
            QueueTrackArchive(track);
        }
	}

    /** Capture claim state before flight-end notification retires the coordinator state. */
    public static void PrepareTrackForFlightEnd(@NonNull CtDroneSpec spec) {
        WaypointTrack track = TrackMap.get(spec.trackLabel());
        if (track != null) track.prepareForArchive();
    }

    public static void ArchiveTracks() {
		if (0 == WaypointCount) {
			CTDebug(TAG, "ArchiveTracks(): no waypoints recorded");
			return;
		}
        ArrayList<String> keys = new ArrayList<>(TrackMap.size());
		keys.addAll(TrackMap.keySet());
		for (String key : keys) {
			WaypointTrack track = TrackMap.remove(key);
			if (null != track) {
                track.prepareForArchive();
                track.archive();
            }
		}
	}

    synchronized void prepareForArchive() {
        if (archivePrepared) return;
        archivedOwner = droneSpec.getOwner();
        archivedFlightReadinessJson = droneSpec.getFlightReadinessJson();
        archivedModel = droneSpec.getModel();
        archivedOrg = droneSpec.getOrg();
        archivedRemoteId = droneSpec.getRemoteId();
        archivedMappedId = droneSpec.getMappedId();
        archivedLocalOnly = droneSpec.isLocalArchiveOnly();
        archivedOkToLog = droneSpec.okToLog();
        archivedFlightConfirmed = droneSpec.isCurrentFlightConfirmed()
                || CaltopoClient.IsCurrentLocallyConfirmedDrone(archivedRemoteId);
        archivedDistanceInFeet = droneSpec.getDistanceInFeet();
        String claim = FlightOwnershipContext.NONE;
        try {
            claim = R2cRuntimeRegistry.getDefaultRuntime().getPeerCoordinator().ownershipClaimState(archivedRemoteId);
        } catch (Exception ignored) { }
        if (closedAsShadow) {
            archivedFlightConfirmed = false;
            archivedLocalOnly = false;
        }
        boolean mine = archivedFlightConfirmed && !archivedLocalOnly;
        archivedClaimState = claim == null ? FlightOwnershipContext.NONE : claim;
        archivedTrackerClaimPending = mine && FlightOwnershipContext.isAdverseClaim(archivedClaimState);
        // Ownership of a finished segment is who owned it while it flew. A takeover that
        // lands before the idle timeout must not turn that segment into local_only.
        boolean ownedWhileFlying = closedAsPriorOwner || isLocalFlightOwnerForUpload(archivedRemoteId);
        archivedTrackerUploadAuthorized = mine &&
                !archivedTrackerClaimPending &&
                ownedWhileFlying &&
                CaltopoClient.IsKnownTeamDroneForTrackerUpload(
                        archivedRemoteId, archivedOrg);
        noteOwnershipContext();
        archivePrepared = true;
    }

    /**
     * Only the local flight owner uploads the archive to the tracker. A peer that currently
     * holds the owner lease is a non-owner and must not upload. Where coordination has not
     * assigned any owner (standalone / tracker coordination idle) this device is the sole
     * recorder, so it is not treated as a non-owner.
     */

    @NonNull
    private String publishDecisionLabel() {
        if (!archivedFlightConfirmed) return "unconfirmed";
        if (archivedLocalOnly) {
            try {
                PeerCoordinator peers = R2cRuntimeRegistry.getDefaultRuntime().getPeerCoordinator();
                String remote = archivedRemoteId == null ? "" : archivedRemoteId;
                if (!remote.isEmpty() && peers.hasActivePeerOwner(remote)) {
                    String zone = peers.ownerGuidForRemoteId(remote);
                    if (zone == null || zone.isEmpty()) zone = "unknown";
                    return "non-published (peer owner " + zone + ")";
                }
            } catch (Exception ignored) { }
            return "local_only";
        }
        if (!archivedOkToLog) return "declined";
        if (!archivedTrackerUploadAuthorized) return "tracker_skip";
        return "publish";
    }

    /**
     * Ends the unconfirmed copy recorded while another tablet owned the aircraft.
     * The returned track is no longer live. Its archive stays local and is not uploaded.
     * The drone's next waypoint starts the owned flight.
     */
    @Nullable
    static WaypointTrack detachShadow(@NonNull CtDroneSpec spec) {
        String label = spec.trackLabel();
        if (label == null || label.isEmpty()) return null;
        WaypointTrack track = TrackMap.remove(label);
        if (track == null) return null;
        if (track.coordinates.length() == 0 || !track.recordedAsShadow) {
            TrackMap.put(label, track);
            return null;
        }
        track.closedAsShadow = true;
        spec.beginOwnedFlightAfterShadow();
        return track;
    }

    /**
     * Drops points recorded while another tablet held the lease. The track, its label,
     * and its local record stay; nothing is written for the dropped points.
     */
    static boolean omitShadowPointsBeforeGrant(@NonNull CtDroneSpec spec, long grantedAtMs) {
        String label = spec.trackLabel();
        if (label == null || label.isEmpty()) return false;
        WaypointTrack track = TrackMap.get(label);
        if (track == null) return false;
        long ownedStartMs;
        synchronized (track) {
            if (!track.recordedAsShadow && track.ownedFromMs > 0L) return false;
            track.recordedAsShadow = false;
            ownedStartMs = grantedAtMs;
            track.ownedFromMs = grantedAtMs;
            track.noteOwnedStartLocked(ownedStartMs);
        }
        spec.retitleToOwnedStart(ownedStartMs);
        WaypointTrackJournal.discardUnfinished(track.deferredPublicationId);
        return true;
    }

    /** Call before {@link #prepareForArchive()} when a takeover closes a segment this tablet owned. */
    static void markClosedAsPriorOwner(@Nullable String trackLabel) {
        if (trackLabel == null || trackLabel.isEmpty()) return;
        WaypointTrack track = TrackMap.get(trackLabel);
        if (track != null) track.closedAsPriorOwner = true;
    }

    private static long coordinateTimestamp(@Nullable JSONArray point) {
        if (point == null || point.length() < 4) return 0L;
        Object value = point.opt(3);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void dropPointsBeforeLocked(long grantedAtMs) {
        JSONArray kept = new JSONArray();
        ArrayList<Long> keptReceived = new ArrayList<>();
        ArrayList<Boolean> keptClock = new ArrayList<>();
        ArrayList<String> keptSources = new ArrayList<>();
        int removed = 0;
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray point = coordinates.optJSONArray(i);
            long stamp = coordinateTimestamp(point);
            if (point != null && stamp > 0L && stamp < grantedAtMs) {
                removed++;
                continue;
            }
            kept.put(point);
            if (i < pointReceivedAtMs.size()) keptReceived.add(pointReceivedAtMs.get(i));
            if (i < pointDroneClock.size()) keptClock.add(pointDroneClock.get(i));
            if (i < pointSources.size()) keptSources.add(pointSources.get(i));
        }
        coordinates = kept;
        pointReceivedAtMs.clear();
        pointReceivedAtMs.addAll(keptReceived);
        pointDroneClock.clear();
        pointDroneClock.addAll(keptClock);
        pointSources.clear();
        pointSources.addAll(keptSources);
        renderTail = null;
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray point = coordinates.optJSONArray(i);
            if (point == null) continue;
            long received = i < pointReceivedAtMs.size() && pointReceivedAtMs.get(i) != null
                    ? pointReceivedAtMs.get(i) : point.optLong(3, 0L);
            renderTail = new RenderPointNode(new TrackPoint(
                    point.optDouble(1), point.optDouble(0), point.optDouble(2),
                    point.optLong(3), received), renderTail);
        }
        WaypointCount = Math.max(0, WaypointCount - removed);
    }

    private void noteShadowIfAnotherTabletOwns(@NonNull CtDroneSpec spec) {
        String remoteId = spec.getRemoteId();
        if (remoteId == null || remoteId.isEmpty()) return;
        try {
            PeerCoordinator peers = R2cRuntimeRegistry.getDefaultRuntime().getPeerCoordinator();
            if (!peers.isLocalOwner(remoteId) && peers.hasActivePeerOwner(remoteId)) {
                recordedAsShadow = true;
            }
        } catch (Exception ignored) { }
    }

    private static boolean isLocalFlightOwnerForUpload(@NonNull String remoteId) {
        if (remoteId.isEmpty()) return false;
        try {
            PeerCoordinator peers = R2cRuntimeRegistry.getDefaultRuntime().getPeerCoordinator();
            return peers.isLocalOwner(remoteId) || !peers.hasActivePeerOwner(remoteId);
        } catch (Exception error) {
            return true;
        }
    }

    @NonNull
    private static synchronized ExecutorService GetTrackArchiveExecutorPool() {
        if (TrackArchiveExecutorPool == null ||
                TrackArchiveExecutorPool.isShutdown() ||
                TrackArchiveExecutorPool.isTerminated()) {
            TrackArchiveExecutorPool = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "r2c-track-archive");
                thread.setDaemon(true);
                return thread;
            });
        }
        return TrackArchiveExecutorPool;
    }

    private static void QueueTrackArchive(@NonNull WaypointTrack track) {
        GetTrackArchiveExecutorPool().submit(track::archive);
    }

    private void absorb(@NonNull WaypointTrack other, @NonNull CtDroneSpec updatedDroneSpec) {
        this.droneSpec = updatedDroneSpec;
        for (int i = 0; i < other.coordinates.length(); i++) {
            Object point = other.coordinates.opt(i);
            if (point != null) {
                this.coordinates.put(point);
                this.pointReceivedAtMs.add(i < other.pointReceivedAtMs.size() ? other.pointReceivedAtMs.get(i) : null);
                this.pointDroneClock.add(i < other.pointDroneClock.size() ? other.pointDroneClock.get(i) : Boolean.TRUE);
                this.pointSources.add(i < other.pointSources.size() ? other.pointSources.get(i) : "rid");
                if (point instanceof JSONArray) try {
                    JSONArray coordinate = (JSONArray) point;
                    Long received = i < other.pointReceivedAtMs.size() ? other.pointReceivedAtMs.get(i) : null;
                    long timestamp = coordinate.getLong(3);
                    renderTail = new RenderPointNode(new TrackPoint(coordinate.getDouble(1),
                            coordinate.getDouble(0), coordinate.getDouble(2), timestamp,
                            received != null ? received : timestamp), renderTail);
                } catch (JSONException error) {
                    CTWarn(TAG, "Skipping malformed merged render point", error);
                }
            }
        }
    }

    @NonNull
    private synchronized List<ClueBindingPoint> getBindingPointsSnapshot() {
        ArrayList<ClueBindingPoint> snapshot = new ArrayList<>(coordinates.length());
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray point = coordinates.optJSONArray(i);
            if (point == null || point.length() < 4) continue;
            try {
                double lng = point.getDouble(0);
                double lat = point.getDouble(1);
                double ele = point.getDouble(2);
                long timestampMsec = point.getLong(3);
                Long received = i < pointReceivedAtMs.size() ? pointReceivedAtMs.get(i) : null;
                boolean droneClock = i >= pointDroneClock.size() || pointDroneClock.get(i);
                String source = i < pointSources.size() ? pointSources.get(i) : "rid";
                snapshot.add(new ClueBindingPoint(timestampMsec, received, lat, lng,
                        ele > -999.0 ? Double.valueOf(ele) : null, source, droneClock));
            } catch (JSONException e) {
                CTWarn(TAG, String.format(Locale.US,
                        "getBindingPointsSnapshot(%s): skipping malformed point at index %d", trackLabel, i), e);
            }
        }
        return snapshot;
    }

    @NonNull
    private List<TrackPoint> getTrackPointsSnapshot() {
        RenderPointNode tail = renderTail;
        ArrayList<TrackPoint> snapshot = new ArrayList<>();
        for (RenderPointNode node = tail; node != null; node = node.previous) snapshot.add(node.point);
        java.util.Collections.reverse(snapshot);
        return snapshot;
    }

    @Nullable
    public JSONObject getGeoJson() {
        boolean useArchiveSnapshot = archivePrepared;
        JSONObject joTop = new JSONObject();
        try {
            JSONObject jo = new JSONObject();
            jo.put("type", "Feature");

            JSONObject r2cProp = new JSONObject();
            r2cProp.put("owner", useArchiveSnapshot ? archivedOwner : droneSpec.getOwner());
            r2cProp.put("flightReadiness", new JSONObject(useArchiveSnapshot ? archivedFlightReadinessJson : droneSpec.getFlightReadinessJson()));
            r2cProp.put("model", useArchiveSnapshot ? archivedModel : droneSpec.getModel());
            r2cProp.put("org", useArchiveSnapshot ? archivedOrg : droneSpec.getOrg());
            r2cProp.put("rid", useArchiveSnapshot ? archivedRemoteId : droneSpec.getRemoteId());
            r2cProp.put("mid", useArchiveSnapshot ? archivedMappedId : droneSpec.getMappedId());
            r2cProp.put("local_archive_only",
                    useArchiveSnapshot ? archivedLocalOnly : droneSpec.isLocalArchiveOnly());
            if (useArchiveSnapshot) {
                r2cProp.put("tracker_upload_authorized", archivedTrackerUploadAuthorized);
                if (archivedTrackerClaimPending) r2cProp.put("tracker_claim_pending", true);
            }
            r2cProp.put("incident", incident);
            r2cProp.put("op_period", opPeriod);
            r2cProp.put("map_id", mapId);
            r2cProp.put("tz_str", ZoneId.systemDefault().getId());
            r2cProp.put("device_name", R2CActivity.MyDeviceName);
            r2cProp.put("BUILD_VERSION", BuildConfig.BUILD_VERSION);
            r2cProp.put("BUILD_TIME", BuildConfig.BUILD_TIME);
            r2cProp.put("distance_mi",
                    String.format(Locale.US, "%.4f",
                            (float)(useArchiveSnapshot
                                    ? archivedDistanceInFeet
                                    : droneSpec.getDistanceInFeet()) / 5280.0));

            JSONObject joProp = new JSONObject();
            joProp.put("title", trackLabel);
            joProp.put("start_time", startTimeStr);
            joProp.put("r2c_prop", r2cProp);
            joProp.put("r2c_flight_id", deferredPublicationId);
            jo.put("properties", joProp);

            JSONObject joGeometry = new JSONObject();
            joGeometry.put("type", "LineString");
            JSONArray archivedCoordinates = coordinatesForArchive();
            FlightArchivePointTiming.write(joProp, pointReceivedAtMs, pointDroneClock, archivedCoordinates.length());
            joGeometry.put("coordinates", archivedCoordinates);
            jo.put("geometry", joGeometry);

            JSONArray jaFeatures = new JSONArray();
            jaFeatures.put(jo);

            joTop.put("type", "FeatureCollection");
            joTop.put("features", jaFeatures);
        } catch (Exception e) {
            CTError(TAG, "getGeoJson() raised", e);
            return null;
        }
        return joTop;
    }

    @NonNull
    private static HashSet<String> ReadFilenamesFromDocFile(@NonNull DocumentFile reportedFilepath) {
        HashSet<String> filenames = new HashSet<>();
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) {
            CTError(TAG, "ReadFilenamesFromDocFile(): context not defined.");
            return filenames;
        }
        try {
            Uri uri = reportedFilepath.getUri();
            InputStream is = ctxt.getContentResolver().openInputStream(uri);
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader reader = new BufferedReader(isr);
            String filename;
            while ((filename = reader.readLine()) != null) filenames.add(filename);
            reader.close();
        } catch (Exception e) {
            CTError(TAG, "ReadFilenamesFromDocFile() raised.", e);
        }
        return filenames;
    }

    @Nullable
    private static JSONObject ReadGeoJson(DocumentFile waypointFile) throws IOException, JSONException {
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) {
            CTError(TAG, "ReadGeoJson(): context not defined.");
            return null;
        }

        JSONObject waypointTrack = null;
        Uri uri = waypointFile.getUri();
        StringBuilder builder = new StringBuilder();
        InputStream is = ctxt.getContentResolver().openInputStream(uri);
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader reader = new BufferedReader(isr);
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        reader.close();
        isr.close();
        if (null != is) is.close();
        // Make sure file contains a self-consistent JSON structure:
        waypointTrack = new JSONObject(builder.toString());
        return waypointTrack;
    }

    static void ReportStatsForFile(@NonNull DocumentFile reportedFilepath, @NonNull String filename) {
        Uri uri = reportedFilepath.getUri();
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) {
            CTError(TAG, "ReportStatsForFile(): missing ctxt");
            return;
        }
        ContentResolver resolver = ctxt.getContentResolver();
        if (null == resolver) {
            CTError(TAG, "ReportStatsForFile(): missing required resolver()");
            return;
        }
        try {
            OutputStream os = resolver.openOutputStream(uri, "wa");
            if (null != os) {
                PrintWriter writer = new PrintWriter(os);
                writer.println(filename);
                writer.flush();
                writer.close();
                os.close();
                org.ncssar.rid2caltopo.app.FlightStorage.documentChanged(ctxt, reportedFilepath);
                CTDebug(TAG, "ReportStatsForFile() logged for " + filename);
            }
        } catch (Exception e) {
            CTError(TAG, "ReportStatsForFile() raised", e);
        }
    }

    private static boolean IsTransientStatsResponse(int responseCode) {
        return responseCode == 408 || responseCode == 429 || responseCode >= 500;
    }

    public static boolean ShouldMarkGeoJsonStatsReportedForResponse(int responseCode) {
        // Authentication and upgrade failures can recover without changing the archive.
        return responseCode != GEOJSON_STATS_UPLOAD_SKIPPED &&
                responseCode != 401 && responseCode != 403 && responseCode != 426 &&
                responseCode != 499 &&
                !IsTransientStatsResponse(responseCode);
    }

    private static int PublishGeoJsonStatsWithRetry(@NonNull String geoJsonString, @NonNull String context) {
        if (!ShouldPublishGeoJsonStatsForTracker(geoJsonString, context)) {
            return GEOJSON_STATS_UPLOAD_SKIPPED;
        }
        int responseCode = 503;
        for (int attempt = 1; attempt <= MAX_GEOJSON_STATS_RETRIES; attempt++) {
            if (CaltopoClient.IsExitRequested()) {
                return 499;
            }
            Future<Integer> publishFuture = null;
            try {
                publishFuture = CaltopoClient.PublishGeoJsonStats(
                        R2cRuntimeRegistry.getDefaultRuntime().getTrackerPublisher(),
                        geoJsonString);
                responseCode = publishFuture.get(GEOJSON_PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                if (publishFuture != null) publishFuture.cancel(true);
                responseCode = 408;
                CTWarn(TAG, context + " publish timeout", e);
            } catch (Exception e) {
                responseCode = 503;
                CTWarn(TAG, context + " publish failed", e);
            }
            if (!IsTransientStatsResponse(responseCode)) {
                return responseCode;
            }
            if (attempt >= MAX_GEOJSON_STATS_RETRIES) {
                break;
            }
            if (CaltopoClient.IsExitRequested()) {
                return 499;
            }
            long delayMs = GEOJSON_RETRY_BASE_DELAY_MS * attempt;
            CTWarn(TAG, String.format(Locale.US,
                    "%s transient response %d (attempt %d/%d), retrying in %.3f seconds",
                    context, responseCode, attempt, MAX_GEOJSON_STATS_RETRIES, delayMs / 1000.0));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CTWarn(TAG, context + " retry sleep interrupted");
                break;
            }
        }
        return responseCode;
    }

    @NonNull
    private static synchronized ExecutorService GetArchiveReportExecutorPool() {
        if (ArchiveReportExecutorPool == null ||
                ArchiveReportExecutorPool.isShutdown() ||
                ArchiveReportExecutorPool.isTerminated()) {
            ArchiveReportExecutorPool = Executors.newSingleThreadExecutor();
        }
        return ArchiveReportExecutorPool;
    }

    @Nullable
    private static ExecutorService ArchiveReportExecutorPool = null;

    @NonNull
    static Future<Integer> PublishGeoJsonStatsWithRetryAsyncForTesting(
            @NonNull String geoJsonString,
            @NonNull String context
    ) {
        return QueueGeoJsonStatsReport(geoJsonString, context, null);
    }

    @NonNull
    private static Future<Integer> QueueGeoJsonStatsReport(
            @NonNull String geoJsonString,
            @NonNull String context,
            @Nullable Runnable onReported
    ) {
        return GetArchiveReportExecutorPool().submit(() -> {
            int responseCode = PublishGeoJsonStatsWithRetry(geoJsonString, context);
            if (responseCode == GEOJSON_STATS_UPLOAD_SKIPPED) {
                CTDebug(TAG, context + ": tracker upload skipped locally");
            } else {
                CTDebug(TAG, String.format(Locale.US,
                        "%s: server returned %d", context, responseCode));
            }
            if (ShouldMarkGeoJsonStatsReportedForResponse(responseCode) && onReported != null) {
                onReported.run();
            }
            return responseCode;
        });
    }

    @NonNull
    private static String propertiesFlightId(@Nullable JSONObject waypointTrack) {
        if (waypointTrack == null) return "";
        JSONArray features = waypointTrack.optJSONArray("features");
        JSONObject feature = features == null ? null : features.optJSONObject(0);
        JSONObject properties = feature == null ? null : feature.optJSONObject("properties");
        return properties == null ? "" : properties.optString("r2c_flight_id", "");
    }

    @Nullable
    private static JSONObject GetR2cProp(@Nullable JSONObject waypointTrack) {
        if (waypointTrack == null) return null;
        JSONArray features = waypointTrack.optJSONArray("features");
        if (features == null || features.length() <= 0) return null;
        JSONObject feature = features.optJSONObject(0);
        if (feature == null) return null;
        JSONObject properties = feature.optJSONObject("properties");
        if (properties == null) return null;
        return properties.optJSONObject("r2c_prop");
    }

    @NonNull
    private static String NormalizeTrackerOrg(@Nullable String org) {
        if (org == null) return "";
        return org.trim().toUpperCase(Locale.US);
    }

    private enum TrackerUploadEligibility {
        ELIGIBLE,
        PERMANENTLY_INELIGIBLE,
        RETRY_LATER
    }

    @NonNull
    private static TrackerUploadEligibility GetTrackerUploadEligibility(
            @Nullable JSONObject waypointTrack
    ) {
        JSONObject r2cProp = GetR2cProp(waypointTrack);
        if (r2cProp == null) return TrackerUploadEligibility.PERMANENTLY_INELIGIBLE;
        if (r2cProp.optBoolean("local_archive_only", false)) {
            return TrackerUploadEligibility.PERMANENTLY_INELIGIBLE;
        }
        String flightId = propertiesFlightId(waypointTrack);
        boolean released = OwnershipReviewReleases.allowsTrackerUpload(flightId);
        if (r2cProp.optBoolean("tracker_claim_pending", false) && !released) {
            return TrackerUploadEligibility.RETRY_LATER;
        }
        String droneOrg = NormalizeTrackerOrg(r2cProp.optString("org", ""));
        String remoteId = r2cProp.optString("rid", "");
        if (!CaltopoClient.IsTrackerUploadIdentityForConfiguredOrg(remoteId, droneOrg)) {
            return TrackerUploadEligibility.RETRY_LATER;
        }
        if (r2cProp.has("tracker_upload_authorized") && !released) {
            return r2cProp.optBoolean("tracker_upload_authorized", false)
                    ? TrackerUploadEligibility.ELIGIBLE
                    : TrackerUploadEligibility.PERMANENTLY_INELIGIBLE;
        }
        if (CaltopoClient.IsKnownTeamDroneForTrackerUpload(remoteId, droneOrg) ||
                CaltopoClient.IsPersistedTeamDroneForTrackerUpload(remoteId, droneOrg)) {
            return TrackerUploadEligibility.ELIGIBLE;
        }
        String mappedId = r2cProp.optString("mid", "").trim();
        if (!mappedId.isEmpty() && !mappedId.equalsIgnoreCase(remoteId.trim())) {
            return TrackerUploadEligibility.ELIGIBLE;
        }
        return TrackerUploadEligibility.RETRY_LATER;
    }

    public static boolean ShouldPublishGeoJsonStatsForTracker(@Nullable JSONObject waypointTrack) {
        return GetTrackerUploadEligibility(waypointTrack) == TrackerUploadEligibility.ELIGIBLE;
    }

    private static boolean ShouldPublishGeoJsonStatsForTracker(@NonNull String geoJsonString,
                                                               @NonNull String context) {
        try {
            JSONObject waypointTrack = new JSONObject(geoJsonString);
            TrackerUploadEligibility eligibility = GetTrackerUploadEligibility(waypointTrack);
            if (eligibility != TrackerUploadEligibility.ELIGIBLE) {
                JSONObject r2cProp = GetR2cProp(waypointTrack);
                String droneOrg = NormalizeTrackerOrg(r2cProp != null ? r2cProp.optString("org", "") : "");
                String trackerOrg = NormalizeTrackerOrg(CaltopoClient.GetTrackerUploadOrgName());
                CTInfo(TAG, String.format(Locale.US,
                        "%s skipping tracker upload (%s): drone org '%s', remoteId '%s', tracker upload org '%s'",
                        context,
                        eligibility,
                        droneOrg,
                        r2cProp != null ? r2cProp.optString("rid", "") : "",
                        trackerOrg));
            }
            return eligibility == TrackerUploadEligibility.ELIGIBLE;
        } catch (JSONException e) {
            CTWarn(TAG, context + " skipping tracker upload: malformed geojson", e);
            return false;
        }
    }

    private static boolean IsLocalArchiveOnly(@Nullable JSONObject waypointTrack) {
        if (waypointTrack == null) return false;
        JSONArray features = waypointTrack.optJSONArray("features");
        if (features == null || features.length() <= 0) return false;
        JSONObject feature = features.optJSONObject(0);
        if (feature == null) return false;
        JSONObject properties = feature.optJSONObject("properties");
        if (properties == null) return false;
        JSONObject r2cProp = properties.optJSONObject("r2c_prop");
        return r2cProp != null && r2cProp.optBoolean("local_archive_only", false);
    }

    @Nullable
    private static String FlightIdFromGeoJson(@Nullable JSONObject waypointTrack) {
        if (waypointTrack == null) return null;
        JSONArray features = waypointTrack.optJSONArray("features");
        if (features == null || features.length() <= 0) return null;
        JSONObject feature = features.optJSONObject(0);
        if (feature == null) return null;
        JSONObject properties = feature.optJSONObject("properties");
        if (properties == null) return null;
        String id = properties.optString("r2c_flight_id", "").trim();
        return id.isEmpty() ? null : id;
    }

    /** Ledger decline / suppress / local-archive / published blocks tracker stats upload. */
    private static boolean IsPublicationBlocked(@Nullable JSONObject waypointTrack) {
        if (IsLocalArchiveOnly(waypointTrack)) return true;
        String flightId = FlightIdFromGeoJson(waypointTrack);
        return flightId != null && FlightPublicationLedger.blocksPublication(flightId);
    }

    public static class ResubmitRecentTrackStatsResult {
        public final int daysRequested;
        public int directoriesChecked;
        public int reportFilesDeleted;
        public int filesConsidered;
        public int filesUploaded;
        public int filesMarkedReported;
        public int filesSkippedActive;
        public int filesSkippedIneligible;
        public int filesFailed;

        ResubmitRecentTrackStatsResult(int daysRequested) {
            this.daysRequested = daysRequested;
        }

        @NonNull
        public String summary() {
            return String.format(Locale.US,
                    "Resubmit checked %d recent archive folder(s), uploaded %d track(s), rebuilt %d report entry(s), skipped %d active, skipped %d ineligible, failed %d.",
                    directoriesChecked,
                    filesUploaded,
                    filesMarkedReported,
                    filesSkippedActive,
                    filesSkippedIneligible,
                    filesFailed);
        }
    }

    static boolean IsTrackDirectoryWithinRecentDays(@Nullable String directoryName,
                                                    int daysBack,
                                                    @NonNull LocalDate today) {
        if (directoryName == null || !directoryName.startsWith("tracks-")) return false;
        if (daysBack < 1) daysBack = 1;
        String datePart = directoryName.substring("tracks-".length());
        try {
            LocalDate directoryDate = LocalDate.parse(datePart, TRACK_DIRECTORY_DATE_FORMATTER);
            LocalDate oldestIncluded = today.minusDays(daysBack - 1L);
            return !directoryDate.isBefore(oldestIncluded) && !directoryDate.isAfter(today);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static boolean IsTrackFileActive(long fileLastModifiedMillis, long nowMillis) {
        return fileLastModifiedMillis > 0 &&
                fileLastModifiedMillis + ACTIVE_TRACK_GRACE_MS > nowMillis;
    }

    @NonNull
    public static ResubmitRecentTrackStatsResult ResubmitRecentTrackStatsToTracker(int daysBack) {
        if (daysBack < 1) daysBack = 1;
        ResubmitRecentTrackStatsResult result = new ResubmitRecentTrackStatsResult(daysBack);
        DocumentFile archiveDir = CaltopoClient.GetArchiveDir();
        if (archiveDir == null || !archiveDir.isDirectory()) {
            CTDebug(TAG, "ResubmitRecentTrackStatsToTracker(): no archiveDir");
            return result;
        }

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        for (DocumentFile trackDir : archiveDir.listFiles()) {
            String trackDirName = trackDir.getName();
            if (!trackDir.isDirectory()) continue;
            if (!IsTrackDirectoryWithinRecentDays(trackDirName, daysBack, today)) continue;
            result.directoriesChecked++;
            CTInfo(TAG, "ResubmitRecentTrackStatsToTracker() Checking " + trackDirName);
            DocumentFile reportedFilepath = DocumentFileCompat.findFileIncludingLegacyDuplicateExtension(
                    trackDir, "text/plain", ReportedFilenames);
            if (reportedFilepath != null && reportedFilepath.isFile()) {
                if (reportedFilepath.delete()) result.reportFilesDeleted++;
            }
            reportedFilepath = DocumentFileCompat.createFileWithExactName(
                    trackDir, "text/plain", ReportedFilenames);
            if (reportedFilepath == null) {
                CTError(TAG, String.format(Locale.US, "Couldn't create '%s' in '%s'",
                        ReportedFilenames, trackDir.getUri()));
                result.filesFailed++;
                continue;
            }
            ResubmitTrackDirectory(trackDir, reportedFilepath, result);
        }
        CTDebug(TAG, "ResubmitRecentTrackStatsToTracker(): " + result.summary());
        return result;
    }

    private static void ResubmitTrackDirectory(@NonNull DocumentFile trackDir,
                                               @NonNull DocumentFile reportedFilepath,
                                               @NonNull ResubmitRecentTrackStatsResult result) {
        long nowMs = System.currentTimeMillis();
        for (DocumentFile file : trackDir.listFiles()) {
            String filename = file.getName();
            if (filename == null || !filename.endsWith(".json")) continue;
            result.filesConsidered++;
            if (file.lastModified() > 0 && file.lastModified() + ACTIVE_TRACK_GRACE_MS > nowMs) {
                CTInfo(TAG, "ResubmitRecentTrackStatsToTracker() skipping active file " + filename);
                result.filesSkippedActive++;
                continue;
            }
            JSONObject waypointTrack;
            try {
                waypointTrack = ReadGeoJson(file);
            } catch (Exception e) {
                CTWarn(TAG, "Not able to read " + filename, e);
                ReportStatsForFile(reportedFilepath, filename);
                result.filesMarkedReported++;
                continue;
            }
            if (waypointTrack == null) continue;
            if (IsPublicationBlocked(waypointTrack)) {
                CTInfo(TAG, "ResubmitRecentTrackStatsToTracker() skipping publication-blocked file " + filename);
                ReportStatsForFile(reportedFilepath, filename);
                result.filesMarkedReported++;
                result.filesSkippedIneligible++;
                continue;
            }
            TrackerUploadEligibility eligibility = GetTrackerUploadEligibility(waypointTrack);
            if (eligibility != TrackerUploadEligibility.ELIGIBLE) {
                CTInfo(TAG, "ResubmitRecentTrackStatsToTracker() skipping ineligible tracker file " + filename);
                if (eligibility == TrackerUploadEligibility.PERMANENTLY_INELIGIBLE) {
                    ReportStatsForFile(reportedFilepath, filename);
                    result.filesMarkedReported++;
                }
                result.filesSkippedIneligible++;
                continue;
            }
            int responseCode = PublishGeoJsonStatsWithRetry(
                    waypointTrack.toString(),
                    String.format(Locale.US, "ResubmitRecentTrackStatsToTracker(%s)", filename));
            if (ShouldMarkGeoJsonStatsReportedForResponse(responseCode)) {
                ReportStatsForFile(reportedFilepath, filename);
                result.filesMarkedReported++;
                if (responseCode >= 200 && responseCode < 300) {
                    result.filesUploaded++;
                }
            } else {
                result.filesFailed++;
            }
        }
    }


    /* BgPollUnreportedTracks()
     *  Called from CaltopoClient by one of it's background threads if it's been
     *  configured with what looks like a reasonable r2c-tracker server.  This
     *  procedure walks thru all the daily ArchiveDirs in the filesystem and
     *  makes sure each of the Waypoint Tracks has been reported and archived
     *  via CaltopoClient.PublishGeoJsonStats().   The first time
     *  PublishGeoJsonStats() raises, this thread must log the error and
     *  assume that the r2c-tracker server is down or il-configured.
     *
     * Compare the date on the ReportedFilenames file to make sure it's
     *
     */
    public static void BgPollUnreportedTracks() {
        try {
            WaypointTrackJournal.recoverUnfinished();
        } catch (Exception error) {
            CTError(TAG, "BgPollUnreportedTracks(): journal recovery failed", error);
        }
        int consecutiveFails = 0;
        DocumentFile archiveDir = CaltopoClient.GetArchiveDir();
        if (null == archiveDir || !archiveDir.isDirectory()) {
            CTDebug(TAG, "BgPollUnreportedTracks(): no archiveDir");
            return;
        }
        // archiveDir is the parent directory of the daily track log directories
        for (DocumentFile trackDir : archiveDir.listFiles()) {
            if (!trackDir.isDirectory()) continue;
            if (trackDir.getName().equals("cache")) continue;
            String storageOwner = "archive-upload-" + java.util.UUID.randomUUID();
            org.ncssar.rid2caltopo.app.FlightStorage.protect(trackDir.getName(), storageOwner);
            try {
                CTInfo(TAG, "BgPollUnreportedTracks() Checking " + trackDir.getName());
                Handler mainThreadHandler = new Handler(Looper.getMainLooper());
                DocumentFile reportedFilepath = DocumentFileCompat.findFileIncludingLegacyDuplicateExtension(
                        trackDir, "text/plain", ReportedFilenames);
                HashSet<String> reportedFilenames = null;
                if (null == reportedFilepath) {
                    reportedFilepath = DocumentFileCompat.createFileWithExactName(
                            trackDir, "text/plain", ReportedFilenames);
                    if (null == reportedFilepath) {
                        CTError(TAG, String.format(Locale.US, "Couldn't create '%s' in '%s'",
                                ReportedFilenames, trackDir.getUri()));
                        continue;
                    }
                } else {
                    reportedFilenames = ReadFilenamesFromDocFile(reportedFilepath);
                }
                for (DocumentFile file : trackDir.listFiles()) {
                    String filename = file.getName();
                    DocumentFile finalReportedFilepath = reportedFilepath;

                    if (null == filename || !filename.endsWith(".json")) {
                        CTInfo(TAG, "BgPollUnreportedTracks() skipping non geo-json file " + filename);
                        continue;
                    }
                    if (IsTrackFileActive(file.lastModified(), System.currentTimeMillis())) {
                        CTInfo(TAG, "BgPollUnreportedTracks() skipping file that appears to still be active: " + filename);
                        continue;
                    }
                    CTInfo(TAG, "BgPollUnreportedTracks() found geo-json file " + filename);
                    if ((null != reportedFilenames) && reportedFilenames.contains(filename)) {
                        CTInfo(TAG, "BgPollUnreportedTracks() skipping already reported file " + filename);
                        continue;
                    }
                    // then filename should contain a geo-json file associated with an unreported track.
                    JSONObject waypointTrack = null;
                    try {
                        waypointTrack = ReadGeoJson(file);
                    } catch (Exception e) {
                        CTWarn(TAG, "Not able to read " + filename, e);
                        // Ignore unreadable/empty files during future launches.
                        ReportStatsForFile(finalReportedFilepath, filename);
                        continue;
                    }
                    if (null == waypointTrack) continue;
                    if (IsPublicationBlocked(waypointTrack)) {
                        CTInfo(TAG, "BgPollUnreportedTracks() skipping publication-blocked file " + filename);
                        ReportStatsForFile(finalReportedFilepath, filename);
                        continue;
                    }
                    TrackerUploadEligibility eligibility = GetTrackerUploadEligibility(waypointTrack);
                    if (eligibility != TrackerUploadEligibility.ELIGIBLE) {
                        CTInfo(TAG, "BgPollUnreportedTracks() skipping ineligible tracker file " + filename);
                        if (eligibility == TrackerUploadEligibility.PERMANENTLY_INELIGIBLE) {
                            ReportStatsForFile(finalReportedFilepath, filename);
                        }
                        continue;
                    }
                    String geoJsonString = waypointTrack.toString();
                    CTDebug(TAG, "BgPollUnreportedTracks() publishing " + filename);
                    int responseCode = PublishGeoJsonStatsWithRetry(
                            geoJsonString,
                            String.format(Locale.US, "BgPollUnreportedTracks(%s)", filename));
                    if (!ShouldMarkGeoJsonStatsReportedForResponse(responseCode)) {
                        consecutiveFails++;
                        if (consecutiveFails > 2) {
                            CTDebug(TAG, "BgPollUnreportedTracks(): suspending ops due to 2 consecutive failures to publish");
                            break;
                        }
                    } else {
                        consecutiveFails = 0;
                        mainThreadHandler.post(() -> ReportStatsForFile(finalReportedFilepath, filename));
                    }

                    // Take a breather - give main thread and network a chance to do something.
                    try {
                        Thread.sleep(500);
                    } catch (Exception e) {
                        Thread.currentThread().interrupt(); // restore interrupted status
                    }
                }
            } finally {
                org.ncssar.rid2caltopo.app.FlightStorage.release(storageOwner);
            }
        }
        CTDebug(TAG, "BgPollUnreportedTracks(): Finished processing archiveDir. Terminating.");
    }


    // To keep track of the tracks in the current directory that have been archived, we
    // append the filename to a ReportedFilenames in the same directory.  This way, if the
    // app is unable to report tracks for any reason (i.e. no network or the server isn't
    // running or the app gets killed before it can report all tracks, then follow-up with
    // BgPollUnreportedTracks() to handle any unreported tracks.
    private void statsReported() {
        DocumentFile todaysArchiveDir = GetTodaysTrackDir();
        DocumentFile reportedFilepath = DocumentFileCompat.findFileIncludingLegacyDuplicateExtension(
                todaysArchiveDir, "text/plain", ReportedFilenames);
        if (null == reportedFilepath)
            reportedFilepath = DocumentFileCompat.createFileWithExactName(
                    todaysArchiveDir, "text/plain", ReportedFilenames);
        if (null == reportedFilepath) {
            CTError(TAG, String.format(Locale.US,
                    "statsReported(%s): Not able to create %s in %s", fileName,
                    ReportedFilenames, todaysArchiveDir.getUri()));
            return;
        }
        ReportStatsForFile(reportedFilepath, fileName);
    }

    boolean shouldRecordArchive() {
        prepareForArchive();
        // Don't Publish, unanswered, and confirmed flights all retain a local archive.
        // Publication (CalTopo / tracker) remains gated by confirmation + local_archive_only.
        return true;
    }

    public void archive() {
        prepareForArchive();
        if (!keepsArchiveFile()) {
            CTDebug(TAG, "archive(): shadow points kept in memory only " + archivedRemoteId);
            WaypointTrackJournal.discardUnfinished(deferredPublicationId);
            return;
        }
        long numCoords = coordinatesForArchive().length();
        if (numCoords <= 0) {
            CTDebug(TAG, "archive(): no waypoints.");
            retireUnpublishedJournal(FlightPublicationLedger.Disposition.SUPPRESSED);
            return;
        }
        if (!archivedFlightConfirmed) {
            // Don't Publish or unanswered: durable local archive, never awaiting-map / tracker / CalTopo.
            boolean declined = archivedLocalOnly;
            CTDebug(TAG, declined
                    ? "archive(): declined flight retained locally only " + archivedRemoteId
                    : "archive(): unanswered flight retained locally only " + archivedRemoteId);
            FlightPublicationLedger.remember(
                    deferredPublicationId,
                    declined
                            ? FlightPublicationLedger.Disposition.DECLINED
                            : FlightPublicationLedger.Disposition.SUPPRESSED);
            // Force local-only metadata so tracker / CalTopo paths stay closed.
            archivedLocalOnly = true;
            archiveRecorded();
            return;
        }
        double first = coordinates.optJSONArray(0).optDouble(3, Double.NaN);
        double last = coordinates.optJSONArray(coordinates.length() - 1).optDouble(3, Double.NaN);
        if (!ShortFlightRecording.request(archivedMappedId.isEmpty() ? archivedRemoteId : archivedMappedId,
                (last - first) / 1000.0, archivedDistanceInFeet * 0.3048,
                () -> GetTrackArchiveExecutorPool().submit(this::archiveRecorded),
                () -> GetTrackArchiveExecutorPool().submit(() -> {
                    // Short-flight "No" arrives on the Compose click thread; storage stays off Main.
                    AwaitingMapFlights.discard(deferredPublicationId);
                    retireUnpublishedJournal(FlightPublicationLedger.Disposition.SUPPRESSED);
                }))) {
            archiveRecorded();
        }
    }

    private void retireUnpublishedJournal(@NonNull FlightPublicationLedger.Disposition disposition) {
        FlightPublicationLedger.remember(deferredPublicationId, disposition);
        AwaitingMapFlights.discard(deferredPublicationId);
        WaypointTrackJournal.markFinishedAndRemove(deferredPublicationId);
    }

    private void archiveRecorded() {
        if (archivedFlightConfirmed && !archivedLocalOnly) {
            recordPublicationIntent(true);
        }
        long numCoords = coordinates.length();
        JSONObject joTop = getGeoJson();
        if (null == joTop) return;
        // Link matching day-folder MediaMTX / catalog video into the local record for every
        // retained flight. CalTopo description updates stay on the publish path only.
        attachLocalCapturedVideo(joTop);
        try {
            Context ctxt = R2CApplication.getAppCtxt();
            DocumentFile todaysArchiveDir = GetTodaysTrackDir();
            if (ctxt == null || todaysArchiveDir == null) {
                CTError(TAG, "archive(): missing context or archive folder.");
                return;
            }
            if (fileName == null) fileName = OperatorArchiveFilename.track(archiveAircraftID(), archiveTimestampMsec());
            String geoJsonString = joTop.toString();
            // GeoJSON then the flight's backup KMZ (track plus every clue and local marker it owns),
            // each written crash-safely; the KMZ is written with or without clues.
            if (!FlightArchiveStore.writeFlight(todaysArchiveDir, fileName, geoJsonString, trackLabel,
                    archiveAircraftID(), deferredPublicationId, getBindingPointsSnapshot())) {
                CTError(TAG, "archive(): unable to write " + fileName);
                return;
            }
            WaypointTrackJournal.removeNow(deferredPublicationId);
            if (archivedLocalOnly) {
                FlightPublicationLedger.remember(
                        deferredPublicationId, FlightPublicationLedger.Disposition.LOCAL_ARCHIVED);
            }
            dataFilepath = todaysArchiveDir.findFile(fileName);
            CTDebug(TAG, String.format(Locale.US, "archive(): wrote %d coordinates to %s", numCoords, fileName));
            CTDebug(TAG, String.format(Locale.US, "archive(%s): publish decision=%s",
                    fileName, publishDecisionLabel()));
            if (archivedOkToLog && !archivedLocalOnly && archivedFlightConfirmed) {
                CTDebug(TAG, String.format(Locale.US,
                        "archive(%s): Publishing...", fileName));
                QueueGeoJsonStatsReport(
                        geoJsonString,
                        String.format(Locale.US, "archive(%s)", fileName),
                        this::statsReported);
            }
        } catch (Exception e) {
			CTError(TAG, String.format("archive(%s): raised.", fileName), e);
		}
	}

    /**
     * Writes the same captured-video short URL used for CalTopo into the local GeoJSON
     * {@code description}, matching by stream designators and flight start/end. Never publishes.
     */
    private void attachLocalCapturedVideo(@NonNull JSONObject geoJson) {
        Context ctxt = R2CApplication.getAppCtxt();
        if (ctxt == null) return;
        long startedAtMs = firstReceivedAtMs();
        long endedAtMs = lastReceivedAtMs();
        if (startedAtMs <= 0L || endedAtMs < startedAtMs) return;
        String mapped = archivedMappedId == null ? "" : archivedMappedId.trim();
        String remote = archivedRemoteId == null ? "" : archivedRemoteId.trim();
        String labelPrefix = trackLabel == null ? "" : trackLabel.split("_", 2)[0];
        try {
            org.ncssar.rid2caltopo.video.ManagedVideoSessionRecording recording =
                    org.ncssar.rid2caltopo.video.ManagedVideoSessionRecordingCatalog.findForTrack(
                            ctxt, startedAtMs, endedAtMs, mapped, remote, labelPrefix);
            if (recording == null) return;
            String videoUrl = TrackerTabletLink.recordingShortUrl(
                    CaltopoClient.GetTrackerCoordinationUrlPfx(),
                    R2CActivity.MyDeviceName,
                    recording.getSessionId());
            if (videoUrl == null || videoUrl.trim().isEmpty()) return;
            JSONArray features = geoJson.optJSONArray("features");
            JSONObject feature = features == null ? null : features.optJSONObject(0);
            JSONObject properties = feature == null ? null : feature.optJSONObject("properties");
            if (properties == null) return;
            properties.put("description", videoUrl.trim());
            CTDebug(TAG, "archive(): linked local captured video sessionId=" + recording.getSessionId() + " videoLink=true");
        } catch (Exception error) {
            CTWarn(TAG, "archive(): local video link skipped videoLink=false", error);
        }
    }

    private long firstReceivedAtMs() {
        for (int i = 0; i < pointReceivedAtMs.size(); i++) {
            Long value = pointReceivedAtMs.get(i);
            if (value != null && value > 0L) return value;
        }
        return startedAtMs;
    }

    private long lastReceivedAtMs() {
        for (int i = pointReceivedAtMs.size() - 1; i >= 0; i--) {
            Long value = pointReceivedAtMs.get(i);
            if (value != null && value > 0L) return value;
        }
        long stamp = archiveTimestampMsec();
        return stamp > 0L ? stamp : System.currentTimeMillis();
    }

    // returns true if waypoint added
	public void addWaypoint(double lat, double lng,
							long altInMeters, long timestampInMillisec) {
		addWaypoint(lat, lng, altInMeters, timestampInMillisec, System.currentTimeMillis(), "rid", true);
	}

	public synchronized void addWaypoint(double lat, double lng, long altInMeters, long timestampInMillisec,
							long receivedAtMs, @NonNull String source, boolean droneClock) {
		JSONArray ja = new JSONArray();
		ja.put(String.format(Locale.US, "%.6f", lng));
		ja.put(String.format(Locale.US, "%.6f", lat));
		ja.put(String.format(Locale.US, "%d", altInMeters));
		ja.put(String.format(Locale.US, "%d", timestampInMillisec));
		coordinates.put(ja);
		pointReceivedAtMs.add(receivedAtMs);
		noteShadowIfAnotherTabletOwns(droneSpec);
		pointDroneClock.add(droneClock);
		pointSources.add(source);
        renderTail = new RenderPointNode(new TrackPoint(lat, lng, altInMeters,
                timestampInMillisec, receivedAtMs), renderTail);
		WaypointCount++;
        WaypointTrackJournal.appendPoint(
                deferredPublicationId, lat, lng, altInMeters, timestampInMillisec,
                receivedAtMs, source, droneClock);
        noteOwnershipContext();
        if (droneSpec.isCurrentFlightConfirmed() && !droneSpec.isLocalArchiveOnly()) {
            recordPublicationIntent(false);
        }
	}

    @NonNull
    private String archiveAircraftID() {
        String remoteID = archivePrepared ? archivedRemoteId : droneSpec.getRemoteId();
        return remoteID == null || remoteID.isEmpty() ? trackLabel : remoteID;
    }

    boolean keepsArchiveFile() {
        return ownedFromMs > 0L || closedAsPriorOwner || !recordedAsShadow;
    }

    private JSONArray coordinatesForArchive() {
        if (ownedFromMs <= 0L) return coordinates;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray point = coordinates.optJSONArray(i);
            long stamp = coordinateTimestamp(point);
            if (point != null && (stamp <= 0L || stamp >= ownedFromMs)) kept.put(point);
        }
        return kept;
    }

    static long ownedFromMs(@NonNull CtDroneSpec spec) {
        String label = spec.trackLabel();
        WaypointTrack track = label == null ? null : TrackMap.get(label);
        return track == null ? 0L : track.ownedFromMs;
    }

    private long firstKeptTimestampLocked() {
        JSONArray first = coordinates.optJSONArray(0);
        return coordinateTimestamp(first);
    }

    private void noteOwnedStartLocked(long ownedStartMs) {
        if (ownedStartMs <= 0L) return;
        startTimeStr = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US).format(new Date(ownedStartMs));
    }

    /** True when a different tablet holds the lease, so this tablet must not record. */
    static boolean anotherTabletOwns(@NonNull CtDroneSpec spec) {
        String remoteId = spec.getRemoteId();
        if (remoteId == null || remoteId.isEmpty()) return false;
        try {
            PeerCoordinator peers = R2cRuntimeRegistry.getDefaultRuntime().getPeerCoordinator();
            return !peers.isLocalOwner(remoteId) && peers.hasActivePeerOwner(remoteId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private long archiveTimestampMsec() {
        long ownedStart = droneSpec.getStartMsecTimestamp();
        long firstPoint = firstKeptTimestampLocked();
        if (ownedStart > 0L && (firstPoint <= 0L || ownedStart <= firstPoint)) return ownedStart;
        if (firstPoint > 0L) return firstPoint;
        return System.currentTimeMillis();
    }

}
