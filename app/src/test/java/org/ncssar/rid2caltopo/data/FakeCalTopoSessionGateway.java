package org.ncssar.rid2caltopo.data;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Test double for runtime-owned CalTopo session interactions.
 *
 * It records every operation in-memory so multi-runtime tests can assert which
 * runtime attempted to open maps, create tracks, add live points, archive
 * lines, etc., without touching the real network or CalTopo backend.
 */
public final class FakeCalTopoSessionGateway implements CalTopoSessionGateway {
    public final List<Consumer<CaltopoOp>> startCallbacks = new ArrayList<>();
    public final List<Consumer<CaltopoOp>> pointCallbacks = new ArrayList<>();

    public static final class Operation {
        @NonNull public final String kind;
        @NonNull public final String summary;
        @Nullable public final JSONObject payload;

        Operation(@NonNull String kind, @NonNull String summary, @Nullable JSONObject payload) {
            this.kind = kind;
            this.summary = summary;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return kind + ": " + summary;
        }
    }

    private final String runtimeLabel;
    private final List<Operation> operations = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile CaltopoCredentials credentials;
    private volatile String domainAndPort = "";
    private volatile String currentMapId = "";

    public FakeCalTopoSessionGateway(@NonNull String runtimeLabel) {
        this.runtimeLabel = runtimeLabel;
    }

    @NonNull
    public List<Operation> snapshotOperations() {
        synchronized (operations) {
            return new ArrayList<>(operations);
        }
    }

    public int countOperations(@NonNull String kind) {
        int count = 0;
        synchronized (operations) {
            for (Operation op : operations) {
                if (kind.equals(op.kind)) count++;
            }
        }
        return count;
    }

    public void clear() {
        operations.clear();
    }

    @Override
    public void init(@NonNull CaltopoCredentials credentials, @NonNull String domainAndPort) {
        this.credentials = credentials;
        this.domainAndPort = domainAndPort;
        record("init", String.format(Locale.US, "%s %s", safe(credentials.teamId), domainAndPort), null);
    }

    @NonNull
    @Override
    public CaltopoOp verifyAccount(@Nullable Consumer<CaltopoOp> onComplete) {
        record("verifyAccount", runtimeLabel, null);
        return completedOp("verify");
    }

    @Override
    public void shutdown() {
        record("shutdown", runtimeLabel, null);
    }

    @NonNull
    @Override
    public CaltopoOp openMap(@NonNull CaltopoNode.MapNode mapNode, long lastSyncTimestamp, @Nullable Consumer<CaltopoOp> onComplete) {
        currentMapId = mapNode.getId();
        JSONObject payload = new JSONObject();
        try {
            payload.put("mapId", mapNode.getId());
            payload.put("lastSyncTimestamp", lastSyncTimestamp);
        } catch (Exception ignored) { }
        record("openMap", mapNode.getId(), payload);
        return completedOp("map-" + mapNode.getId());
    }

    @Nullable
    @Override
    public CaltopoOp addFolder(@NonNull String folderName, boolean contentsVisible, boolean contentLabelsVisible, @Nullable Consumer<CaltopoOp> onComplete) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("folderName", folderName);
            payload.put("contentsVisible", contentsVisible);
            payload.put("contentLabelsVisible", contentLabelsVisible);
        } catch (Exception ignored) { }
        record("addFolder", folderName, payload);
        return completedOp("folder-" + folderName);
    }

    @NonNull
    @Override
    public CaltopoOp addLine(@NonNull JSONArray pointArray, @NonNull String lineLabel, @Nullable String description, @Nullable String existingLineId, @Nullable String folderId, @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("lineLabel", lineLabel);
            payload.put("pointCount", pointArray.length());
            payload.put("folderId", folderId);
            payload.put("existingLineId", existingLineId);
        } catch (Exception ignored) { }
        record("addLine", lineLabel, payload);
        return completedOp(existingLineId != null && !existingLineId.isEmpty() ? existingLineId : "line-" + nextId.getAndIncrement());
    }

    @Nullable
    @Override
    public CaltopoOp addMarker(double lat, double lng, @NonNull String markerTitle, @Nullable String symbol, @Nullable String folderId, @Nullable String existingMarkerId, @Nullable JSONObject extraProperties, @Nullable Consumer<CaltopoOp> onComplete) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("markerTitle", markerTitle);
            payload.put("lat", lat);
            payload.put("lng", lng);
            payload.put("symbol", symbol);
            payload.put("folderId", folderId);
            payload.put("existingMarkerId", existingMarkerId);
            if (extraProperties != null) {
                payload.put("extraProperties", extraProperties);
            }
        } catch (Exception ignored) { }
        record("addMarker", markerTitle, payload);
        return completedOp(existingMarkerId != null && !existingMarkerId.isEmpty() ? existingMarkerId : "marker-" + nextId.getAndIncrement());
    }

    @NonNull
    @Override
    public CaltopoOp deleteShapeWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        record("deleteShape", objId, null);
        return completedOp(objId);
    }

    @NonNull
    @Override
    public CaltopoOp deleteMarkerWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        record("deleteMarker", objId, null);
        return completedOp(objId);
    }

    @NonNull
    @Override
    public CaltopoOp deleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        return deleteLiveTrackWithId(objId, onComplete, new int[0]);
    }

    @NonNull
    @Override
    public CaltopoOp deleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete, int... successCodes) {
        record("deleteLiveTrack", objId, null);
        CaltopoOp op = completedOp(objId);
        if (onComplete != null) onComplete.accept(op);
        return op;
    }

    @NonNull
    @Override
    public CaltopoOp editObjectWithId(@NonNull String objectType, @NonNull String objId, @NonNull JSONObject featureSet, @Nullable Consumer<CaltopoOp> onComplete) {
        record("editObject", objectType + ":" + objId, featureSet);
        CaltopoOp op = completedOp(objId);
        if (onComplete != null) onComplete.accept(op);
        return op;
    }

    @NonNull
    @Override
    public CaltopoOp startLiveTrack(@NonNull String liveTrackId, @NonNull String deviceId, @NonNull String label, @Nullable String folderId, @Nullable String description, @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("deviceId", deviceId);
            payload.put("label", label);
            payload.put("folderId", folderId);
            payload.put("mapId", currentMapId);
        } catch (Exception ignored) { }
        try { payload.put("id", liveTrackId); } catch (Exception ignored) { }
        record("startLiveTrack", deviceId, payload);
        startCallbacks.add(onComplete);
        return completedOp(liveTrackId);
    }

    @NonNull
    @Override
    public CaltopoOp addLiveTrackPoint(@NonNull String deviceId, double lat, double lng, double eleMeters, @Nullable CtDroneSpec.PositionTelemetry telemetry, @Nullable CaltopoCameraMetadata cameraMetadata, @Nullable Consumer<CaltopoOp> onComplete) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("deviceId", deviceId);
            payload.put("lat", lat);
            payload.put("lng", lng);
            payload.put("eleMeters", eleMeters);
            if (telemetry != null) {
                JSONObject telemetryJson = new JSONObject();
                if (telemetry.aircraftGsKnots != null) telemetryJson.put("gsKnots", telemetry.aircraftGsKnots);
                if (telemetry.aircraftTrackDeg != null) telemetryJson.put("trackDeg", telemetry.aircraftTrackDeg);
                if (telemetry.aircraftAltitudeRateFpm != null) telemetryJson.put("altitudeRateFpm", telemetry.aircraftAltitudeRateFpm);
                payload.put("telemetry", telemetryJson);
            }
            if (cameraMetadata != null) {
                payload.put("cameraExternalUrl", cameraMetadata.externalUrl);
                payload.put("cameraThumbnailUrl", cameraMetadata.thumbnailUrl);
            }
        } catch (Exception ignored) { }
        record("addLiveTrackPoint", deviceId, payload);
        pointCallbacks.add(onComplete);
        return completedOp(deviceId);
    }

    @Nullable
    @Override
    public CaltopoOp addPhotoMarker(double lat, double lng, @NonNull String markerTitle, @NonNull String markerDesc, @NonNull String folderId, long clueTimestamp, @NonNull Bitmap photoBitmap, @Nullable Consumer<CaltopoOp> onComplete) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("markerTitle", markerTitle);
            payload.put("folderId", folderId);
            payload.put("clueTimestamp", clueTimestamp);
            payload.put("width", photoBitmap.getWidth());
            payload.put("height", photoBitmap.getHeight());
        } catch (Exception ignored) { }
        record("addPhotoMarker", markerTitle, payload);
        return completedOp("photo-" + nextId.getAndIncrement());
    }

    private void record(@NonNull String kind, @NonNull String summary, @Nullable JSONObject payload) {
        operations.add(new Operation(kind, summary, payload));
    }

    @NonNull
    private CaltopoOp completedOp(@NonNull String id) {
        CaltopoOp op = new CaltopoOp(null);
        op.responseCode = 200;
        op.response = "fake";
        JSONObject responseJson = new JSONObject();
        try {
            responseJson.put("id", id);
            responseJson.put("status", "fake");
        } catch (Exception ignored) { }
        op.responseJson = responseJson;
        op.setOperationIsDone(true);
        return op;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
