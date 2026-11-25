package org.ncssar.rid2caltopo.data;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the batched copy of the session log (Log_*.txt) from app-private storage to the Archive
 * Dir. All card I/O runs on one low-priority background thread, never on the main thread or
 * the diagnostic log writer, so a slow or missing card cannot hold up logging, startup, the
 * stream server, the pod link or the live views.
 */
final class SessionLogCardSync {
    private static final String TAG = "SessionLogCardSync";
    static final String STAGING_DIR_NAME = "session-log-staging";
    static final long BATCH_INTERVAL_MSEC = 3_000L;
    /** Leftover logs from killed runs wait until startup and live views have settled. */
    static final long LEFTOVER_DELAY_MSEC = 15_000L;
    static final long LEFTOVER_MAX_AGE_MSEC = 14L * 24L * 60L * 60L * 1000L;
    static final long FINAL_SYNC_WAIT_MSEC = 3_000L;

    private static final ScheduledExecutorService executor = createExecutor();
    private static final AtomicBoolean leftoversScheduled = new AtomicBoolean(false);
    private static final Object lock = new Object();
    private static StagedCardLog active;
    private static String activeKey;
    private static Context activeContext;
    private static ScheduledFuture<?> tick;

    private static ScheduledExecutorService createExecutor() {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "r2c-log-card-sync");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    private SessionLogCardSync() { }

    @NonNull
    static File stagingDir(@NonNull Context context) {
        return new File(context.getNoBackupFilesDir(), STAGING_DIR_NAME);
    }

    /**
     * Starts staging a new session log whose Archive Dir copy is {@code cardUri} (already
     * created, empty). Returns the private stream the log writer appends to.
     */
    @NonNull
    static OutputStream start(@NonNull Context context, @NonNull String name, @NonNull Uri cardUri)
            throws IOException {
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        StagedCardLog log = StagedCardLog.create(stagingDir(appContext), name, cardUri.toString(),
                new DocumentCardTarget(appContext, cardUri));
        OutputStream stream = log.openPrivateStream();
        synchronized (lock) {
            active = log;
            activeKey = cardUri.toString();
            activeContext = appContext;
            if (tick == null) {
                tick = executor.scheduleWithFixedDelay(SessionLogCardSync::periodicBatch,
                        BATCH_INTERVAL_MSEC, BATCH_INTERVAL_MSEC, TimeUnit.MILLISECONDS);
            }
        }
        scheduleLeftoverFlush(appContext);
        return stream;
    }

    /** Key prefix for a log whose Archive Dir file could not be created yet (read-only card). */
    static final String PENDING_PREFIX = "pending:";

    /**
     * Starts staging a session log whose Archive Dir file cannot be created now (the archive
     * is read-only or failing). The card file {@code dayDir/name} is created on the first
     * copy once the archive is writable again, or from the leftovers of a later run.
     */
    @NonNull
    static OutputStream startDeferred(@NonNull Context context, @NonNull String dayDir, @NonNull String name)
            throws IOException {
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        String key = pendingKey(dayDir, name);
        StagedCardLog log = StagedCardLog.create(stagingDir(appContext), name, key,
                new DeferredCardTarget(appContext, dayDir, name));
        OutputStream stream = log.openPrivateStream();
        synchronized (lock) {
            active = log;
            activeKey = key;
            activeContext = appContext;
            if (tick == null) {
                tick = executor.scheduleWithFixedDelay(SessionLogCardSync::periodicBatch,
                        BATCH_INTERVAL_MSEC, BATCH_INTERVAL_MSEC, TimeUnit.MILLISECONDS);
            }
        }
        scheduleLeftoverFlush(appContext);
        return stream;
    }

    @NonNull
    static String pendingKey(@NonNull String dayDir, @NonNull String name) {
        return PENDING_PREFIX + dayDir + "/" + name;
    }

    /** Archive writable again: copy the active log and any leftovers now, in the background. */
    static void catchUp(@NonNull Context context) {
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            executor.execute(() -> {
                Snapshot s = snapshot();
                if (s != null) runBatch(s, System.currentTimeMillis());
                flushLeftovers(appContext);
            });
        } catch (RuntimeException ignored) { }
    }

    @NonNull
    static StagedCardLog.CardTarget targetFor(@NonNull Context context, @NonNull String key) {
        if (key.startsWith(PENDING_PREFIX)) {
            String path = key.substring(PENDING_PREFIX.length());
            int slash = path.indexOf('/');
            if (slash > 0 && slash < path.length() - 1) {
                return new DeferredCardTarget(context, path.substring(0, slash), path.substring(slash + 1));
            }
        }
        String retargeted = retargetFromRemovedCard(context, key);
        if (retargeted != null) return targetFor(context, retargeted);
        return new DocumentCardTarget(context, Uri.parse(key));
    }

    /**
     * A leftover log whose archive file was on a card that has since been swapped out goes to
     * the same day folder in the active archive folder instead of waiting for that card.
     */
    @androidx.annotation.Nullable
    private static String retargetFromRemovedCard(@NonNull Context context, @NonNull String key) {
        try {
            Uri active = CaltopoClient.GetArchiveUri();
            String activeVolume = null;
            if (active != null && "com.android.externalstorage.documents".equals(active.getAuthority())) {
                activeVolume = android.provider.DocumentsContract.getTreeDocumentId(active).split(":", 2)[0];
            }
            String retargeted = org.ncssar.rid2caltopo.storage.DetachedPreviousStorage.INSTANCE.retargetSessionLogKey(
                    key, activeVolume,
                    volume -> org.ncssar.rid2caltopo.storage.OperationalStorage.INSTANCE.removableVolumeMounted(context, volume));
            if (retargeted != null) CaltopoClient.CTDebug(TAG, "Leftover session log's card is gone; copying it to " + retargeted.substring(PENDING_PREFIX.length()) + " in the active archive folder");
            return retargeted;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean archiveWritable() {
        return org.ncssar.rid2caltopo.app.ArchiveWritability.getWritable();
    }

    /** Copies pending log bytes to the card now (app stop). Does not wait. */
    static void requestBatch() {
        try {
            executor.execute(() -> {
                Snapshot s = snapshot();
                if (s != null) runBatch(s, System.currentTimeMillis());
            });
        } catch (RuntimeException ignored) { }
    }

    /**
     * Session end: the private stream is already closed. Copies the remainder to the card and
     * removes the private copy when the card holds every byte. Waits at most
     * {@link #FINAL_SYNC_WAIT_MSEC} (never on the main thread); anything not copied stays in
     * the private file and is caught up on the next start.
     */
    static void finish() {
        finish(true);
    }

    /**
     * A full session log rolled over to a new file: copy the finished file's remainder in the
     * background without waiting (the caller is the diagnostic log writer).
     */
    static void finishRolledOver() {
        finish(false);
    }

    private static void finish(boolean wait) {
        Snapshot s;
        synchronized (lock) {
            if (active == null) return;
            s = new Snapshot(active, activeKey, activeContext);
            active = null;
            activeKey = null;
            activeContext = null;
            if (tick != null) {
                tick.cancel(false);
                tick = null;
            }
        }
        final Snapshot last = s;
        Future<?> done;
        try {
            done = executor.submit(() -> {
                // A read-only archive keeps the private copy; the next start catches it up.
                if (!archiveWritable()) return;
                runBatch(last, System.currentTimeMillis());
                last.log.discardPrivateIfCaughtUp();
            });
        } catch (RuntimeException error) {
            return;
        }
        Looper main = Looper.getMainLooper();
        if (!wait || (main != null && Looper.myLooper() == main)) return;
        try {
            done.get(FINAL_SYNC_WAIT_MSEC, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            Log.w(TAG, "Final session log copy still queued: " + error.getClass().getSimpleName());
        }
    }

    /** Once per process, in the background, after startup has settled. */
    static void scheduleLeftoverFlush(@NonNull Context context) {
        if (!leftoversScheduled.compareAndSet(false, true)) return;
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            executor.schedule(() -> flushLeftovers(appContext), LEFTOVER_DELAY_MSEC,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            leftoversScheduled.set(false);
        }
    }

    private static void flushLeftovers(@NonNull Context context) {
        File dir = stagingDir(context);
        if (!dir.isDirectory()) return;
        // Kept for later while the archive is read-only; catchUp() runs once it is writable.
        if (!archiveWritable()) return;
        Snapshot s = snapshot();
        Set<String> exclude = s == null ? Collections.emptySet()
                : Collections.singleton(s.log.privateFile().getName());
        StagedCardLog.LeftoverReport report = StagedCardLog.flushLeftovers(dir, exclude,
                key -> targetFor(context, key),
                System.currentTimeMillis(), LEFTOVER_MAX_AGE_MSEC);
        if (!report.flushed.isEmpty() || !report.kept.isEmpty() || !report.expired.isEmpty()) {
            CaltopoClient.CTDebug(TAG, String.format(Locale.US,
                    "Leftover session logs: copied to Archive Dir=%s kept for retry=%s expired=%s",
                    report.flushed, report.kept, report.expired));
        }
    }

    private static void periodicBatch() {
        Snapshot s = snapshot();
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (s.log.pendingBytes() <= 0 || !s.log.shouldAttempt(now)) return;
        // Back off while the archive is read-only/failing; bytes stay in app storage.
        if (!archiveWritable()) return;
        runBatch(s, now);
    }

    private static void runBatch(@NonNull Snapshot s, long nowMsec) {
        boolean wasFailing = s.log.isFailing();
        StagedCardLog.Result result = s.log.syncToCard(nowMsec);
        if (result == StagedCardLog.Result.APPENDED && s.context != null) {
            String key = s.key;
            if (key != null && key.startsWith(PENDING_PREFIX)) {
                Uri resolved = DeferredCardTarget.resolvedUri(s.context, key);
                key = resolved == null ? null : resolved.toString();
            }
            org.ncssar.rid2caltopo.app.FlightStorage.appendCompleted(s.context, key,
                    s.log.lastAppendedBytes());
        }
        boolean failing = s.log.isFailing();
        if (failing && !wasFailing) {
            CaltopoClient.CTWarn(TAG, String.format(Locale.US,
                    "Session log copy to Archive Dir %s (%d bytes kept in app storage); will retry.",
                    result == StagedCardLog.Result.MISMATCH ? "stopped: card file differs" : "failed",
                    s.log.pendingBytes()));
        } else if (wasFailing && !failing) {
            CaltopoClient.CTDebug(TAG, "Session log copy to Archive Dir caught up.");
        }
    }

    @Nullable
    private static Snapshot snapshot() {
        synchronized (lock) {
            return active == null ? null : new Snapshot(active, activeKey, activeContext);
        }
    }

    private static final class Snapshot {
        final StagedCardLog log;
        final String key;
        final Context context;
        Snapshot(StagedCardLog log, String key, Context context) {
            this.log = log; this.key = key; this.context = context;
        }
    }

    /**
     * Archive Dir file created on first use: {@code <archive>/<dayDir>/<name>}. Used when the
     * archive was read-only when the session started.
     */
    static final class DeferredCardTarget implements StagedCardLog.CardTarget {
        private static final java.util.concurrent.ConcurrentHashMap<String, Uri> resolved =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final Context context;
        private final String dayDir;
        private final String name;

        DeferredCardTarget(@NonNull Context context, @NonNull String dayDir, @NonNull String name) {
            this.context = context;
            this.dayDir = dayDir;
            this.name = name;
        }

        @Nullable
        static Uri resolvedUri(@NonNull Context context, @NonNull String key) {
            return resolved.get(key);
        }

        @NonNull
        private Uri resolveOrCreate() throws IOException {
            String key = pendingKey(dayDir, name);
            Uri known = resolved.get(key);
            if (known != null) return known;
            if (!archiveWritable()) throw new IOException("Archive Dir is not writable yet");
            DocumentFile archive = CaltopoClient.GetArchiveDir();
            if (archive == null) throw new IOException("Archive Dir is unavailable");
            DocumentFile day = archive.findFile(dayDir);
            if (day == null) day = archive.createDirectory(dayDir);
            if (day == null) throw new IOException("unable to create " + dayDir);
            DocumentFile file = day.findFile(name);
            if (file == null) file = DocumentFileCompat.createFileWithExactName(day, "text/plain", name);
            if (file == null) throw new IOException("unable to create " + dayDir + "/" + name);
            resolved.put(key, file.getUri());
            CaltopoClient.CTDebug(TAG, "Session log kept in tablet storage now has its archive file " + dayDir + "/" + name);
            return file.getUri();
        }

        @Override
        public long length() throws IOException {
            Uri uri = resolveOrCreate();
            return new DocumentCardTarget(context, uri).length();
        }

        @NonNull
        @Override
        public OutputStream openAppend() throws IOException {
            return new DocumentCardTarget(context, resolveOrCreate()).openAppend();
        }
    }

    /** Archive Dir file reached through SAF (or a plain file for the temporary archive). */
    static final class DocumentCardTarget implements StagedCardLog.CardTarget {
        private final Context context;
        private final Uri uri;

        DocumentCardTarget(@NonNull Context context, @NonNull Uri uri) {
            this.context = context;
            this.uri = uri;
        }

        @Override
        public long length() {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) return -1;
                File file = new File(path);
                return file.isFile() ? file.length() : -1;
            }
            DocumentFile document = DocumentFile.fromSingleUri(context, uri);
            if (document == null || !document.exists()) return -1;
            return document.length();
        }

        @NonNull
        @Override
        public OutputStream openAppend() throws IOException {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) throw new IOException("no path in " + uri);
                return new FileOutputStream(path, true);
            }
            OutputStream stream = context.getContentResolver().openOutputStream(uri, "wa");
            if (stream == null) throw new IOException("openOutputStream returned null for " + uri);
            return stream;
        }
    }
}
