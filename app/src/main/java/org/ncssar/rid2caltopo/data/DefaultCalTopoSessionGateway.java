package org.ncssar.rid2caltopo.data;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.Consumer;

public final class DefaultCalTopoSessionGateway implements CalTopoSessionGateway {
    private static final DefaultCalTopoSessionGateway INSTANCE = new DefaultCalTopoSessionGateway();

    private DefaultCalTopoSessionGateway() { }

    @NonNull
    public static DefaultCalTopoSessionGateway getInstance() {
        return INSTANCE;
    }

    @Override
    public void init(@NonNull CaltopoCredentials credentials, @NonNull String domainAndPort) {
        CaltopoSession.Init(credentials, domainAndPort);
    }

    @NonNull
    @Override
    public CaltopoOp verifyAccount(@Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.VerifyAccount(onComplete);
    }

    @Override
    public void shutdown() {
        CaltopoSession.Shutdown();
    }

    @NonNull
    @Override
    public CaltopoOp openMap(@NonNull CaltopoNode.MapNode mapNode, long lastSyncTimestamp, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.OpenMap(mapNode, lastSyncTimestamp, onComplete);
    }

    @Nullable
    @Override
    public CaltopoOp addFolder(@NonNull String folderName, boolean contentsVisible, boolean contentLabelsVisible, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.AddFolder(folderName, contentsVisible, contentLabelsVisible, onComplete);
    }

    @NonNull
    @Override
    public CaltopoOp addLine(@NonNull JSONArray pointArray, @NonNull String lineLabel, @Nullable String description, @Nullable String existingLineId, @Nullable String folderId, @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.AddLine(pointArray, lineLabel, description, existingLineId, folderId, lineProp, onComplete);
    }

    @Nullable
    @Override
    public CaltopoOp addMarker(double lat, double lng, @NonNull String markerTitle, @Nullable String symbol, @Nullable String folderId, @Nullable String existingMarkerId, @Nullable JSONObject extraProperties, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.AddMarker(lat, lng, markerTitle, symbol, folderId, existingMarkerId, extraProperties, onComplete);
    }

    @NonNull
    @Override
    public CaltopoOp deleteShapeWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.DeleteShapeWithId(objId, onComplete);
    }

    @NonNull
    @Override
    public CaltopoOp deleteMarkerWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.DeleteMarkerWithId(objId, onComplete);
    }

    @NonNull
    @Override
    public CaltopoOp deleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.DeleteLiveTrackWithId(objId, onComplete);
    }

    public CaltopoOp deleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete,
                                           int... acceptedErrorCodes) {
        return CaltopoSession.DeleteLiveTrackWithId(objId, onComplete, acceptedErrorCodes);
    }

    @NonNull
    @Override
    public CaltopoOp editObjectWithId(@NonNull String objectType, @NonNull String objId, @NonNull JSONObject featureSet, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.EditObjectWithId(objectType, objId, featureSet, onComplete);
    }

    @NonNull
    @Override
    public CaltopoOp startLiveTrack(@NonNull String liveTrackId, @NonNull String deviceId, @NonNull String label, @Nullable String folderId, @Nullable String description, @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.StartLiveTrack(liveTrackId, deviceId, label, folderId, description, lineProp, onComplete);
    }

    @Override
    public void cancelLiveTrackPoints(@NonNull String deviceId) {
        CaltopoSession.CancelLiveTrackPoints(deviceId);
    }

    @NonNull
    @Override
    public CaltopoOp addLiveTrackPoint(@NonNull String deviceId, double lat, double lng, double eleMeters, @Nullable CtDroneSpec.PositionTelemetry telemetry, @Nullable CaltopoCameraMetadata cameraMetadata, @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.AddLiveTrackPoint(deviceId, lat, lng, eleMeters, telemetry, cameraMetadata, onComplete);
    }

    @NonNull
    @Override
    public CaltopoOp addPhotoMarker(double lat, double lng, @NonNull String markerTitle, @NonNull String markerDesc,
                                    @NonNull String folderId, long clueTimestamp,
                                    @NonNull Bitmap photoBitmap,
                                    @Nullable Consumer<CaltopoOp> onComplete) {
        return CaltopoSession.AddPhotoMarker(lat, lng, markerTitle, markerDesc, folderId, clueTimestamp, photoBitmap, onComplete);
    }
}
