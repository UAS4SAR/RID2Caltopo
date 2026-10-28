
/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.app;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.data.CaltopoMap;
import org.ncssar.rid2caltopo.data.SimpleTimer;
import org.ncssar.rid2caltopo.data.WifiRidScanPrefs;
import org.opendroneid.android.bluetooth.BluetoothScanner;
import org.opendroneid.android.bluetooth.OpenDroneIdDataManager;
import org.opendroneid.android.bluetooth.WiFiScanner;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;

/* This foreground Service required to receive Bluetooth and Wifi
   updates when the app is backgrounded/paused.
 */

public class ScanningService extends Service {
    private static final String TAG = "ScanningService";
    private static final String ACTION_STOP_SERVICE = "STOP_SERVICE";
    private static final String ACTION_REFRESH_WIFI_RID_SCANNING = "REFRESH_WIFI_RID_SCANNING";
    private static final String ACTION_REFRESH_BLUETOOTH_RID_TEST = "REFRESH_BLUETOOTH_RID_TEST";
    private static final String ACTION_DISPLAY_STATE = "DISPLAY_STATE";
    private static final String EXTRA_DISPLAY_ACTIVE = "display_active";
    private static final String EXTRA_EXTERNAL_DISPLAY_CONNECTED = "external_display_connected";
    private static final long INCIDENT_MAP_BACKGROUND_DISCONNECT_DELAY_MS = 5L * 60L * 1000L;
    private static final long INCIDENT_MAP_BACKGROUND_RECHECK_MS = 60L * 1000L;
    private static final long TRACKER_SILENCE_RECHECK_MS = 15L * 1000L;
    private static final String CHANNEL_ID = "OpenDroneIdScanner";
    private static final String CHANNEL_NAME = "OpenDroneId Scanner Service";
    private static final int NOTIFICATION_ID = 1;
    public static final SimpleTimer ScannerUptime = new SimpleTimer();;
    private BluetoothScanner btScanner;
    private WiFiScanner wiFiScanner;
    private boolean scanning = false;
    private boolean foregroundStarted = false;
    private boolean foregroundStartBlocked = false;
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback backgroundLocationCallback;
    private boolean externalDisplayConnected = false;
    private final Runnable incidentMapBackgroundDisconnect = new Runnable() {
        @Override public void run() {
            if (externalDisplayConnected) return;
            boolean disconnected = CaltopoMap.DisconnectIncidentMapIfInactive("display inactive");
            CTDebug(TAG, disconnected
                    ? "Display remained inactive; incident map disconnected"
                    : "Display remained inactive; incident map retained because operational work is active");
            if (!disconnected && CaltopoMap.GetMapNode() != null) {
                lifecycleHandler.postDelayed(this, INCIDENT_MAP_BACKGROUND_RECHECK_MS);
            }
        }
    };
    private final Runnable trackerSilenceDisconnect = new Runnable() {
        @Override public void run() {
            boolean disconnected = CaltopoMap.DisconnectIncidentMapAfterTrackerSilenceIfInactive();
            if (disconnected) {
                CTWarn(TAG, "Tracker silent for 60 seconds; inactive incident map disconnected");
                return;
            }
            if (CaltopoMap.GetMapNode() != null) {
                lifecycleHandler.postDelayed(this, TRACKER_SILENCE_RECHECK_MS);
            }
        }
    };
    private static volatile boolean serviceRunning = false;
    private static Context AppContext = R2CApplication.getAppCtxt();
    private static OpenDroneIdDataManager DataManager = new OpenDroneIdDataManager(null);
    public static long GetStartTimeInMsec() {return ScannerUptime.getStartTimeInMsec();}
    public static boolean IsRunning() { return serviceRunning; }
    public static int GetRidIngestQueueDepth() {
        return (DataManager != null) ? DataManager.getRidIngestQueueDepth() : -1;
    }
    public static long GetDroppedRidIngestPacketCount() {
        return (DataManager != null) ? DataManager.getDroppedRidIngestPacketCount() : -1L;
    }
    public void startScanning() {
        if (scanning) {
            CTError(TAG, "startScanning(): ignoring start request while running.");
            return;
        }
        scanning = true;
        if (null == AppContext) AppContext = R2CApplication.getAppCtxt();
        CTDebug(TAG, String.format(Locale.US, "startScanning(): ScanningService 0x%x", this.hashCode()));
        btScanner = new BluetoothScanner(AppContext, DataManager);
        btScanner.startScan();
        applyWifiRidScanningPreference();
    }

    private void applyWifiRidScanningPreference() {
        boolean enabled = WifiRidScanPrefs.isEnabled(AppContext);
        if (enabled && wiFiScanner == null) {
            CTDebug(TAG, "Wi-Fi RID scanning enabled; starting Beacon and NAN discovery.");
            wiFiScanner = new WiFiScanner(AppContext, DataManager);
            wiFiScanner.startScan();
        } else if (!enabled && wiFiScanner != null) {
            CTDebug(TAG, "Wi-Fi RID scanning disabled; stopping Beacon and NAN discovery.");
            wiFiScanner.stopScan();
            wiFiScanner = null;
        } else {
            CTDebug(TAG, "Wi-Fi RID scanning remains " + (enabled ? "enabled." : "disabled."));
        }
    }

    private void applyBluetoothRidTestPreference() {
        if (btScanner != null) btScanner.stopScan();
        btScanner = new BluetoothScanner(AppContext, DataManager);
        btScanner.startScan();
    }

    public void stopScanning() {
        if (!scanning) {
            CTError(TAG, "stopScanning(): Ignoring request to stop when idle");
            return;
        }
        CTDebug(TAG, String.format(Locale.US, "stopScanning(): ScanningService 0x%x", this.hashCode()));
        if (wiFiScanner != null) {
            wiFiScanner.stopScan();
            wiFiScanner = null;
        }
        if (btScanner != null) {
            btScanner.stopScan();
            btScanner = null;
        }
        scanning = false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ScannerUptime.restartTimer();
        CTDebug(TAG, String.format(Locale.US,
                "onCreate(): Starting ScanningService:0x%x in pid:%d",
                this.hashCode(), Process.myPid()));

        if (null == AppContext) AppContext = R2CApplication.getAppCtxt();
        if (null == AppContext || null == DataManager) {
            CTError(TAG, "onCreate() missing required app context - stopping service.");
            stopSelf();
            return;
        }

        CTDebug(TAG, String.format(Locale.US, "onCreate(): Context:0x%x DataManager:0x%x",
                AppContext.hashCode(), DataManager.hashCode()));

        NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID,
                CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notManager = getSystemService(NotificationManager.class);
        if (notManager != null) {
            notManager.createNotificationChannel(serviceChannel);
        }

        if (startForegroundSafely(buildForegroundNotification())) {
            serviceRunning = true;
        } else {
            foregroundStartBlocked = true;
            serviceRunning = false;
            stopSelf();
        }
    }
    @NonNull
    public static String UpTime() {
        return ScannerUptime.durationAsString();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        settingsServiceStopped = true;
        pendingSettingsCommands.clear();
        CTDebug(TAG, String.format(Locale.US,
                "onDestroy(): ScanningService 0x%x", this.hashCode()));
        stopForegroundSafely();
        lifecycleHandler.removeCallbacks(incidentMapBackgroundDisconnect);
        lifecycleHandler.removeCallbacks(trackerSilenceDisconnect);
        stopBackgroundLocationUpdates();
        if (scanning) {
            stopScanning();
        }
        foregroundStarted = false;
        foregroundStartBlocked = false;
        serviceRunning = false;
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
    }

    @Override
    public
    void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    private boolean settingsWaitRegistered;
    private boolean settingsServiceStopped;
    private final java.util.ArrayList<Runnable> pendingSettingsCommands = new java.util.ArrayList<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (foregroundStartBlocked) {
            CTError(TAG, "onStartCommand(): foreground start blocked by Android.");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            settingsServiceStopped = true;
            pendingSettingsCommands.clear();
            CTDebug(TAG, "ScanningService shutting down.");
            stopForegroundSafely();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!SettingsStartup.isReady()) {
            final Intent savedIntent = intent == null ? null : new Intent(intent);
            pendingSettingsCommands.add(() -> onStartCommand(savedIntent, flags, startId));
            if (!settingsWaitRegistered) {
                settingsWaitRegistered = true;
                SettingsStartup.whenReady(() -> {
                    if (settingsServiceStopped) return;
                    java.util.ArrayList<Runnable> commands = new java.util.ArrayList<>(pendingSettingsCommands);
                    pendingSettingsCommands.clear();
                    for (Runnable command : commands) command.run();
                }, error -> {
                    pendingSettingsCommands.clear();
                    CTError(TAG, "Saved settings unavailable; stopping scanner.");
                    stopSelf();
                });
            }
            return START_STICKY;
        }

        if (intent != null && ACTION_REFRESH_WIFI_RID_SCANNING.equals(intent.getAction())) {
            if (scanning) applyWifiRidScanningPreference();
            return START_STICKY;
        }

        if (intent != null && ACTION_REFRESH_BLUETOOTH_RID_TEST.equals(intent.getAction())) {
            if (scanning) applyBluetoothRidTestPreference();
            return START_STICKY;
        }

        if (intent != null && ACTION_DISPLAY_STATE.equals(intent.getAction())) {
            boolean displayActive = intent.getBooleanExtra(EXTRA_DISPLAY_ACTIVE, true);
            externalDisplayConnected = intent.getBooleanExtra(
                    EXTRA_EXTERNAL_DISPLAY_CONNECTED, false);
            lifecycleHandler.removeCallbacks(incidentMapBackgroundDisconnect);
            lifecycleHandler.removeCallbacks(trackerSilenceDisconnect);
            if (displayActive) {
                CaltopoMap.EndIncidentDisplayInactive();
                stopBackgroundLocationUpdates();
            } else {
                CaltopoMap.BeginIncidentDisplayInactive();
                startBackgroundLocationUpdates();
                lifecycleHandler.postDelayed(
                        trackerSilenceDisconnect,
                        TRACKER_SILENCE_RECHECK_MS);
                if (!externalDisplayConnected) {
                    lifecycleHandler.postDelayed(
                            incidentMapBackgroundDisconnect,
                            INCIDENT_MAP_BACKGROUND_DISCONNECT_DELAY_MS);
                }
            }
            return START_STICKY;
        }

        if (null == AppContext) {
            AppContext = R2CApplication.getAppCtxt();
            if (null == AppContext) {
                return START_REDELIVER_INTENT;
            }
        }
        CTDebug(TAG, String.format(Locale.US, "onStartCommand(): AppContext:0x%x", AppContext.hashCode()));
        if (!foregroundStarted && !startForegroundSafely(buildForegroundNotification())) {
            foregroundStartBlocked = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        startScanning();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        CTDebug(TAG, "onTaskRemoved()");
        stopForegroundSafely();
        stopSelf();
        CaltopoClient.ShutdownAsync();
        super.onTaskRemoved(rootIntent);
    }

    public static void requestStart(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        Intent startIntent = new Intent(appContext, ScanningService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, startIntent);
        } else {
            appContext.startService(startIntent);
        }
    }

    public static void requestStop(@NonNull Context context) {
        if (!IsRunning()) {
            CTDebug(TAG, "requestStop(): ScanningService already stopped; ignoring stop request.");
            return;
        }
        Intent stopIntent = new Intent(context, ScanningService.class);
        context.getApplicationContext().stopService(stopIntent);
    }

    public static void setDisplayActive(
            @NonNull Context context,
            boolean active,
            boolean externalDisplayConnected
    ) {
        if (!IsRunning()) return;
        Intent intent = new Intent(context.getApplicationContext(), ScanningService.class);
        intent.setAction(ACTION_DISPLAY_STATE);
        intent.putExtra(EXTRA_DISPLAY_ACTIVE, active);
        intent.putExtra(EXTRA_EXTERNAL_DISPLAY_CONNECTED, externalDisplayConnected);
        context.getApplicationContext().startService(intent);
    }

    public static void requestWifiRidScanningRefresh(@NonNull Context context) {
        if (!IsRunning()) return;
        Context appContext = context.getApplicationContext();
        Intent refreshIntent = new Intent(appContext, ScanningService.class);
        refreshIntent.setAction(ACTION_REFRESH_WIFI_RID_SCANNING);
        appContext.startService(refreshIntent);
    }

    public static void requestBluetoothRidTestRefresh(@NonNull Context context) {
        if (!IsRunning()) return;
        Context appContext = context.getApplicationContext();
        Intent refreshIntent = new Intent(appContext, ScanningService.class);
        refreshIntent.setAction(ACTION_REFRESH_BLUETOOTH_RID_TEST);
        appContext.startService(refreshIntent);
    }
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(fd, writer, args);
    }

    private Notification buildForegroundNotification() {
        Intent launchIntent = new Intent(AppContext, R2CActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(AppContext, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(AppContext, CHANNEL_ID)
                .setContentTitle("OpenDroneID Scanning Service")
                .setContentText("Scanning for remoteID broadcasts on Bluetooth and Wireless interfaces")
                .setSmallIcon(R.drawable.ic_notification_drone)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .build();
    }

    private boolean startForegroundSafely(Notification notification) {
        try {
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    serviceTypes);
            foregroundStarted = true;
            return true;
        } catch (ForegroundServiceStartNotAllowedException e) {
            CTError(TAG, "Android blocked ScanningService foreground start", e);
        } catch (IllegalStateException e) {
            CTError(TAG, "ScanningService foreground start failed", e);
        } catch (RuntimeException e) {
            CTError(TAG, "Unexpected ScanningService foreground start failure", e);
        }
        foregroundStarted = false;
        return false;
    }

    private void startBackgroundLocationUpdates() {
        if (backgroundLocationCallback != null) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            CTError(TAG, "Background movement monitoring unavailable: precise location not granted.");
            return;
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        backgroundLocationCallback = new LocationCallback() {
            @Override public void onLocationResult(@NonNull LocationResult result) {
                for (android.location.Location location : result.getLocations()) {
                    CaltopoMap.UpdateMyLocation(location);
                }
            }
        };
        LocationRequest request = new LocationRequest.Builder(10_000L)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(5_000L)
                .setMinUpdateDistanceMeters(5.0f)
                .build();
        fusedLocationClient.requestLocationUpdates(
                request, backgroundLocationCallback, Looper.getMainLooper())
                .addOnFailureListener(error -> CTError(
                        TAG, "Background movement location request failed", error));
        CTDebug(TAG, "Background movement location monitoring started.");
    }

    private void stopBackgroundLocationUpdates() {
        if (fusedLocationClient != null && backgroundLocationCallback != null) {
            fusedLocationClient.removeLocationUpdates(backgroundLocationCallback);
        }
        backgroundLocationCallback = null;
        fusedLocationClient = null;
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
}
