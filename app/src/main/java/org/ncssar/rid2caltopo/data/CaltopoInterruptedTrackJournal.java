package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.app.R2CApplication;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/** Durable recovery journal for CalTopo LiveTracks interrupted by process death or app update. */
public final class CaltopoInterruptedTrackJournal {
    private static final String TAG = "CaltopoRecovery";
    private static final String FILE_NAME = "caltopo-interrupted-tracks.json";
    private static final Object LOCK = new Object();
    @Nullable private static File testFile;

    private CaltopoInterruptedTrackJournal() { }

    public static boolean save(@NonNull String mapId,
                            @NonNull String remoteId,
                            @NonNull String liveTrackId,
                            @NonNull String label,
                            @NonNull String description,
                            @NonNull JSONArray points) {
        if (mapId.isEmpty() || liveTrackId.isEmpty()) return false;
        synchronized (LOCK) {
            try {
                JSONObject root = readRoot();
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) {
                    entries = new JSONArray();
                    root.put("entries", entries);
                }
                JSONObject entry = null;
                for (int index = 0; index < entries.length(); index++) {
                    JSONObject candidate = entries.optJSONObject(index);
                    if (candidate != null && liveTrackId.equals(candidate.optString("liveTrackId"))) {
                        entry = candidate;
                        break;
                    }
                }
                if (entry == null) {
                    entry = new JSONObject();
                    entries.put(entry);
                }
                entry.put("mapId", mapId);
                entry.put("remoteId", remoteId);
                entry.put("liveTrackId", liveTrackId);
                entry.put("label", label);
                entry.put("description", description);
                entry.put("points", new JSONArray(points.toString()));
                writeRoot(root);
                return true;
            } catch (Exception error) {
                CaltopoClient.CTError(TAG, "Could not persist interrupted LiveTrack", error);
                return false;
            }
        }
    }

    @NonNull
    public static Set<String> recover(@NonNull String mapId,
                                      @NonNull String archiveFolderId,
                                      @NonNull R2cRuntime runtime) {
        Set<String> recovering = new HashSet<>();
        JSONArray entries;
        synchronized (LOCK) {
            entries = readRoot().optJSONArray("entries");
            if (entries == null) return recovering;
            entries = copyArray(entries);
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null || !mapId.equals(entry.optString("mapId"))) continue;
            String liveTrackId = entry.optString("liveTrackId");
            JSONArray points = entry.optJSONArray("points");
            if (liveTrackId.isEmpty() || points == null || points.length() == 0) continue;
            recovering.add(liveTrackId);
            try {
                JSONObject feature = archiveFeature(entry, archiveFolderId);
                runtime.getCalTopoSessionGateway().editObjectWithId(
                        "Shape", liveTrackId, feature, editOp -> {
                            if (!editOp.success()) {
                                CaltopoClient.CTWarn(TAG, "Interrupted LiveTrack conversion deferred id=" + liveTrackId);
                                return;
                            }
                            runtime.getCalTopoSessionGateway().deleteLiveTrackWithId(
                                    liveTrackId,
                                    deleteOp -> {
                                        if (deleteOp.success()) {
                                            remove(liveTrackId);
                                            CaltopoClient.CTInfo(TAG, "Recovered interrupted LiveTrack id=" + liveTrackId);
                                        } else {
                                            CaltopoClient.CTWarn(TAG, "Interrupted LiveTrack deletion deferred id=" + liveTrackId);
                                        }
                                    },
                                    400, 404);
                        });
            } catch (Exception error) {
                CaltopoClient.CTError(TAG, "Interrupted LiveTrack recovery failed id=" + liveTrackId, error);
            }
        }
        return recovering;
    }

    public static void remove(@NonNull String liveTrackId) {
        synchronized (LOCK) {
            try {
                JSONObject root = readRoot();
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) return;
                JSONArray kept = new JSONArray();
                for (int index = 0; index < entries.length(); index++) {
                    JSONObject entry = entries.optJSONObject(index);
                    if (entry == null || !liveTrackId.equals(entry.optString("liveTrackId"))) {
                        kept.put(entry);
                    }
                }
                root.put("entries", kept);
                writeRoot(root);
            } catch (Exception error) {
                CaltopoClient.CTError(TAG, "Could not clear interrupted LiveTrack " + liveTrackId, error);
            }
        }
    }

    static void setFileForTesting(@Nullable File file) {
        synchronized (LOCK) { testFile = file; }
    }

    @NonNull
    static JSONArray entriesForTesting() {
        synchronized (LOCK) {
            JSONArray entries = readRoot().optJSONArray("entries");
            return entries == null ? new JSONArray() : copyArray(entries);
        }
    }

    @NonNull
    private static JSONObject archiveFeature(@NonNull JSONObject entry,
                                             @NonNull String archiveFolderId) throws Exception {
        String liveTrackId = entry.getString("liveTrackId");
        JSONObject properties = new JSONObject();
        properties.put("class", "Shape");
        properties.put("title", entry.optString("label", entry.optString("remoteId")));
        properties.put("folderId", archiveFolderId);
        properties.put("stroke", CaltopoMap.ArchiveLineProp.color);
        properties.put("stroke-width", CaltopoMap.ArchiveLineProp.width);
        properties.put("stroke-opacity", CaltopoMap.ArchiveLineProp.opacity);
        properties.put("pattern", CaltopoMap.ArchiveLineProp.pattern);
        properties.put("updated", String.valueOf(System.currentTimeMillis()));
        properties.put("-updated-on", String.valueOf(System.currentTimeMillis()));
        String description = entry.optString("description").trim();
        if (!description.isEmpty()) properties.put("description", description);

        JSONArray points = entry.getJSONArray("points");
        JSONObject geometry = new JSONObject();
        geometry.put("type", "LineString");
        geometry.put("coordinates", points);
        geometry.put("size", points.length());

        JSONObject feature = new JSONObject();
        feature.put("id", liveTrackId);
        feature.put("type", "Feature");
        feature.put("properties", properties);
        feature.put("geometry", geometry);
        return feature;
    }

    @NonNull
    private static JSONObject readRoot() {
        try {
            File file = journalFile();
            if (file == null || !file.isFile()) return emptyRoot();
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception error) {
            CaltopoClient.CTError(TAG, "Could not read interrupted LiveTrack journal", error);
            return emptyRoot();
        }
    }

    private static void writeRoot(@NonNull JSONObject root) throws Exception {
        File file = journalFile();
        if (file == null) throw new IllegalStateException("Recovery journal storage unavailable");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create journal directory");
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        Files.write(temporary.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Nullable
    private static File journalFile() {
        if (testFile != null) return testFile;
        R2CApplication context = R2CApplication.getAppCtxt();
        return context == null ? null : new File(context.getFilesDir(), FILE_NAME);
    }

    @NonNull
    private static JSONObject emptyRoot() {
        try {
            return new JSONObject().put("version", 1).put("entries", new JSONArray());
        } catch (Exception impossible) {
            return new JSONObject();
        }
    }

    @NonNull
    private static JSONArray copyArray(@NonNull JSONArray source) {
        try {
            return new JSONArray(source.toString());
        } catch (Exception error) {
            CaltopoClient.CTError(TAG, "Could not copy interrupted LiveTrack entries", error);
            return new JSONArray();
        }
    }
}
