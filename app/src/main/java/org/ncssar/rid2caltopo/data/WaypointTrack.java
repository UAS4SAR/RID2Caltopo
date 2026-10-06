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

        public TrackPoint(double lat, double lng, double ele, long timestampMsec) {
            this.lat = lat;
            this.lng = lng;
            this.ele = ele;
            this.timestampMsec = timestampMsec;
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
	private static HashMap<String, WaypointTrack> TrackMap = new HashMap<>();
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
    private final List<Boolean> pointDroneClock = new ArrayList<>();
    private final List<String> pointSources = new ArrayList<>();
    // Flight start (app clock, Unix ms) and the stable flight id derived from it and the drone's
    // remote id with the iOS scheme (RidFlightId), so both platforms name the same flight alike.
    private final long startedAtMs;
    private final String deferredPublicationId;
    private boolean archivePrepared = false;
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
		WaypointTrack track = TrackMap.get(trackLabel);
		if (null == track) {
			track = new WaypointTrack(trackLabel, droneSpec);
			TrackMap.put(trackLabel, track);
		}
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
        for (WaypointTrack track : new ArrayList<>(TrackMap.values())) {
            if (track.droneSpec.getRemoteId().equals(drone.getRemoteId())) track.recordPublicationIntent(false);
        }
    }

    private void recordPublicationIntent(boolean finished) {
        try {
            AwaitingMapFlights.record(deferredPublicationId, droneSpec.getRemoteId(), trackLabel, coordinates, finished);
        } catch (Exception error) { CTError(TAG, "Could not persist publication intent; local track recording continues", error); }
    }

    @NonNull
    public static List<TrackPoint> GetTrackPointsSnapshot(@NonNull CtDroneSpec droneSpec) {
        WaypointTrack track = TrackMap.get(droneSpec.trackLabel());
        if (track == null) return new ArrayList<>();
        return track.getTrackPointsSnapshot();
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

	public static void ArchiveTrack(@NonNull String trackLabel) {
		WaypointTrack track = TrackMap.remove(trackLabel);
		if (null != track) {
            track.prepareForArchive();
            QueueTrackArchive(track);
        }
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
        archivedFlightConfirmed = droneSpec.isCurrentFlightConfirmed() ||
                CaltopoClient.IsCurrentPeerDroneConfirmed(archivedRemoteId);
        archivedDistanceInFeet = droneSpec.getDistanceInFeet();
        archivedTrackerUploadAuthorized = !archivedLocalOnly &&
                CaltopoClient.IsKnownTeamDroneForTrackerUpload(
                        archivedRemoteId, archivedOrg);
        archivePrepared = true;
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
        ArrayList<TrackPoint> snapshot = new ArrayList<>(coordinates.length());
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray point = coordinates.optJSONArray(i);
            if (point == null || point.length() < 4) continue;
            try {
                double lng = point.getDouble(0);
                double lat = point.getDouble(1);
                double ele = point.getDouble(2);
                long timestampMsec = point.getLong(3);
                snapshot.add(new TrackPoint(lat, lng, ele, timestampMsec));
            } catch (JSONException e) {
                CTWarn(TAG, String.format(Locale.US,
                        "getTrackPointsSnapshot(%s): skipping malformed point at index %d",
                        trackLabel, i), e);
            }
        }
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
            // Optional per-point fields aligned with coordinates (older files omit them).
            JSONArray received = new JSONArray();
            JSONArray droneClock = new JSONArray();
            for (int i = 0; i < coordinates.length(); i++) {
                Long value = i < pointReceivedAtMs.size() ? pointReceivedAtMs.get(i) : null;
                received.put(value == null ? JSONObject.NULL : value);
                droneClock.put(i >= pointDroneClock.size() || pointDroneClock.get(i));
            }
            joProp.put("r2c_point_received_ms", received);
            joProp.put("r2c_point_drone_clock", droneClock);
            joProp.put("r2c_flight_id", deferredPublicationId);
            jo.put("properties", joProp);

            JSONObject joGeometry = new JSONObject();
            joGeometry.put("type", "LineString");
            joGeometry.put("coordinates", coordinates);
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
        String droneOrg = NormalizeTrackerOrg(r2cProp.optString("org", ""));
        String remoteId = r2cProp.optString("rid", "");
        if (!CaltopoClient.IsTrackerUploadIdentityForConfiguredOrg(remoteId, droneOrg)) {
            return TrackerUploadEligibility.RETRY_LATER;
        }
        if (r2cProp.has("tracker_upload_authorized")) {
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
            if (IsLocalArchiveOnly(waypointTrack)) {
                CTInfo(TAG, "ResubmitRecentTrackStatsToTracker() skipping local-archive-only file " + filename);
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
                    if (IsLocalArchiveOnly(waypointTrack)) {
                        CTInfo(TAG, "BgPollUnreportedTracks() skipping local-archive-only file " + filename);
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
        return archivedFlightConfirmed;
    }

    public void archive() {
        if (!shouldRecordArchive()) {
            CTDebug(TAG, "archive(): ignored unanswered/unconfirmed flight " + archivedRemoteId);
            return;
        }
        long numCoords = coordinates.length();
        if (numCoords <= 0) {
            CTDebug(TAG, "archive(): no waypoints.");
            return;
        }
        double first = coordinates.optJSONArray(0).optDouble(3, Double.NaN);
        double last = coordinates.optJSONArray(coordinates.length() - 1).optDouble(3, Double.NaN);
        if (!ShortFlightRecording.request(archivedMappedId.isEmpty() ? archivedRemoteId : archivedMappedId,
                (last - first) / 1000.0, archivedDistanceInFeet * 0.3048,
                () -> GetTrackArchiveExecutorPool().submit(this::archiveRecorded),
                () -> AwaitingMapFlights.discard(deferredPublicationId))) {
            archiveRecorded();
        }
    }

    private void archiveRecorded() {
        if (archivedFlightConfirmed && !archivedLocalOnly) {
            recordPublicationIntent(true);
        }
        long numCoords = coordinates.length();
        JSONObject joTop = getGeoJson();
        if (null == joTop) return;
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
            dataFilepath = todaysArchiveDir.findFile(fileName);
            CTDebug(TAG, String.format(Locale.US, "archive(): wrote %d coordinates to %s", numCoords, fileName));
            if (archivedOkToLog && !archivedLocalOnly) {
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
		pointDroneClock.add(droneClock);
		pointSources.add(source);
		WaypointCount++;
        if (droneSpec.isCurrentFlightConfirmed() && !droneSpec.isLocalArchiveOnly()) {
            recordPublicationIntent(false);
        }
	}

    @NonNull
    private String archiveAircraftID() {
        String remoteID = archivePrepared ? archivedRemoteId : droneSpec.getRemoteId();
        return remoteID == null || remoteID.isEmpty() ? trackLabel : remoteID;
    }

    private long archiveTimestampMsec() {
        JSONArray first = coordinates.optJSONArray(0);
        long timestamp = first == null ? 0 : first.optLong(3, 0);
        if (timestamp <= 0) timestamp = droneSpec.getStartMsecTimestamp();
        return timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

}
