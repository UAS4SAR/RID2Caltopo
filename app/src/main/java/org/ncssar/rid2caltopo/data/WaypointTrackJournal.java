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
    private static final String CONTEXT = "context";
    private static final ConcurrentHashMap<String, String> LastContext = new ConcurrentHashMap<>();

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
    /** Tests supply saved flights so recovery does not need the archive directory. */
    @Nullable private static volatile RecoveredFlightDedup.Lookup savedFlightLookupForTesting;
    /** Tests capture the flight file. Production writes the archive and returns whether it is durable. */
    @Nullable private static volatile RecoveredArchiveWriter recoveredArchiveWriterForTesting;

    /** True when the flight file now holds this journal. A false result must keep the .ndjson. */
    interface RecoveredArchiveWriter {
        boolean write(@NonNull RecoveredFlight flight, boolean localOnly);
    }

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
    static void setSavedFlightLookupForTesting(@Nullable RecoveredFlightDedup.Lookup lookup) {
        savedFlightLookupForTesting = lookup;
    }

    @VisibleForTesting
    static void setRecoveredArchiveWriterForTesting(@Nullable RecoveredArchiveWriter writer) {
        recoveredArchiveWriterForTesting = writer;
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
                            long requestedStart = startedAtMs > 0 ? startedAtMs : 0L;
                            // Same in-progress flight (including mid-flight Don't Publish): keep
                            // appending. A leftover unfinished journal from a different start must
                            // never receive another flight's points under a reused id.
                            if (requestedStart <= 0L
                                    || existing.startedAtMs <= 0L
                                    || existing.startedAtMs == requestedStart) {
                                return;
                            }
                            CTWarn(TAG, String.format(Locale.US,
                                    "ensureOpen replacing unfinished journal %s priorStart=%d newStart=%d",
                                    file.getName(), existing.startedAtMs, requestedStart));
                            if (!file.delete()) {
                                CTWarn(TAG, "Could not replace mismatched journal " + file.getName());
                                return;
                            }
                        }
                    }
                    // Do not open a fresh journal for a flight id already declined/discarded/published.
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

    /**
     * Append the latest known pilot/claim facts. Identical repeats are skipped.
     * Legacy journals simply have no context line; recovery must not invent one.
     */
    public static void noteContext(@NonNull String flightId, @NonNull FlightOwnershipContext.Snapshot snapshot) {
        JSONObject row = FlightOwnershipContext.toJson(snapshot);
        String encoded = row.toString();
        if (encoded.equals(LastContext.get(flightId))) return;
        Writer.execute(() -> {
            synchronized (lockFor(flightId)) {
                try {
                    File file = journalFile(flightId);
                    if (!file.exists()) {
                        LastContext.remove(flightId);
                        return;
                    }
                    if (encoded.equals(LastContext.get(flightId))) return;
                    appendLine(file, row);
                    LastContext.put(flightId, encoded);
                } catch (Exception error) {
                    CTError(TAG, "noteContext failed for " + flightId, error);
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

    /** Delete an unfinished journal without turning it into a flight file. */
    public static void discardUnfinished(@NonNull String flightId) {
        if (flightId.isEmpty()) return;
        Writer.execute(() -> {
            synchronized (lockFor(flightId)) {
                File file = journalFile(flightId);
                if (file.exists() && !file.delete()) {
                    CTWarn(TAG, "discardUnfinished could not delete " + flightId);
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
        Writer.execute(() -> removeNowLocked(flightId));
    }

    /**
     * Synchronously delete a crash-recovery journal so Discard survives process death / power loss
     * before the writer queue drains. Logs explicitly when the file remains after a failed delete.
     */
    public static void removeNow(@NonNull String flightId) {
        try {
            Writer.submit(() -> removeNowLocked(flightId)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception error) {
            CTError(TAG, "removeNow failed for " + flightId + "; journal may remain", error);
            // Best-effort direct attempt if the writer is stuck or rejected.
            synchronized (lockFor(flightId)) {
                removeNowLocked(flightId);
            }
        }
    }

    private static void removeNowLocked(@NonNull String flightId) {
        try {
            File file = journalFile(flightId);
            if (!file.exists()) return;
            if (!file.delete()) {
                CTWarn(TAG, "Could not delete journal " + file.getName() + "; file still present");
            } else if (file.exists()) {
                CTWarn(TAG, "Journal delete reported success but file still present: " + file.getName());
            }
        } finally {
            FlightLocks.remove(flightId);
        }
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
                        deleteJournalFile(file, "empty-or-unreadable");
                        continue;
                    }
                    if (recovered.finished) {
                        deleteJournalFile(file, "already-finished");
                        continue;
                    }
                    if (recoverAgainstSavedFlight(recovered, file)) continue;
                    if (DocumentFileCompat.archiveAlreadyPresent(recovered)) {
                        CTDebug(TAG, String.format(Locale.US,
                                "recoverUnfinished(): retiring %s; local archive already exists",
                                recovered.id));
                        deleteJournalFile(file, "archive-already-present");
                        continue;
                    }
                    if (shouldRetireWithoutPromotion(recovered)) {
                        CTDebug(TAG, String.format(Locale.US,
                                "recoverUnfinished(): retiring %s without awaiting-map promotion",
                                recovered.id));
                        if (FlightPublicationLedger.isDeclinedOrSuppressed(recovered.id)) {
                            writeLocalTrackIfPossible(recovered, /*localOnly=*/true);
                        }
                        deleteJournalFile(file, "ledger-or-finished-gate");
                        continue;
                    }
                    FlightOwnershipContext.Snapshot context = recovered.context;
                    boolean hold = context.getRequiresHold();
                    boolean autoPublish = context.getMayAutoPublish();
                    boolean block = context.getBlocksOperatorPublish();
                    CTDebug(TAG, String.format(Locale.US,
                            "recoverUnfinished(): promoting %s (%d points) hold=%s auto=%s block=%s",
                            recovered.id, recovered.points.length(), hold, autoPublish, block));
                    // A live publication still owns this journal. Recovery must not close it
                    // or take the Tracker lease.
                    // Load the awaiting-map row and the interrupted record before deciding
                    // the flight already started or finished. load() marks every row
                    // finished; that flag is not "the flight file was written."
                    boolean onMap = AwaitingMapFlights.publicationAlreadyStarted(recovered.id)
                            || CaltopoInterruptedTrackJournal.hasLiveTrack(recovered.id);
                    if (onMap && CaltopoLiveTrack.hasActivePublication(recovered.id)) {
                        CTDebug(TAG, "recoverUnfinished(): leaving live journal " + recovered.id);
                        continue;
                    }
                    boolean startedOnMap = onMap && !CaltopoLiveTrack.hasActivePublication(recovered.id);
                    // The flight file is written from every journal point before the journal
                    // can be retired. A failed write leaves the .ndjson for the next launch.
                    // Tracker upload of that file is the one archive poll, and only when this
                    // journal's own verified Mine authorizes it. Recovery does not send a claim.
                    if (startedOnMap) {
                        if (!writeLocalTrackIfPossible(recovered, !autoPublish)) {
                            CTWarn(TAG, "recoverUnfinished(): kept " + file.getName()
                                    + "; flight file was not written");
                            continue;
                        }
                        CaltopoInterruptedTrackJournal.replacePointsIfSameFlight(
                                recovered.id, recovered.points);
                    }
                    // Commit-then-delete: only remove the journal after AwaitingMapFlights
                    // open+finish succeed. A kill or failure mid-way leaves the .ndjson.
                    // Legacy and unverified Mine claims stay in review. Known unconfirmed
                    // flights are retained but cannot be published. Only a verified Mine
                    // selection (or one tracker was never asked to verify) may auto-publish.
                    recordRecovered(recovered, false, hold, block);
                    Runnable between = recoverBetweenRecordStepsHook;
                    if (between != null) between.run();
                    recordRecovered(recovered, true, hold, block);
                    if (!startedOnMap) {
                        writeLocalTrackIfPossible(recovered, !autoPublish);
                    }
                    Runnable beforeDelete = recoverBeforeDeleteHook;
                    if (beforeDelete != null) beforeDelete.run();
                    deleteJournalFile(file, "promoted");
                } catch (Exception error) {
                    CTError(TAG, "recoverUnfinished failed for " + file.getName()
                            + "; journal retained", error);
                }
            }
        }
    }

    private static void recordRecovered(
            @NonNull RecoveredFlight recovered, boolean finished, boolean hold, boolean block) {
        FlightOwnershipContext.Snapshot context = recovered.context;
        AwaitingMapFlights.recordOwned(
                recovered.id,
                recovered.remote,
                recovered.label,
                recovered.points,
                finished,
                recovered.map,
                recovered.team,
                hold,
                context.getRecordedPilot(),
                context.getKnown() ? context.getClaim() : "",
                context.getKnown(),
                block);
    }

    /**
     * True when this journal is the same flight as a file already saved under any name.
     * A match never writes a second file. The journal stays if the saved file cannot be shown
     * to hold every journal point.
     */
    private static boolean recoverAgainstSavedFlight(
            @NonNull RecoveredFlight recovered, @NonNull File journal) {
        long[] times = pointTimes(recovered.points);
        if (times.length == 0) return false;
        RecoveredFlightDedup.Lookup lookup = savedFlightLookupForTesting;
        final RecoveredFlightDedup.Decision decision;
        try {
            java.util.List<RecoveredFlightDedup.SavedFlight> saved = lookup != null
                    ? lookup.flights()
                    : FlightArchiveStore.savedFlightsNear(times[0]);
            decision = RecoveredFlightDedup.decide(recovered.remote, times, saved);
        } catch (Exception error) {
            CTWarn(TAG, "recoverUnfinished(): saved-flight scan failed; journal kept on the existing path "
                    + journal.getName(), error);
            return false;
        }
        if (decision.getKind() == RecoveredFlightDedup.Kind.WRITE_NEW) return false;
        if (decision.getKind() == RecoveredFlightDedup.Kind.RETIRE) {
            CTDebug(TAG, String.format(Locale.US,
                    "recoverUnfinished(): retiring %s; %s already holds these points",
                    recovered.id, decision.getFileName()));
            deleteJournalFile(journal, "saved-flight-holds-points");
            return true;
        }
        final boolean merged;
        try {
            merged = lookup != null
                    ? lookup.mergeInto(decision.getDayDirectory(), decision.getFileName(), recovered.points)
                    : FlightArchiveStore.mergeJournalPoints(
                            decision.getDayDirectory(), decision.getFileName(), recovered.points, times);
        } catch (Exception error) {
            CTWarn(TAG, "recoverUnfinished(): kept " + journal.getName()
                    + "; merge into " + decision.getFileName() + " failed", error);
            return true;
        }
        if (merged) {
            CTDebug(TAG, String.format(Locale.US,
                    "recoverUnfinished(): merged %s into %s", recovered.id, decision.getFileName()));
            deleteJournalFile(journal, "merged-into-saved-flight");
        } else {
            CTWarn(TAG, String.format(Locale.US,
                    "recoverUnfinished(): kept %s; %s does not yet hold every journal point",
                    journal.getName(), decision.getFileName()));
        }
        return true;
    }

    @NonNull
    private static long[] pointTimes(@NonNull JSONArray points) {
        java.util.ArrayList<Long> times = new java.util.ArrayList<>();
        for (int i = 0; i < points.length(); i++) {
            JSONArray point = points.optJSONArray(i);
            if (point == null || point.length() < 4) continue;
            long time = point.optLong(3, 0L);
            if (time <= 0L) {
                try {
                    time = Long.parseLong(point.optString(3));
                } catch (NumberFormatException ignored) {
                    time = 0L;
                }
            }
            if (time > 0L) times.add(time);
        }
        long[] out = new long[times.size()];
        for (int i = 0; i < times.size(); i++) out[i] = times.get(i);
        return out;
    }

    private static boolean shouldRetireWithoutPromotion(@NonNull RecoveredFlight recovered) {
        return FlightPublicationRecoveryGate.shouldRetireWithoutPromotion(recovered.id);
    }

    private static void deleteJournalFile(@NonNull File file, @NonNull String reason) {
        if (!file.exists()) return;
        if (file.delete()) {
            if (file.exists()) {
                CTWarn(TAG, "Journal delete reported success but file still present after "
                        + reason + ": " + file.getName());
            }
            return;
        }
        CTWarn(TAG, "Could not delete journal after " + reason + ": " + file.getName()
                + "; file still present");
    }

    /** @return true only when the flight file is durable, so the journal may be retired. */
    private static boolean writeLocalTrackIfPossible(@NonNull RecoveredFlight recovered, boolean localOnly) {
        RecoveredArchiveWriter hook = recoveredArchiveWriterForTesting;
        if (hook != null) {
            try {
                return hook.write(recovered, localOnly);
            } catch (Exception error) {
                CTWarn(TAG, "Local track write skipped for " + recovered.id + ": " + error.getMessage());
                return false;
            }
        }
        try {
            return DocumentFileCompat.writeRecoveredGeoJson(recovered, localOnly);
        } catch (Exception error) {
            CTWarn(TAG, "Local track write skipped for " + recovered.id + ": " + error.getMessage());
            return false;
        }
    }

    /**
     * Points from an unfinished journal with this flight id, or null. Used so a shape
     * update can include points that were not yet in the interrupted-publication snapshot.
     */
    @Nullable
    static JSONArray pointsForFlight(@NonNull String flightId) {
        if (flightId.isEmpty()) return null;
        File file = journalFile(flightId);
        if (!file.isFile()) return null;
        try {
            RecoveredFlight recovered = readJournal(file);
            if (recovered == null || recovered.finished || recovered.points.length() == 0) return null;
            if (!flightId.equals(recovered.id)) return null;
            return recovered.points;
        } catch (Exception error) {
            return null;
        }
    }

    /** Small helper keeps SAF/DocumentFile imports out of the hot journal path. */
    private static final class DocumentFileCompat {
        static boolean archiveAlreadyPresent(@NonNull RecoveredFlight recovered) {
            try {
                return archiveFile(recovered) != null;
            } catch (Exception ignored) {
                return false;
            }
        }

        @Nullable
        private static androidx.documentfile.provider.DocumentFile archiveFile(
                @NonNull RecoveredFlight recovered) {
            long stamp = recovered.firstTimestampMs > 0
                    ? recovered.firstTimestampMs
                    : (recovered.startedAtMs > 0 ? recovered.startedAtMs : 0L);
            if (stamp <= 0L) return null;
            androidx.documentfile.provider.DocumentFile dayDir =
                    CaltopoClient.GetTrackDirForTime(stamp);
            if (dayDir == null) return null;
            String fileName = OperatorArchiveFilename.track(
                    recovered.remote.isEmpty() ? recovered.label : recovered.remote, stamp);
            return dayDir.findFile(fileName);
        }

        static boolean writeRecoveredGeoJson(@NonNull RecoveredFlight recovered, boolean localOnly)
                throws Exception {
            long stamp = recovered.firstTimestampMs > 0
                    ? recovered.firstTimestampMs
                    : (recovered.startedAtMs > 0 ? recovered.startedAtMs : System.currentTimeMillis());
            androidx.documentfile.provider.DocumentFile dayDir =
                    CaltopoClient.GetTrackDirForTime(stamp);
            if (dayDir == null) return false;
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            JSONObject properties = new JSONObject();
            properties.put("title", recovered.label);
            properties.put("r2c_flight_id", recovered.id);
            properties.put("r2c_recovered", true);
            JSONObject r2cProp = new JSONObject();
            r2cProp.put("rid", recovered.remote);
            r2cProp.put("local_archive_only", localOnly);
            FlightOwnershipContext.Snapshot context = recovered.context;
            // Only a pilot actually stored on the journal is written. Legacy files omit it.
            if (!context.getRecordedPilot().isEmpty()) r2cProp.put("owner", context.getRecordedPilot());
            if (context.getKnown() && !context.getOrg().isEmpty()) r2cProp.put("org", context.getOrg());
            if (context.getKnown() && !context.getModel().isEmpty()) r2cProp.put("model", context.getModel());
            if (context.getKnown() && !context.getMappedId().isEmpty()) r2cProp.put("mid", context.getMappedId());
            boolean adverse = context.getRequiresHold();
            r2cProp.put("tracker_claim_pending", adverse && context.getKnown() && context.getConfirmation().equals(FlightOwnershipContext.MINE));
            r2cProp.put("tracker_upload_authorized", context.getMayAutoPublish());
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
            String payload = collection.toString();
            if (coordinateCount(payload) != recovered.points.length()) return false;
            // A short file is not this flight. Rewrite it. A file that already holds
            // every journal point is left as-is. An unreadable file keeps the journal.
            byte[] current = FlightArchiveStore.readFile(dayDir, fileName);
            if (current != null && coordinateCount(new String(current, StandardCharsets.UTF_8))
                    == recovered.points.length()) {
                CTDebug(TAG, "writeRecoveredGeoJson(): archive already holds every point for " + fileName);
                return true;
            }
            if (current == null && archiveFile(recovered) != null) return false;
            boolean wrote = FlightArchiveStore.writeFlight(
                    dayDir,
                    fileName,
                    payload,
                    recovered.label,
                    recovered.remote,
                    recovered.id,
                    new ArrayList<>());
            if (!wrote) return false;
            byte[] readBack = FlightArchiveStore.readFile(dayDir, fileName);
            if (readBack == null
                    || coordinateCount(new String(readBack, StandardCharsets.UTF_8)) != recovered.points.length()) {
                return false;
            }
            if (localOnly) {
                FlightPublicationLedger.remember(
                        recovered.id, FlightPublicationLedger.Disposition.LOCAL_ARCHIVED);
            }
            return true;
        }

        private static int coordinateCount(String geoJson) {
            try {
                JSONArray coordinates = new JSONObject(geoJson)
                        .getJSONArray("features")
                        .getJSONObject(0)
                        .getJSONObject("geometry")
                        .getJSONArray("coordinates");
                return coordinates.length();
            } catch (Exception error) {
                return -1;
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
        FlightOwnershipContext.Snapshot context = FlightOwnershipContext.legacy();
        boolean sawContext = false;
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
                } else if (CONTEXT.equals(type)) {
                    context = FlightOwnershipContext.fromJson(row);
                    sawContext = true;
                } else if (FINISHED.equals(type)) {
                    finished = true;
                }
            }
        }
        if (id.isEmpty()) return null;
        if (!sawContext) context = FlightOwnershipContext.legacy();
        return new RecoveredFlight(id, remote, label, map, team, points, finished,
                firstTimestampMs, startedAtMs, context);
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
        final FlightOwnershipContext.Snapshot context;

        RecoveredFlight(String id, String remote, String label, String map, String team,
                        JSONArray points, boolean finished, long firstTimestampMs) {
            this(id, remote, label, map, team, points, finished, firstTimestampMs, 0L,
                    FlightOwnershipContext.legacy());
        }

        RecoveredFlight(String id, String remote, String label, String map, String team,
                        JSONArray points, boolean finished, long firstTimestampMs, long startedAtMs) {
            this(id, remote, label, map, team, points, finished, firstTimestampMs, startedAtMs,
                    FlightOwnershipContext.legacy());
        }

        RecoveredFlight(String id, String remote, String label, String map, String team,
                        JSONArray points, boolean finished, long firstTimestampMs, long startedAtMs,
                        FlightOwnershipContext.Snapshot context) {
            this.id = id;
            this.remote = remote;
            this.label = label;
            this.map = map;
            this.team = team;
            this.points = points;
            this.finished = finished;
            this.firstTimestampMs = firstTimestampMs;
            this.startedAtMs = startedAtMs;
            this.context = context == null ? FlightOwnershipContext.legacy() : context;
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
