package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.app.R2CApplication;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Durable recovery journal for CalTopo LiveTracks interrupted by process death or app update. */
public final class CaltopoInterruptedTrackJournal {
    private static final String TAG = "CaltopoRecovery";
    private static final String FILE_NAME = "caltopo-interrupted-tracks.json";
    private static final Object LOCK = new Object();
    private static final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Nullable private static File testFile;
    /** In-memory journal mirror; disk I/O must not run while LOCK is held. */
    @Nullable private static JSONObject cachedRoot;

    private CaltopoInterruptedTrackJournal() { }

    public static boolean save(@NonNull String mapId,
                            @NonNull String remoteId,
                            @NonNull String liveTrackId,
                            @NonNull String label,
                            @NonNull String description,
                            @NonNull JSONArray points) {
        CaltopoRecoveryWork.requireOffMain();
        if (mapId.isEmpty() || liveTrackId.isEmpty()) return false;
        final JSONObject toWrite;
        synchronized (LOCK) {
            try {
                JSONObject root = mutableRootLocked();
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
                cachedRoot = root;
                toWrite = copyRoot(root);
            } catch (Exception error) {
                CaltopoClient.CTError(TAG, "Could not persist interrupted LiveTrack", error);
                return false;
            }
        }
        try {
            writeRoot(toWrite);
            return true;
        } catch (Exception error) {
            synchronized (LOCK) { cachedRoot = null; }
            CaltopoClient.CTError(TAG, "Could not persist interrupted LiveTrack", error);
            return false;
        }
    }

    @NonNull
    public static Set<String> recover(@NonNull String mapId,
                                      @NonNull String archiveFolderId,
                                      @NonNull R2cRuntime runtime) {
        return recover(mapId, archiveFolderId, runtime, java.util.Collections.emptyList());
    }

    public static Set<String> recover(@NonNull String mapId,
                                      @NonNull String archiveFolderId,
                                      @NonNull R2cRuntime runtime,
                                      @NonNull java.util.List<JSONObject> mapFeatures) {
        // Upgrade old journals from positive server evidence, without recreating
        // an archive that is already present on this map.
        java.util.Map<String, JSONObject> archivedFeatures = new java.util.HashMap<>();
        for (JSONObject feature : mapFeatures) {
            JSONObject properties = feature.optJSONObject("properties");
            if (properties != null && "Shape".equals(properties.optString("class"))) {
                archivedFeatures.put(feature.optString("id"), feature);
            }
        }
        Set<String> recovering = new HashSet<>();
        JSONArray entries;
        synchronized (LOCK) {
            entries = mutableRootLocked().optJSONArray("entries");
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
            if (CaltopoLiveTrack.hasActivePublication(liveTrackId) || !inFlight.add(liveTrackId)) continue;
            try {
                if (entry.optBoolean("archiveUploaded") || archiveContainsPoints(archivedFeatures.get(liveTrackId), points)) {
                    finishArchiveCleanup(mapId, liveTrackId, runtime);
                    continue;
                }
                JSONObject feature = archiveFeature(entry, archiveFolderId);
                runtime.getCalTopoSessionGateway().editObjectOnMap(
                        mapId, "Shape", liveTrackId, feature, editOp -> CaltopoRecoveryWork.offMainArchive(() -> {
                            if (!editOp.success()) {
                                inFlight.remove(liveTrackId);
                                CaltopoClient.CTWarn(TAG, "Interrupted LiveTrack conversion deferred id=" + liveTrackId);
                                return;
                            }
                            finishArchiveCleanup(mapId, liveTrackId, runtime);
                        }));
            } catch (Exception error) {
                inFlight.remove(liveTrackId);
                CaltopoClient.CTError(TAG, "Interrupted LiveTrack recovery failed id=" + liveTrackId, error);
            }
        }
        return recovering;
    }

    private static boolean archiveContainsPoints(@Nullable JSONObject feature, JSONArray points) {
        if (feature == null) return false;
        JSONObject geometry = feature.optJSONObject("geometry");
        if (geometry == null || !"LineString".equals(geometry.optString("type"))) return false;
        JSONArray archived = geometry.optJSONArray("coordinates");
        if (archived == null || archived.length() < points.length()) return false;
        // An older/partial Shape is not proof that the complete buffered track
        // was uploaded. Require every pending coordinate, including altitude.
        for (int i = 0; i < points.length(); i++) {
            JSONArray expected = points.optJSONArray(i);
            JSONArray actual = archived.optJSONArray(i);
            if (expected == null || actual == null || actual.length() < expected.length()) return false;
            for (int j = 0; j < expected.length(); j++) {
                double value = expected.optDouble(j);
                if (!Double.isFinite(value) || Double.compare(value, actual.optDouble(j)) != 0) return false;
            }
        }
        return true;
    }

    private static void finishArchiveCleanup(String mapId, String liveTrackId, R2cRuntime runtime) {
        try {
            // Persist completion before cleanup: a cleanup retry must never POST
            // the Shape again, including after a restart or manual map deletion.
            final JSONObject toWrite;
            synchronized (LOCK) {
                JSONObject root = mutableRootLocked();
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) { inFlight.remove(liveTrackId); return; }
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry != null && liveTrackId.equals(entry.optString("liveTrackId"))) {
                        entry.put("archiveUploaded", true);
                    }
                }
                cachedRoot = root;
                toWrite = copyRoot(root);
            }
            try {
                writeRoot(toWrite);
            } catch (Exception writeError) {
                synchronized (LOCK) { cachedRoot = null; }
                throw writeError;
            }
            AwaitingMapFlights.published(liveTrackId);
            runtime.getCalTopoSessionGateway().deleteLiveTrackOnMap(mapId, liveTrackId, deleteOp -> CaltopoRecoveryWork.offMainArchive(() -> {
                // Match Apple's already-gone handling. Standalone archives never
                // created a LiveTrack, for which CalTopo returns 400 (or 404).
                if (deleteOp.success() || deleteOp.responseCode == 400 || deleteOp.responseCode == 404) {
                    remove(liveTrackId);
                    CaltopoClient.CTInfo(TAG, "Recovered interrupted LiveTrack id=" + liveTrackId);
                } else {
                    CaltopoClient.CTWarn(TAG, "Archive uploaded; only LiveTrack cleanup pending id=" + liveTrackId);
                }
                inFlight.remove(liveTrackId);
            }));
        } catch (Exception error) {
            inFlight.remove(liveTrackId);
            CaltopoClient.CTError(TAG, "Could not finish archive cleanup id=" + liveTrackId, error);
        }
    }

    public static void remove(@NonNull String liveTrackId) {
        CaltopoRecoveryWork.requireOffMain();
        final JSONObject toWrite;
        synchronized (LOCK) {
            try {
                JSONObject root = mutableRootLocked();
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
                cachedRoot = root;
                toWrite = copyRoot(root);
            } catch (Exception error) {
                CaltopoClient.CTError(TAG, "Could not clear interrupted LiveTrack " + liveTrackId, error);
                return;
            }
        }
        try {
            writeRoot(toWrite);
        } catch (Exception error) {
            synchronized (LOCK) { cachedRoot = null; }
            CaltopoClient.CTError(TAG, "Could not clear interrupted LiveTrack " + liveTrackId, error);
        }
    }

    static void setFileForTesting(@Nullable File file) {
        synchronized (LOCK) {
            testFile = file;
            cachedRoot = null;
        }
    }

    @NonNull
    static JSONArray entriesForTesting() {
        synchronized (LOCK) {
            JSONArray entries = mutableRootLocked().optJSONArray("entries");
            return entries == null ? new JSONArray() : copyArray(entries);
        }
    }

    /** Pending finished-track / interrupted Shape archives awaiting upload. */
    public static int pendingCount() {
        synchronized (LOCK) {
            JSONArray entries = mutableRootLocked().optJSONArray("entries");
            return entries == null ? 0 : entries.length();
        }
    }

    /**
     * Clear recover() in-flight markers so a connectivity-restore flush can retry
     * after canceling a hung Shape POST that never completed its callback.
     */
    public static void clearInFlightForConnectivityRestore() {
        inFlight.clear();
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

    /** Caller holds LOCK. Cold-start disk read is the only I/O under LOCK. */
    @NonNull
    private static JSONObject mutableRootLocked() {
        if (cachedRoot != null) return cachedRoot;
        cachedRoot = readRootFromDisk();
        return cachedRoot;
    }

    @NonNull
    private static JSONObject copyRoot(@NonNull JSONObject root) throws Exception {
        return new JSONObject(root.toString());
    }

    @NonNull
    private static JSONObject readRootFromDisk() {
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
        // .partial + fsync + verify + atomic move — same durability as flight archives.
        PlainFileSafeWriter.replace(file, root.toString().getBytes(StandardCharsets.UTF_8), bytes -> bytes.length > 0);
        org.ncssar.rid2caltopo.storage.OperationalStorage.INSTANCE.retainLater(file);
    }

    @Nullable
    private static File journalFile() {
        if (testFile != null) return testFile;
        R2CApplication context = R2CApplication.getAppCtxt();
        return context == null ? null : org.ncssar.rid2caltopo.storage.OperationalStorage.INSTANCE.stateFile(context, FILE_NAME);
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
