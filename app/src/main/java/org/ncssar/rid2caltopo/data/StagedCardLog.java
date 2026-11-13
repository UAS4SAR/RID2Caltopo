package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Session log staged in app-private storage and copied to the Archive Dir in short batches.
 *
 * <p>Android kills any process that holds a file open on removable storage when the volume is
 * unmounted (vold sends SIGINT). On Nov 12, 2026 the MediaProvider ran out of memory, the SD
 * card was unmounted and R2C was killed because its Log_ file on the card was open. The log
 * writer now appends only to a private file; the card copy is opened, appended, flushed and
 * closed per batch, so no card handle stays open between batches.
 *
 * <p>The card file only ever receives a byte-for-byte prefix of the private file, so the
 * finished card file is identical to what the old direct write produced. When a batch fails
 * (card missing, provider gone), the private file keeps every byte and the next batch first
 * re-reads the card file length and appends from there. A private file left by a killed run is
 * found by {@link #flushLeftovers} on the next start and caught up the same way.
 */
final class StagedCardLog {
    /** The Archive Dir copy of one session log. */
    interface CardTarget {
        /** Current size of the card file in bytes, or -1 if it is missing or unreadable. */
        long length() throws IOException;
        /** Opens the card file for appending. The caller closes it after each batch. */
        @NonNull OutputStream openAppend() throws IOException;
    }

    interface TargetResolver {
        @Nullable CardTarget resolve(@NonNull String key);
    }

    enum Result { UP_TO_DATE, APPENDED, DEFERRED, FAILED, MISMATCH }

    static final String TARGET_SUFFIX = ".target";
    static final long MIN_RETRY_MSEC = 3_000L;
    static final long MAX_RETRY_MSEC = 60_000L;
    private static final int COPY_CHUNK = 64 * 1024;

    private final File privateFile;
    private final CardTarget target;
    private long cardOffset;
    private boolean resync;
    private boolean mismatch;
    private int consecutiveFailures;
    private long nextAttemptAtMsec;
    private long lastAppendedBytes;

    private StagedCardLog(@NonNull File privateFile, @NonNull CardTarget target, boolean resync) {
        this.privateFile = privateFile;
        this.target = target;
        this.resync = resync;
    }

    /**
     * Starts a new session log. {@code key} identifies the card file (its URI) and is saved next
     * to the private file so a later process can finish the copy. The card file must be empty.
     */
    @NonNull
    static StagedCardLog create(@NonNull File stagingDir, @NonNull String name,
                                @NonNull String key, @NonNull CardTarget target) throws IOException {
        if (!stagingDir.isDirectory() && !stagingDir.mkdirs()) {
            throw new IOException("unable to create " + stagingDir);
        }
        File privateFile = new File(stagingDir, name);
        writeTargetKey(targetFileFor(privateFile), key);
        // Truncate in case a stale file with the same name exists; the card file is new.
        new FileOutputStream(privateFile, false).close();
        return new StagedCardLog(privateFile, target, false);
    }

    @NonNull File privateFile() { return privateFile; }

    /** Append-only stream to the private file. Unbuffered, so each write is on disk at once. */
    @NonNull
    OutputStream openPrivateStream() throws IOException {
        return new FileOutputStream(privateFile, true);
    }

    synchronized long pendingBytes() {
        return Math.max(0L, privateFile.length() - cardOffset);
    }

    synchronized long cardOffset() { return cardOffset; }

    synchronized long lastAppendedBytes() { return lastAppendedBytes; }

    synchronized boolean isFailing() { return consecutiveFailures > 0 || mismatch; }

    synchronized boolean isMismatched() { return mismatch; }

    /** Periodic batches respect a backoff after failures; final/forced batches do not. */
    synchronized boolean shouldAttempt(long nowMsec) {
        return !mismatch && nowMsec >= nextAttemptAtMsec;
    }

    /**
     * Copies every byte the private file has beyond the card copy. Opens, appends, flushes and
     * closes the card file within this call. Never throws.
     */
    synchronized Result syncToCard(long nowMsec) {
        lastAppendedBytes = 0;
        if (mismatch) return Result.MISMATCH;
        try {
            long privateLength = privateFile.length();
            if (resync) {
                long cardLength = target.length();
                if (cardLength < 0) return failed(nowMsec);
                if (cardLength > privateLength) {
                    // The card file is not a prefix of this log. Never write into it.
                    mismatch = true;
                    return Result.MISMATCH;
                }
                cardOffset = cardLength;
                resync = false;
            }
            if (privateLength <= cardOffset) {
                succeeded();
                return Result.UP_TO_DATE;
            }
            long appended = 0;
            OutputStream out = target.openAppend();
            try (RandomAccessFile in = new RandomAccessFile(privateFile, "r")) {
                in.seek(cardOffset);
                byte[] buffer = new byte[COPY_CHUNK];
                long remaining = privateLength - cardOffset;
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                    appended += read;
                }
                out.flush();
            } finally {
                out.close();
            }
            cardOffset += appended;
            lastAppendedBytes = appended;
            succeeded();
            return Result.APPENDED;
        } catch (IOException | RuntimeException error) {
            // A partial append leaves the card length unknown: re-read it before the next batch.
            resync = true;
            return failed(nowMsec);
        }
    }

    /** Removes the private copy once the card copy holds every byte. */
    synchronized boolean discardPrivateIfCaughtUp() {
        if (mismatch || resync || pendingBytes() > 0) return false;
        boolean deleted = privateFile.delete();
        //noinspection ResultOfMethodCallIgnored
        targetFileFor(privateFile).delete();
        return deleted;
    }

    private Result failed(long nowMsec) {
        consecutiveFailures++;
        long delay = MIN_RETRY_MSEC << Math.min(5, consecutiveFailures - 1);
        nextAttemptAtMsec = nowMsec + Math.min(MAX_RETRY_MSEC, delay);
        return Result.FAILED;
    }

    private void succeeded() {
        consecutiveFailures = 0;
        nextAttemptAtMsec = 0;
    }

    /** Outcome of one leftover-log pass. */
    static final class LeftoverReport {
        final List<String> flushed = new ArrayList<>();
        final List<String> kept = new ArrayList<>();
        final List<String> expired = new ArrayList<>();
    }

    /**
     * Finishes copying private logs left by earlier runs (killed, crashed or card missing at
     * shutdown). Each one is caught up from the card file's current length and then deleted.
     * Logs that still cannot reach the card are kept for the next start, unless older than
     * {@code maxAgeMsec}. Files named in {@code exclude} (the live session) are not touched.
     */
    @NonNull
    static LeftoverReport flushLeftovers(@NonNull File stagingDir, @NonNull Set<String> exclude,
                                         @NonNull TargetResolver resolver,
                                         long nowMsec, long maxAgeMsec) {
        LeftoverReport report = new LeftoverReport();
        File[] files = stagingDir.listFiles();
        if (files == null) return report;
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.endsWith(".txt") || exclude.contains(name)) continue;
            String key = readTargetKey(targetFileFor(file));
            CardTarget target = key == null ? null : resolver.resolve(key);
            boolean done = false;
            if (target != null) {
                StagedCardLog leftover = new StagedCardLog(file, target, true);
                Result result = leftover.syncToCard(nowMsec);
                done = (result == Result.APPENDED || result == Result.UP_TO_DATE)
                        && leftover.discardPrivateIfCaughtUp();
            }
            if (done) {
                report.flushed.add(name);
            } else if (nowMsec - file.lastModified() > maxAgeMsec) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                targetFileFor(file).delete();
                report.expired.add(name);
            } else {
                report.kept.add(name);
            }
        }
        // Drop target sidecars whose log is gone.
        File[] sidecars = stagingDir.listFiles((dir, n) -> n.endsWith(TARGET_SUFFIX));
        if (sidecars != null) for (File sidecar : sidecars) {
            String logName = sidecar.getName().substring(0,
                    sidecar.getName().length() - TARGET_SUFFIX.length());
            if (!new File(stagingDir, logName).exists() && !exclude.contains(logName)) {
                //noinspection ResultOfMethodCallIgnored
                sidecar.delete();
            }
        }
        return report;
    }

    @NonNull
    static File targetFileFor(@NonNull File privateFile) {
        return new File(privateFile.getParentFile(), privateFile.getName() + TARGET_SUFFIX);
    }

    private static void writeTargetKey(@NonNull File file, @NonNull String key) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(key.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("unable to save " + file);
        }
    }

    @Nullable
    private static String readTargetKey(@NonNull File file) {
        if (!file.isFile()) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String key = new String(bytes, StandardCharsets.UTF_8).trim();
            return key.isEmpty() ? null : key;
        } catch (IOException error) {
            return null;
        }
    }
}
