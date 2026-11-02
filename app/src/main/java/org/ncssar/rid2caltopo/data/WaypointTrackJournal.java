package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.app.R2CApplication;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Append-only per-flight waypoint journal for unclean-exit recovery.
 * Writes stay off Main and off the LiveTrack start/archive lanes so durability
 * cannot stall publication or wait behind another flight's archive.
 */
public final class WaypointTrackJournal {
    private static final String TAG = "WaypointTrackJournal";
    private static final String DIR_NAME = "waypoint-track-journals";
    private static final String HEADER = "header";
    private static final String POINT = "point";
    private static final String FINISHED = "finished";

    private static final ExecutorService Writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "waypoint-track-journal");
        thread.setDaemon(true);
        return thread;
    });

    private static final ConcurrentHashMap<String, Object> FlightLocks = new ConcurrentHashMap<>();
    /** Single-flight recovery: ClueTrackDurabilityIntegrity and BgPoll must not run concurrently. */
    private static final Object RecoverLock = new Object();
    @Nullable private static File testRoot;
    /** Fault injection: runs after open record, before finish record. May throw to leave the journal. */
    @Nullable private static volatile Runnable recoverBetweenRecordStepsHook;
    /** Fault injection: runs after successful open+finish (+ best-effort local write), before delete. */
    @Nullable private static volatile Runnable recoverBeforeDeleteHook;

    private WaypointTrackJournal() { }

    @VisibleForTesting
    static void setRootForTesting(@Nullable File root) {
        testRoot = root;
    }

    @VisibleForTesting
    static void setRecoverBetweenRecordStepsHook(@Nullable Runnable hook) {
        recoverBetweenRecordStepsHook = hook;
    }

    @VisibleForTesting
    static void setRecoverBeforeDeleteHook(@Nullable Runnable hook) {
        recoverBeforeDeleteHook = hook;
    }

    @VisibleForTesting
    static void drainForTesting() throws Exception {
        Writer.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @NonNull
    private static File root() {
        if (testRoot != null) return testRoot;
        File base = R2CApplication.getAppCtxt() != null
                ? new File(R2CApplication.getAppCtxt().getFilesDir(), "operational-state")
                : new File(System.getProperty("java.io.tmpdir"), "operational-state");
        File dir = new File(base, DIR_NAME);
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    @NonNull
    private static File journalFile(@NonNull String flightId) {
        String safe = flightId.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(root(), safe + ".ndjson");
    }

    private static Object lockFor(@NonNull String flightId) {
        return FlightLocks.computeIfAbsent(flightId, key -> new Object());
    }

    /** Open or refresh the durable header for an in-progress waypoint track. */
    public static void ensureOpen(
            @NonNull String flightId,
            @NonNull String remoteId,
            @NonNull String trackLabel,
            @NonNull String mapId,
            @NonNull String teamId) {
        ensureOpen(flightId, remoteId, trackLabel, mapId, teamId, System.currentTimeMillis());
    }

    /**
     * Open a journal for [flightId]. A leftover finished journal for the same id is replaced so a
     * new flight never appends into a prior flight's file. Declined/suppressed ledger entries
     * refuse a new open for that same id.
     */
    public static void ensureOpen(
            @NonNull String flightId,
            @NonNull String remoteId,
            @NonNull String trackLabel,
            @NonNull String mapId,
            @NonNull String teamId,
            long startedAtMs) {
        Writer.execute(() -> {
            synchronized (lockFor(flightId)) {
                try {
                    File file = journalFile(flightId);
                    if (file.exists()) {
                        RecoveredFlight existing = readJournal(file);
                        if (existing != null && existing.finished) {
                            if (!file.delete()) {
                                CTWarn(TAG, "Could not replace finished journal " + file.getName());
                                return;
                            }
                        } else if (existing != null) {
                            // Same in-progress flight (including mid-flight Don't Publish): keep appending.
                            return;
                        }
                    }
                    // Do not open a fresh journal for a flight id already declined/suppressed/published.
                    if (!file.exists() && FlightPublicationLedger.blocksAwaitingMapPromotion(flightId)) {
                        CTDebug(TAG, "ensureOpen skipped for ledger-blocked flight " + flightId);
                        return;
                    }
                    if (!file.exists()) {
                        JSONObject header = new JSONObject()
                                .put("type", HEADER)
                                .put("id", flightId)
                                .put("remote", remoteId)
                                .put("label", trackLabel)
                                .put("map", mapId == null ? "" : mapId)
                                .put("team", teamId == null ? "" : teamId)
                                .put("startedAtMs", startedAtMs > 0 ? startedAtMs : System.currentTimeMillis());
                        appendLine(file, header);
                    }
                } catch (Exception error) {
                    CTError(TAG, "ensureOpen failed for " + flightId, error);
                }
            }
        });
    }

    /** Append one accepted waypoint. Safe to call from any thread; I/O is serialized. */
    public static void appendPoint(
            @NonNull String flightId,
            double lat,
            double lng,
            double ele,
            long timestampMsec,
            long receivedAtMs,
            @NonNull String source,
            boolean droneClock) {
        Writer.execute(() -> {
            synchronized (lockFor(flightId)) {
                try {
                    JSONObject point = new JSONObject()
                            .put("type", POINT)
                            .put("lat", lat)
                            .put("lng", lng)
                            .put("ele", ele)
                            .put("t", timestampMsec)
                            .put("recv", receivedAtMs)
                            .put("src", source)
                            .put("droneClock", droneClock);
                    appendLine(journalFile(flightId), point);
                } catch (Exception error) {
                    CTError(TAG, "appendPoint failed for " + flightId, error);
                }
            }
        });
    }

    /** Mark the journal finished and remove it after a successful local archive. */
    public static void markFinishedAndRemove(@NonNull String flightId) {
        Writer.execute(() -> {
            synchronized (lockFor(flightId)) {
                try {
                    File file = journalFile(flightId);
                    if (file.exists()) {
                        appendLine(file, new JSONObject().put("type", FINISHED)
                                .put("atMs", System.currentTimeMillis()));
                        if (!file.delete()) {
                            CTWarn(TAG, "Could not delete finished journal " + file.getName());
                        }
                    }
                } catch (Exception error) {
                    CTError(TAG, "markFinishedAndRemove failed for " + flightId, error);
                } finally {
                    FlightLocks.remove(flightId);
                }
            }
        });
    }

    public static void remove(@NonNull String flightId) {
        Writer.execute(() -> {
            synchronized (lockFor(flightId)) {
                try {
                    File file = journalFile(flightId);
                    if (file.exists() && !file.delete()) {
                        CTWarn(TAG, "Could not delete journal " + file.getName());
                    }
                } finally {
                    FlightLocks.remove(flightId);
                }
            }
        });
    }

    /**
     * Recover unfinished journals into AwaitingMapFlights (and optionally local Flight Storage).
     * Must run off Main. Serialized and idempotent: concurrent callers share one pass, and a
     * journal that was already declined, suppressed, published, or locally archived is deleted
     * without re-entering awaiting-map.
     */
    public static void recoverUnfinished() {
        CaltopoRecoveryWork.requireOffMain();
        synchronized (RecoverLock) {
            File dir = root();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".ndjson"));
            if (files == null || files.length == 0) return;
            for (File file : files) {
                try {
                    if (!file.exists()) continue;
                    RecoveredFlight recovered = readJournal(file);
                    if (recovered == null || recovered.points.length() == 0) {
                        // noinspection ResultOfMethodCallIgnored
                        file.delete();
                        continue;
                    }
                    if (recovered.finished) {
                        // noinspection ResultOfMethodCallIgnored
                        file.delete();
                        continue;
                    }
                    if (shouldRetireWithoutPromotion(recovered)) {
                        CTDebug(TAG, String.format(Locale.US,
                                "recoverUnfinished(): retiring %s without awaiting-map promotion",
                                recovered.id));
                        if (FlightPublicationLedger.isDeclinedOrSuppressed(recovered.id)) {
                            writeLocalTrackIfPossible(recovered, /*localOnly=*/true);
                        }
                        // noinspection ResultOfMethodCallIgnored
                        file.delete();
                        continue;
                    }
                    CTDebug(TAG, String.format(Locale.US,
                            "recoverUnfinished(): promoting %s (%d points) into awaiting-map / flight storage",
                            recovered.id, recovered.points.length()));
                    // Commit-then-delete: only remove the journal after AwaitingMapFlights
                    // open+finish succeed. A kill or failure mid-way leaves the .ndjson.
                    AwaitingMapFlights.record(
                            recovered.id,
                            recovered.remote,
                            recovered.label,
                            recovered.points,
                            false,
                            recovered.map,
                            recovered.team);
                    Runnable between = recoverBetweenRecordStepsHook;
                    if (between != null) between.run();
                    AwaitingMapFlights.record(
                            recovered.id,
                            recovered.remote,
                            recovered.label,
                            recovered.points,
                            true,
                            recovered.map,
                            recovered.team);
                    writeLocalTrackIfPossible(recovered, /*localOnly=*/false);
                    Runnable beforeDelete = recoverBeforeDeleteHook;
                    if (beforeDelete != null) beforeDelete.run();
                    // noinspection ResultOfMethodCallIgnored
                    file.delete();
                } catch (Exception error) {
                    CTError(TAG, "recoverUnfinished failed for " + file.getName()
                            + "; journal retained", error);
                }
            }
        }
    }

    private static boolean shouldRetireWithoutPromotion(@NonNull RecoveredFlight recovered) {
        return FlightPublicationRecoveryGate.shouldRetireWithoutPromotion(recovered.id);
    }

    private static void writeLocalTrackIfPossible(@NonNull RecoveredFlight recovered, boolean localOnly) {
        try {
            DocumentFileCompat.writeRecoveredGeoJson(recovered, localOnly);
        } catch (Exception error) {
            CTWarn(TAG, "Local track write skipped for " + recovered.id + ": " + error.getMessage());
        }
    }

    /** Small helper keeps SAF/DocumentFile imports out of the hot journal path. */
    private static final class DocumentFileCompat {
        static void writeRecoveredGeoJson(@NonNull RecoveredFlight recovered, boolean localOnly)
                throws Exception {
            long stamp = recovered.firstTimestampMs > 0
                    ? recovered.firstTimestampMs
                    : (recovered.startedAtMs > 0 ? recovered.startedAtMs : System.currentTimeMillis());
            androidx.documentfile.provider.DocumentFile dayDir =
                    CaltopoClient.GetTrackDirForTime(stamp);
            if (dayDir == null) return;
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            JSONObject properties = new JSONObject();
            properties.put("title", recovered.label);
            properties.put("r2c_flight_id", recovered.id);
            properties.put("r2c_recovered", true);
            JSONObject r2cProp = new JSONObject();
            r2cProp.put("rid", recovered.remote);
            r2cProp.put("local_archive_only", localOnly);
            properties.put("r2c_prop", r2cProp);
            feature.put("properties", properties);
            JSONObject geometry = new JSONObject();
            geometry.put("type", "LineString");
            geometry.put("coordinates", recovered.points);
            feature.put("geometry", geometry);
            JSONObject collection = new JSONObject()
                    .put("type", "FeatureCollection")
                    .put("features", new JSONArray().put(feature));
            String fileName = OperatorArchiveFilename.track(
                    recovered.remote.isEmpty() ? recovered.label : recovered.remote,
                    stamp);
            // Skip rewrite when this flight's archive already exists in its day folder.
            if (dayDir.findFile(fileName) != null) {
                CTDebug(TAG, "writeRecoveredGeoJson(): archive already present for " + fileName);
                return;
            }
            FlightArchiveStore.writeFlight(
                    dayDir,
                    fileName,
                    collection.toString(),
                    recovered.label,
                    recovered.remote,
                    recovered.id,
                    new ArrayList<>());
            if (localOnly) {
                FlightPublicationLedger.remember(
                        recovered.id, FlightPublicationLedger.Disposition.LOCAL_ARCHIVED);
            }
        }
    }

    @Nullable
    @VisibleForTesting
    static RecoveredFlight readJournal(@NonNull File file) throws Exception {
        String id = "";
        String remote = "";
        String label = "";
        String map = "";
        String team = "";
        boolean finished = false;
        JSONArray points = new JSONArray();
        long firstTimestampMs = 0L;
        long startedAtMs = 0L;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                JSONObject row = new JSONObject(line);
                String type = row.optString("type");
                if (HEADER.equals(type)) {
                    id = row.optString("id", id);
                    remote = row.optString("remote", remote);
                    label = row.optString("label", label);
                    map = row.optString("map", map);
                    team = row.optString("team", team);
                    startedAtMs = row.optLong("startedAtMs", startedAtMs);
                } else if (POINT.equals(type)) {
                    JSONArray coordinate = new JSONArray()
                            .put(String.format(Locale.US, "%.6f", row.getDouble("lng")))
                            .put(String.format(Locale.US, "%.6f", row.getDouble("lat")))
                            .put(String.format(Locale.US, "%d", Math.round(row.optDouble("ele", 0))))
                            .put(String.format(Locale.US, "%d", row.optLong("t")));
                    points.put(coordinate);
                    if (firstTimestampMs <= 0L) firstTimestampMs = row.optLong("t");
                } else if (FINISHED.equals(type)) {
                    finished = true;
                }
            }
        }
        if (id.isEmpty()) return null;
        return new RecoveredFlight(id, remote, label, map, team, points, finished,
                firstTimestampMs, startedAtMs);
    }

    @VisibleForTesting
    static final class RecoveredFlight {
        final String id;
        final String remote;
        final String label;
        final String map;
        final String team;
        final JSONArray points;
        final boolean finished;
        final long firstTimestampMs;
        final long startedAtMs;

        RecoveredFlight(String id, String remote, String label, String map, String team,
                        JSONArray points, boolean finished, long firstTimestampMs) {
            this(id, remote, label, map, team, points, finished, firstTimestampMs, 0L);
        }

        RecoveredFlight(String id, String remote, String label, String map, String team,
                        JSONArray points, boolean finished, long firstTimestampMs, long startedAtMs) {
            this.id = id;
            this.remote = remote;
            this.label = label;
            this.map = map;
            this.team = team;
            this.points = points;
            this.finished = finished;
            this.firstTimestampMs = firstTimestampMs;
            this.startedAtMs = startedAtMs;
        }
    }

    private static void appendLine(@NonNull File file, @NonNull JSONObject row) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            // noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(file, true);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writer.write(row.toString());
            writer.newLine();
            writer.flush();
            out.getFD().sync();
        }
    }
}
