package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.Consumer;

public interface CalTopoSessionGateway {
    void init(@NonNull CaltopoCredentials credentials, @NonNull String domainAndPort);
    @NonNull CaltopoOp verifyAccount(@Nullable Consumer<CaltopoOp> onComplete);
    void shutdown();
    @NonNull CaltopoOp openMap(@NonNull CaltopoNode.MapNode mapNode, long lastSyncTimestamp, @Nullable Consumer<CaltopoOp> onComplete);
    @Nullable CaltopoOp addFolder(@NonNull String folderName, boolean contentsVisible, boolean contentLabelsVisible, @Nullable Consumer<CaltopoOp> onComplete);
    @NonNull CaltopoOp addLine(@NonNull JSONArray pointArray, @NonNull String lineLabel, @Nullable String description,
                               @Nullable String existingLineId, @Nullable String folderId,
                               @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete);
    @Nullable CaltopoOp addMarker(double lat, double lng, @NonNull String markerTitle,
                                  @Nullable String symbol, @Nullable String folderId,
                                  @Nullable String existingMarkerId, @Nullable JSONObject extraProperties,
                                  @Nullable Consumer<CaltopoOp> onComplete);
    @NonNull CaltopoOp deleteShapeWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete);
    @NonNull CaltopoOp deleteMarkerWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete);
    @NonNull CaltopoOp deleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete);
    @NonNull CaltopoOp deleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete, int... acceptedErrorCodes);
    @NonNull CaltopoOp editObjectWithId(@NonNull String objectType, @NonNull String objId,
                                        @NonNull JSONObject featureSet, @Nullable Consumer<CaltopoOp> onComplete);
    @NonNull CaltopoOp startLiveTrack(@NonNull String liveTrackId, @NonNull String deviceId, @NonNull String label,
                                      @Nullable String folderId, @Nullable String description,
                                      @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete);
    default void cancelLiveTrackPoints(@NonNull String deviceId) { }
    @NonNull CaltopoOp addLiveTrackPoint(@NonNull String deviceId, double lat, double lng, double eleMeters,
                                         @Nullable CtDroneSpec.PositionTelemetry telemetry,
                                         @Nullable CaltopoCameraMetadata cameraMetadata,
                                         @Nullable Consumer<CaltopoOp> onComplete);
    @Nullable CaltopoOp addPhotoMarker(double lat, double lng, @NonNull String markerTitle, @NonNull String markerDesc,
                                       @NonNull String folderId, long clueTimestamp,
                                       @NonNull android.graphics.Bitmap photoBitmap,
                                       @Nullable Consumer<CaltopoOp> onComplete);
}
