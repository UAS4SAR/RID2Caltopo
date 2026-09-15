/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.bluetooth;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import org.ncssar.rid2caltopo.app.R2CActivity;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.ncssar.rid2caltopo.data.CtDroneSpec;
import org.ncssar.rid2caltopo.data.BluetoothRidTestPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class BluetoothScanner {
    private static final String TAG = "BluetoothScanner";

    private final OpenDroneIdDataManager dataManager;
    private final BluetoothAdapter bluetoothAdapter = getBluetoothAdapter();
    private BluetoothLeScanner bluetoothLeScanner;
    private final Context context;
    private final BluetoothRidTestPrefs.ScanVariant testVariant;
    private final boolean periodicRestartEnabled;
    private final Handler diagnosticHandler = new Handler(Looper.getMainLooper());
    private boolean scanning;

    private static final long DIAGNOSTIC_REPORT_INTERVAL_MS = 5_000L;
    private static final long DIAGNOSTIC_RESTART_INTERVAL_MS = 120_000L;
    private static final long DIAGNOSTIC_RESTART_PAUSE_MS = 250L;
    private final long diagnosticStartedAtMs = SystemClock.elapsedRealtime();
    private final AtomicLong rawCallbacks = new AtomicLong();
    private final AtomicLong ridMatches = new AtomicLong();
    private final AtomicLong nonRidCallbacks = new AtomicLong();
    private final AtomicLong nullScanRecords = new AtomicLong();
    private final AtomicLong scanFailures = new AtomicLong();
    private final AtomicLong lastCallbackAtMs = new AtomicLong();
    private final AtomicLong maxCallbackGapMs = new AtomicLong();
    private final AtomicLong legacyCallbacks = new AtomicLong();
    private final AtomicLong extendedCallbacks = new AtomicLong();
    private final AtomicLong phy1mCallbacks = new AtomicLong();
    private final AtomicLong phyCodedCallbacks = new AtomicLong();
    private final AtomicLong phyOtherCallbacks = new AtomicLong();
    private final AtomicLongArray messageTypes = new AtomicLongArray(16);
    private final Set<Integer> messageCounters = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> transmitters = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile String lastPacket = "none";

    public BluetoothScanner(@NonNull Context context, @NonNull OpenDroneIdDataManager dataManager) {
        this.context = context;
        this.dataManager = dataManager;
        this.testVariant = BluetoothRidTestPrefs.getVariant(context);
        this.periodicRestartEnabled = BluetoothRidTestPrefs.isPeriodicRestartEnabled(context);
    }

    public static BluetoothAdapter getBluetoothAdapter() {

        Context appContext = R2CApplication.getAppCtxt();
        BluetoothAdapter adapter = null;
        if (null != appContext) {
            BluetoothManager btManager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = btManager.getAdapter();
            if (null == adapter) {
                CTWarn(TAG, "getBluetoothAdapter(): Can't get the default bluetooth adapter.");
            } else if (!adapter.isEnabled()) {
                // not sure what is going on.  Adapter frequently says it's not available, though it seems to be working.
                CTWarn(TAG, "getBluetoothAdapter(): Default bluetooth adapter not enabled.");
            } else {
                CTWarn(TAG, "getBluetoothAdapter(): Default bluetooth adapter is enabled.");
            }
        }
        return adapter;
    }

    private final ScanCallback scanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            recordRawCallback(result);
            ScanRecord scanRecord = result.getScanRecord();
            if (scanRecord == null) {
                nullScanRecords.incrementAndGet();
                return;
            }
            // Parse the UUID-relative service data instead of assuming that the FFFA field
            // starts at byte zero of the complete advertisement. Other advertisement fields
            // may precede it, which made otherwise valid relay pings decode intermittently.
            byte[] serviceData = scanRecord.getServiceData(SERVICE_pUUID);
            if (serviceData == null || serviceData.length == 0 || serviceData[0] != OPEN_DRONE_ID_AD_CODE[0]) {
                nonRidCallbacks.incrementAndGet();
                return;
            }

            recordRidMatch(result, serviceData);

            CtDroneSpec.TransportTypeEnum transportType = CtDroneSpec.TransportTypeEnum.BT4;
            if (bluetoothAdapter.isLeCodedPhySupported()) {
                if (result.getPrimaryPhy() == BluetoothDevice.PHY_LE_CODED)
                    transportType = CtDroneSpec.TransportTypeEnum.BT5;
            }

            if (null != dataManager) dataManager.receiveDataBluetooth(serviceData, result, transportType);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            CTInfo(TAG, "onBatchScanResults: " + results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanFailures.incrementAndGet();
            CTError(TAG, "onScanFailed: errorCode is " + errorCode);
        }
    };

    /* OpenDroneID Bluetooth beacons identify themselves by setting the GAP AD Type to
     * "Service Data - 16-bit UUID" and the value to 0xFFFA for ASTM International, ASTM Remote ID.
     * https://www.bluetooth.com/specifications/assigned-numbers/ -> "Generic Access Profile"
     * https://www.bluetooth.com/specifications/assigned-numbers/ -> "16-bit UUIDs"
     * Vol 3, Part B, Section 2.5.1 of the Bluetooth 5.1 Core Specification
     * The AD Application Code is set to 0x0D = Open Drone ID.
     */
    private static final UUID SERVICE_UUID = UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid SERVICE_pUUID = new ParcelUuid(SERVICE_UUID);
    private static final byte[] OPEN_DRONE_ID_AD_CODE = new byte[]{(byte) 0x0D};

    private final Runnable diagnosticReporter = new Runnable() {
        @Override
        public void run() {
            if (!scanning || !testVariant.getDiagnosticsEnabled()) return;
            CTInfo("BluetoothRIDTest", buildDiagnosticSummary());
            diagnosticHandler.postDelayed(this, DIAGNOSTIC_REPORT_INTERVAL_MS);
        }
    };

    private final Runnable diagnosticRestarter = new Runnable() {
        @Override
        public void run() {
            if (!scanning || !periodicRestartEnabled) return;
            CTInfo("BluetoothRIDTest", "Periodic Bluetooth scan restart requested after 120 seconds.");
            stopPlatformScan();
            diagnosticHandler.postDelayed(() -> {
                if (!scanning) return;
                startPlatformScan();
                diagnosticHandler.postDelayed(diagnosticRestarter, DIAGNOSTIC_RESTART_INTERVAL_MS);
            }, DIAGNOSTIC_RESTART_PAUSE_MS);
        }
    };

    public void startScan() {
        if (null == bluetoothAdapter) {
            CTError(TAG, "startScan(): bluetooth adapter missing.");
            return;
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        scanning = true;
        startPlatformScan();
        if (testVariant.getDiagnosticsEnabled()) {
            CTInfo("BluetoothRIDTest", String.format(Locale.US,
                    "Enabled variant=%s softwareFilter=%s legacy1M=%s periodicRestart=%s",
                    testVariant.name(), testVariant.getUsesSoftwareFilter(),
                    testVariant.getUsesLegacy1M(), periodicRestartEnabled));
            diagnosticHandler.postDelayed(diagnosticReporter, DIAGNOSTIC_REPORT_INTERVAL_MS);
        }
        if (periodicRestartEnabled) {
            diagnosticHandler.postDelayed(diagnosticRestarter, DIAGNOSTIC_RESTART_INTERVAL_MS);
        }
    }

    private void startPlatformScan() {
        if (bluetoothLeScanner == null || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

        List<ScanFilter> scanFilters = null;
        if (!testVariant.getUsesSoftwareFilter()) {
            ScanFilter.Builder builder = new ScanFilter.Builder();
            // Match Apple's service-scoped discovery. The parser validates the
            // Open Drone ID application code after receipt.
            builder.setServiceData(SERVICE_pUUID,
                    testVariant == BluetoothRidTestPrefs.ScanVariant.PRODUCTION ? null : OPEN_DRONE_ID_AD_CODE);
            scanFilters = new ArrayList<>();
            scanFilters.add(builder.build());
        }

        ScanSettings.Builder settingsBuilder = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0L);
        if (testVariant.getUsesLegacy1M()) {
            settingsBuilder.setLegacy(true).setPhy(BluetoothDevice.PHY_LE_1M);
        } else if (bluetoothAdapter.isLeCodedPhySupported() &&
                bluetoothAdapter.isLeExtendedAdvertisingSupported()) {
            CTDebug(TAG, "startScan: Enable scanning also for devices advertising on an LE Coded PHY S2 or S8");
            settingsBuilder.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
        }
        ScanSettings scanSettings = settingsBuilder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                CTError(TAG, "startScan: Did not get BLUETOOTH_SCAN permission");
                return;
            }
        }
        CTDebug(TAG, "startScan: Calling bluetoothLeScanner.startScan variant=" + testVariant.name()
                + " legacy1M=" + testVariant.getUsesLegacy1M()
                + " periodicRestart=" + periodicRestartEnabled);
        bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback);
    }

    public void stopScan() {
        scanning = false;
        diagnosticHandler.removeCallbacksAndMessages(null);
        stopPlatformScan();
        if (testVariant.getDiagnosticsEnabled()) {
            CTInfo("BluetoothRIDTest", "Stopped " + buildDiagnosticSummary());
        }
    }

    private void stopPlatformScan() {
        if (bluetoothLeScanner != null && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    CTError(TAG, "stopScan: Did not get BLUETOOTH_SCAN permission");
                    return;
                }
            }
            CTDebug(TAG, "Calling bluetoothLeScanner.stopScan().");
            bluetoothLeScanner.stopScan(scanCallback);
        }
    }

    private void recordRawCallback(ScanResult result) {
        rawCallbacks.incrementAndGet();
        long now = SystemClock.elapsedRealtime();
        long previous = lastCallbackAtMs.getAndSet(now);
        if (previous > 0L) maxCallbackGapMs.accumulateAndGet(now - previous, Math::max);
        if (result.isLegacy()) legacyCallbacks.incrementAndGet();
        else extendedCallbacks.incrementAndGet();
        if (result.getPrimaryPhy() == BluetoothDevice.PHY_LE_1M) phy1mCallbacks.incrementAndGet();
        else if (result.getPrimaryPhy() == BluetoothDevice.PHY_LE_CODED) phyCodedCallbacks.incrementAndGet();
        else phyOtherCallbacks.incrementAndGet();
    }

    private void recordRidMatch(ScanResult result, byte[] serviceData) {
        ridMatches.incrementAndGet();
        int counter = serviceData.length > 1 ? Byte.toUnsignedInt(serviceData[1]) : -1;
        int type = serviceData.length > 2 ? (Byte.toUnsignedInt(serviceData[2]) >> 4) & 0x0f : -1;
        if (counter >= 0) messageCounters.add(counter);
        if (type >= 0) messageTypes.incrementAndGet(type);
        String address = "unavailable";
        try {
            address = result.getDevice().getAddress();
            transmitters.add(address);
        } catch (SecurityException ignored) {
            // Diagnostics remain useful without device identity permission.
        }
        lastPacket = String.format(Locale.US, "tx=%s counter=%d type=%s rssi=%d bytes=%d phy=%d legacy=%s",
                address, counter, typeName(type), result.getRssi(), serviceData.length,
                result.getPrimaryPhy(), result.isLegacy());
    }

    private String buildDiagnosticSummary() {
        return String.format(Locale.US,
                "variant=%s elapsedMs=%d raw=%d rid=%d nonRid=%d nullRecord=%d failures=%d " +
                        "maxCallbackGapMs=%d transmitters=%d uniqueCounters=%d " +
                        "types[basic=%d location=%d auth=%d self=%d system=%d operator=%d pack=%d unknown=%d] " +
                        "phy[1m=%d coded=%d other=%d legacy=%d extended=%d] " +
                        "ingest[queue=%d dropped=%d failed=%d slow=%d] last[%s]",
                testVariant.name(), SystemClock.elapsedRealtime() - diagnosticStartedAtMs,
                rawCallbacks.get(), ridMatches.get(), nonRidCallbacks.get(), nullScanRecords.get(), scanFailures.get(),
                maxCallbackGapMs.get(), transmitters.size(), messageCounters.size(),
                messageTypes.get(0), messageTypes.get(1), messageTypes.get(2), messageTypes.get(3),
                messageTypes.get(4), messageTypes.get(5), messageTypes.get(15), unknownTypeCount(),
                phy1mCallbacks.get(), phyCodedCallbacks.get(), phyOtherCallbacks.get(),
                legacyCallbacks.get(), extendedCallbacks.get(),
                dataManager.getRidIngestQueueDepth(), dataManager.getDroppedRidIngestPacketCount(),
                dataManager.getFailedRidIngestPacketCount(), dataManager.getSlowRidIngestPacketCount(), lastPacket);
    }

    private long unknownTypeCount() {
        long count = 0L;
        for (int index = 0; index < 16; index++) {
            if (index != 0 && index != 1 && index != 2 && index != 3 &&
                    index != 4 && index != 5 && index != 15) count += messageTypes.get(index);
        }
        return count;
    }

    private static String typeName(int type) {
        OpenDroneIdParser.Type parsed = OpenDroneIdParser.Type.fromId(type);
        return parsed != null ? parsed.name() : "UNKNOWN(" + type + ")";
    }
}
