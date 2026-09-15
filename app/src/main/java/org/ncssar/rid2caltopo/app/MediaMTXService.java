package org.ncssar.rid2caltopo.app;

import static android.system.OsConstants.SIGRTMAX;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

import static java.lang.Thread.sleep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.data.MediaMTXBootstrap;
import org.ncssar.rid2caltopo.data.MediaMTXConfig;
import org.ncssar.rid2caltopo.data.MediaMTXEvent;
import org.ncssar.rid2caltopo.data.MediaMTXNative;
import org.ncssar.rid2caltopo.data.MediaMTXRecordingSync;
import org.ncssar.rid2caltopo.data.MediaMTXStatus;
import org.ncssar.rid2caltopo.data.MediaMTXStructuredDispatcher;
import org.ncssar.rid2caltopo.data.CaltopoClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;


public class MediaMTXService extends Service {
    private static final String TAG = "MediaMTXService";
    private static final String ACTION_STOP_SERVICE = "STOP_SERVICE";
    private static final String ACTION_RESTART_SERVICE = "RESTART_SERVICE";
    private static final String CHANNEL_ID = "streaming";
    private static final int MEDIA_MTX_NOTIFICATION_ID = 2;
    private static final ExecutorService recordingSyncExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mediamtx-recording-sync");
                thread.setDaemon(true);
                return thread;
            });
    private static boolean listenersRegistered = false;
    private static boolean recordingListenerRegistered = false;
    private static int processPid = 0;
    private static volatile boolean serviceRunning = false;
    private static volatile int expectedRestartExitPid = 0;
    private boolean foregroundStarted = false;
    private boolean foregroundStartBlocked = false;
    private final Object nativeControlLock = new Object();
    private final ExecutorService nativeControlExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mediamtx-native-control");
        thread.setDaemon(false);
        return thread;
    });
    private NativeControlAction activeNativeControlAction;
    private NativeControlAction pendingNativeControlAction;
    private boolean nativeControlWorkerScheduled = false;
    private boolean serviceDestroying = false;

    private enum NativeControlAction {
        START,
        RESTART
    }

    public static boolean IsRunning() { return serviceRunning; }

    public static int findNativeServerPid() {
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries == null) return 0;
        final int myPid = Process.myPid();
        final int myUid = Process.myUid();
        for (File entry : entries) {
            String name = entry.getName();
            if (name == null || name.isEmpty()) continue;
            boolean numeric = true;
            for (int i = 0; i < name.length(); i++) {
                if (!Character.isDigit(name.charAt(i))) {
                    numeric = false;
                    break;
                }
            }
            if (!numeric) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (pid <= 0 || pid == myPid) continue;
            try {
                File statusFile = new File(entry, "status");
                String status = new String(
                        java.nio.file.Files.readAllBytes(statusFile.toPath()),
                        StandardCharsets.UTF_8
                );
                if (!status.contains("Uid:\t" + myUid) && !status.contains("Uid:\t" + myUid + "\t")) {
                    continue;
                }
                File cmdlineFile = new File(entry, "cmdline");
                byte[] raw = java.nio.file.Files.readAllBytes(cmdlineFile.toPath());
                if (raw.length == 0) continue;
                String cmdline = new String(raw, StandardCharsets.UTF_8).replace('\u0000', ' ').trim();
                if (cmdline.contains("mediamtx")) {
                    return pid;
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    public static void onNativeProcessExit(int pid, int status, int signaled) {
        CTDebug(TAG, "MediaMTX exited pid=" + pid +
                " status=" + status +
                " signaled=" + signaled);
        if (pid > 0 && pid == expectedRestartExitPid) {
            expectedRestartExitPid = 0;
            CTDebug(TAG, "Ignoring expected MediaMTX exit during app-requested restart.");
            return;
        }
        org.ncssar.rid2caltopo.video.StreamRegistry.onServerStopped();
        processPid = 0;
        serviceRunning = false;
        String description;
        if (0 != signaled) {
            if (signaled == SIGRTMAX) {
                description = "terminated by Android";
            } else {
                description = "terminated by signal " + signaled;
            }
        } else {
            description = "exited with status " + status;
        }
        MediaMTXStatus.onServerExited(description);
    }

    private volatile boolean storageCaptureBlocked = false;

    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        FlightStorage.observe(getApplicationContext(), () -> {
            if (isServiceDestroying()) return;
            boolean blocked = FlightStorage.INSTANCE.getCaptureBlocked();
            if (storageCaptureBlocked != blocked) {
                storageCaptureBlocked = blocked;
                if (CaltopoClient.GetCaptureVideoStreamsFlag()) enqueueNativeControl(NativeControlAction.RESTART);
            }
        });
        Intent launchIntent = new Intent(this, R2CActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                1,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Drone Video Relay")
                .setContentText("Streaming active")
                .setSmallIcon(R.drawable.ic_notification_drone)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .build();

        if (startForegroundSafely(notification)) {
            serviceRunning = true;
        } else {
            serviceRunning = false;
            foregroundStartBlocked = true;
            MediaMTXStatus.onServerExited("foreground start blocked by Android");
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (foregroundStartBlocked) {
            CTInfo(TAG, "Ignoring start request because foreground launch was blocked.");
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP_SERVICE.equals(action)) {
            CTDebug(TAG, "MediaMTXService shutting down.");
            stopForegroundSafely();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART_SERVICE.equals(action)) {
            CTDebug(TAG, "MediaMTXService queueing native server restart.");
            ensureListenersRegistered();
            processPid = Process.myPid();
            enqueueNativeControl(NativeControlAction.RESTART);
            return START_NOT_STICKY;
        }

        CTDebug(TAG, "MediaMTXService.onStartCommand()");

        if (processPid != 0 && processPid != Process.myPid()) {
            CTInfo(TAG, "MediaMTX already running in pid " + processPid);
            return START_NOT_STICKY;
        }
        ensureListenersRegistered();

        processPid = Process.myPid();
        CTDebug(TAG, "MediaMTX service started in pid " + processPid);

        enqueueNativeControl(NativeControlAction.START);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        org.ncssar.rid2caltopo.video.StreamRegistry.onServerStopped();
        FlightStorage.stopObserving();
        synchronized (nativeControlLock) {
            serviceDestroying = true;
            pendingNativeControlAction = null;
        }
        serviceRunning = false;
        foregroundStartBlocked = false;
        processPid = 0;
        stopForegroundSafely();
        final Context appContext = getApplicationContext();
        nativeControlExecutor.execute(() -> {
            MediaMTXNative.stop();
            // Android 14+ enforces a short foreground-service shutdown timeout. Keep the
            // potentially slow recording copy off the main thread and serialized after stop.
            MediaMTXRecordingSync.syncAll(appContext, null);
        });
        nativeControlExecutor.shutdown();
        super.onDestroy();
    }

    private void enqueueNativeControl(NativeControlAction requestedAction) {
        synchronized (nativeControlLock) {
            if (serviceDestroying || nativeControlExecutor.isShutdown()) {
                CTInfo(TAG, "Ignoring " + requestedAction + " request while service is stopping.");
                return;
            }

            if (requestedAction == NativeControlAction.RESTART) {
                if (activeNativeControlAction == NativeControlAction.RESTART
                        || pendingNativeControlAction == NativeControlAction.RESTART) {
                    CTDebug(TAG, "Coalescing duplicate MediaMTX restart request.");
                    return;
                }
                // A restart includes startup, so it supersedes a start that has not begun.
                pendingNativeControlAction = NativeControlAction.RESTART;
            } else {
                if (activeNativeControlAction != null || pendingNativeControlAction != null) {
                    CTDebug(TAG, "Coalescing duplicate MediaMTX start request.");
                    return;
                }
                pendingNativeControlAction = NativeControlAction.START;
            }

            if (!nativeControlWorkerScheduled) {
                nativeControlWorkerScheduled = true;
                nativeControlExecutor.execute(this::drainNativeControlRequests);
            }
        }
    }

    private void drainNativeControlRequests() {
        while (true) {
            final NativeControlAction action;
            synchronized (nativeControlLock) {
                if (serviceDestroying || pendingNativeControlAction == null) {
                    activeNativeControlAction = null;
                    nativeControlWorkerScheduled = false;
                    return;
                }
                action = pendingNativeControlAction;
                pendingNativeControlAction = null;
                activeNativeControlAction = action;
            }

            try {
                CTDebug(TAG, "Running MediaMTX " + action + " on " + Thread.currentThread().getName());
                if (action == NativeControlAction.RESTART) {
                    restartNativeServer();
                } else if (MediaMTXNative.currentPid() > 0) {
                    CTDebug(TAG, "MediaMTX native server is already running; coalescing start request.");
                } else {
                    startNativeServer();
                }
            } catch (RuntimeException e) {
                CTError(TAG, "MediaMTX " + action + " worker failed", e);
                processPid = 0;
                serviceRunning = false;
                MediaMTXStatus.onServerExited(action.name().toLowerCase(Locale.US) + " exception");
                stopSelf();
            } finally {
                synchronized (nativeControlLock) {
                    activeNativeControlAction = null;
                }
            }
        }
    }

    private boolean isServiceDestroying() {
        synchronized (nativeControlLock) {
            return serviceDestroying;
        }
    }

    private void ensureListenersRegistered() {
        if (!listenersRegistered) {
            MediaMTXBootstrap.init();
            listenersRegistered = true;
        }
        if (!recordingListenerRegistered) {
            final Context appContext = getApplicationContext();
            MediaMTXStructuredDispatcher.addListener(event -> {
                if (!CaltopoClient.GetCaptureVideoStreamsFlag()) return Unit.INSTANCE;
                if (event instanceof MediaMTXEvent.RecordFileCompleted) {
                    MediaMTXEvent.RecordFileCompleted completed =
                            (MediaMTXEvent.RecordFileCompleted) event;
                    CTDebug(TAG, "Recording file complete path=" + completed.getFilePath());
                    recordingSyncExecutor.execute(
                            () -> MediaMTXRecordingSync.syncAll(appContext, completed.getPath()));
                }
                return Unit.INSTANCE;
            });
            recordingListenerRegistered = true;
        }
    }

    private void restartNativeServer() {
        MediaMTXRecordingSync.syncAll(getApplicationContext(), null);
        if (isServiceDestroying()) return;

        int restartingPid = MediaMTXNative.currentPid();
        if (restartingPid > 0) {
            expectedRestartExitPid = restartingPid;
        }
        org.ncssar.rid2caltopo.video.StreamRegistry.onServerStopped();
        MediaMTXNative.stop();
        if (!waitForNativeServerStop(restartingPid)) {
            expectedRestartExitPid = 0;
            if (isServiceDestroying()) return;
            CTError(TAG, "Timed out waiting for MediaMTX to stop before restart; keeping existing server state.");
            MediaMTXStatus.INSTANCE.onServerStarted("existing");
            serviceRunning = true;
            return;
        }
        if (isServiceDestroying()) return;
        startNativeServer();
    }

    private boolean waitForNativeServerStop(int pid) {
        if (pid <= 0) {
            return true;
        }
        for (int i = 0; i < 60; i++) {
            int currentPid = MediaMTXNative.currentPid();
            if (currentPid <= 0 || currentPid != pid) {
                return true;
            }
            try {
                sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void startNativeServer() {
        try {
            MediaMTXRecordingSync.syncAll(getApplicationContext(), null);
            if (isServiceDestroying()) return;

            File bin = extractAsset("mediamtx");
            File cfg = buildConfigAsset("mediamtx.yml");
            if (isServiceDestroying()) return;

            CTDebug(TAG, "Starting MediaMTX Server...");

            // Shutdown cleanup is queued on this same executor, so it cannot call stop until
            // this start returns. Avoid holding nativeControlLock across the JNI call: onDestroy
            // must always be able to mark the service as stopping without blocking the main thread.
            int rc = MediaMTXNative.start(bin.getAbsolutePath(), cfg.getAbsolutePath());
            if (rc != 0) {
                CTError(TAG, "MediaMTX failed to start");
                processPid = 0;
                serviceRunning = false;
                MediaMTXStatus.onServerExited("failed to start");
                stopSelf();
            }
        } catch (InterruptedIOException e) {
            if (!isServiceDestroying()) {
                CTError(TAG, "MediaMTX asset extraction interrupted", e);
            }
        } catch (Exception e) {
            CTError(TAG, "MediaMTX_Start() raised", e);
            processPid = 0;
            serviceRunning = false;
            MediaMTXStatus.onServerExited("start exception: " + e.getClass().getSimpleName());
            stopSelf();
        }
    }

    public static void requestRestart(Context context) {
        Context appContext = context.getApplicationContext();
        Intent restartIntent = new Intent(context, MediaMTXService.class);
        restartIntent.setAction(ACTION_RESTART_SERVICE);
        if (IsRunning()) {
            appContext.startService(restartIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, restartIntent);
        } else {
            appContext.startService(restartIntent);
        }
    }

    public static void requestStart(Context context) {
        Context appContext = context.getApplicationContext();
        Intent startIntent = new Intent(appContext, MediaMTXService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, startIntent);
        } else {
            appContext.startService(startIntent);
        }
    }

    public static void requestStop(Context context) {
        Intent stopIntent = new Intent(context, MediaMTXService.class);
        context.getApplicationContext().stopService(stopIntent);
    }

    @Nullable
    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopForegroundSafely();
        stopSelf();
        CaltopoClient.ShutdownAsync();
        super.onTaskRemoved(rootIntent);
    }

    private boolean startForegroundSafely(Notification notification) {
        try {
            startForeground(MEDIA_MTX_NOTIFICATION_ID, notification);
            foregroundStarted = true;
            return true;
        } catch (ForegroundServiceStartNotAllowedException e) {
            CTError(TAG, "Android blocked MediaMTX foreground start", e);
        } catch (IllegalStateException e) {
            CTError(TAG, "MediaMTX foreground start failed", e);
        } catch (RuntimeException e) {
            CTError(TAG, "Unexpected MediaMTX foreground start failure", e);
        }
        foregroundStarted = false;
        return false;
    }

    private void stopForegroundSafely() {
        if (!foregroundStarted) return;
        try {
            stopForeground(true);
        } catch (Exception e) {
            CTDebug(TAG, "stopForeground() ignored: " + e.getMessage());
        } finally {
            foregroundStarted = false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void checkFile(File f) {
        CTDebug(TAG, f
                + " exists=" + f.exists()
                + " r=" + f.canRead()
                + " w=" + f.canWrite()
                + " x=" + f.canExecute());
    }

    private File extractAsset(String assetName) throws IOException {

        File etcDir = new File(getFilesDir(), "etc");
        checkFile(etcDir);
        if (!etcDir.exists() && !etcDir.mkdirs()) {
            throw new IOException("Unable to create " + etcDir);
        }
        File outFile = new File(etcDir, assetName);
        File versionFile = new File(etcDir, assetName + ".app-update");
        String appUpdateToken = getAppUpdateToken();

        if (outFile.isFile()
                && outFile.length() > 0
                && outFile.canExecute()
                && versionFile.isFile()
                && appUpdateToken.equals(readSmallTextFile(versionFile))) {
            CTDebug(TAG, "extractAsset(): using cached " + outFile);
            return outFile;
        }

        long bytesWritten = 0;
        File tempFile = new File(etcDir, assetName + ".tmp");
        CTDebug(TAG, "extractAsset(): building " + outFile);
        try (InputStream in = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || isServiceDestroying()) {
                    throw new InterruptedIOException("MediaMTX asset extraction interrupted");
                }
                bytesWritten += read;
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            if (tempFile.exists() && !tempFile.delete()) {
                CTDebug(TAG, "Unable to remove incomplete MediaMTX asset " + tempFile);
            }
            throw e;
        }

        Files.move(
                tempFile.toPath(),
                outFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
        );
        outFile.setExecutable(true, false);
        outFile.setReadable(true, false);
        outFile.setWritable(true, true);
        try (OutputStream out = new FileOutputStream(versionFile)) {
            out.write(appUpdateToken.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            CTError(TAG, "Unable to record MediaMTX asset cache version; next start will re-extract", e);
        }
        CTDebug(TAG, String.format(Locale.US, "Wrote %.3f KB into %s", bytesWritten / 1024F, outFile));
        checkFile(outFile);
        return outFile;
    }

    private String getAppUpdateToken() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.lastUpdateTime + ":" + packageInfo.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            File sourceApk = new File(getApplicationInfo().sourceDir);
            return Long.toString(sourceApk.lastModified());
        }
    }

    private String readSmallTextFile(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > 128) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private File buildConfigAsset(String assetName) throws IOException {
        File etcDir = new File(getFilesDir(), "etc");
        checkFile(etcDir);
        etcDir.mkdirs();
        File outFile = new File(etcDir, assetName);
        String baseConfig = loadAssetText(assetName);
        File recordingRoot = MediaMTXRecordingSync.getRecordingStagingDir(getApplicationContext());
        if (!recordingRoot.exists() && !recordingRoot.mkdirs()) {
            CTError(TAG, "Unable to create MediaMTX recording staging dir " + recordingRoot);
        }
        FlightStorage.Snapshot storage = FlightStorage.maintain(getApplicationContext());
        storageCaptureBlocked = storage.getBlocked();
        if (storage.getBlocked() && CaltopoClient.GetCaptureVideoStreamsFlag()) {
            CaltopoClient.ShowToast(storage.getMessage());
        }
        String runtimeConfig = MediaMTXConfig.buildRuntimeConfig(
                baseConfig,
                CaltopoClient.GetCaptureVideoStreamsFlag() && !FlightStorage.INSTANCE.getCaptureBlocked(),
                recordingRoot
        );
        try (OutputStream out = new FileOutputStream(outFile)) {
            out.write(runtimeConfig.getBytes(StandardCharsets.UTF_8));
        }
        outFile.setReadable(true, false);
        outFile.setWritable(true, true);
        checkFile(outFile);
        return outFile;
    }

    private String loadAssetText(String assetName) throws IOException {
        try (InputStream in = getAssets().open(assetName);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
