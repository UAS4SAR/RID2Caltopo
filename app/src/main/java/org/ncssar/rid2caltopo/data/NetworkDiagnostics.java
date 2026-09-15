/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Records compact network snapshots only when the effective connection changes.
 * This class performs no polling and no reachability probes.
 */
public final class NetworkDiagnostics {
    private static final String TAG = "NetworkDiagnostics";
    private static final Object LOCK = new Object();
    private static long nextSnapshotNumber = 1L;
    @Nullable private static String lastTransitionKey;
    @NonNull private static volatile String currentSnapshotId = "none";

    private NetworkDiagnostics() {}

    @NonNull
    public static String getCurrentSnapshotId() {
        return currentSnapshotId;
    }

    public static void recordCurrentNetwork(
            @NonNull Context context,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull String reason,
            boolean force) {
        Snapshot snapshot;
        try {
            snapshot = capture(context.getApplicationContext(), connectivityManager);
        } catch (Exception ignored) {
            // Diagnostics must never destabilize the network callback that carries the video route.
            snapshot = new Snapshot("unavailable", "unavailable", Collections.emptyList(),
                    null, null, false, false, false);
        }
        final String snapshotId;
        synchronized (LOCK) {
            if (!shouldLogTransition(lastTransitionKey, snapshot.transitionKey(), force)) return;
            lastTransitionKey = snapshot.transitionKey();
            snapshotId = "net-" + nextSnapshotNumber++;
            currentSnapshotId = snapshotId;
        }
        CTDebug(TAG, String.format(Locale.US,
                "Network snapshotId=%s reason=%s ssid=%s bssidHash=%s ipv4=%s " +
                        "wifiRssiDbm=%s frequencyMhz=%s validated=%b captivePortal=%b metered=%b",
                snapshotId, reason, snapshot.ssid, snapshot.bssidHash,
                snapshot.ipv4Addresses.isEmpty() ? "none" : String.join(",", snapshot.ipv4Addresses),
                snapshot.wifiRssi == null ? "unavailable" : snapshot.wifiRssi,
                snapshot.frequencyMhz == null ? "unavailable" : snapshot.frequencyMhz,
                snapshot.validated, snapshot.captivePortal, snapshot.metered));
        CTDebug(TAG, "Network routes snapshotId=" + snapshotId + " " + describeRoutes(connectivityManager));
    }

    /** Includes non-default networks: controller traffic need not follow the Internet route. */
    @NonNull
    private static String describeRoutes(ConnectivityManager manager) {
        ArrayList<String> descriptions = new ArrayList<>();
        try {
            Network active = manager.getActiveNetwork();
            for (Network network : manager.getAllNetworks()) {
                LinkProperties links = manager.getLinkProperties(network);
                if (links == null) continue;
                descriptions.add("network=" + network + " default=" + network.equals(active) +
                        " interface=" + links.getInterfaceName() + " addresses=" + links.getLinkAddresses() +
                        " routes=" + links.getRoutes());
            }
        } catch (Exception ignored) {
            descriptions.add("routes=unavailable");
        }
        return String.join("; ", descriptions);
    }

    static boolean shouldLogTransition(
            @Nullable String previousKey,
            @NonNull String currentKey,
            boolean force) {
        return force || !currentKey.equals(previousKey);
    }

    @NonNull
    static String transitionKey(
            @NonNull String ssid,
            @NonNull String bssidHash,
            @NonNull List<String> ipv4Addresses,
            boolean validated,
            boolean captivePortal,
            boolean metered) {
        // RSSI and channel are deliberately excluded: normal RF fluctuations must not create logs.
        return ssid + '|' + bssidHash + '|' + String.join(",", ipv4Addresses) + '|' +
                validated + '|' + captivePortal + '|' + metered;
    }

    @NonNull
    private static Snapshot capture(
            @NonNull Context context,
            @NonNull ConnectivityManager connectivityManager) {
        Network activeNetwork = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = activeNetwork == null
                ? null : connectivityManager.getNetworkCapabilities(activeNetwork);
        LinkProperties linkProperties = activeNetwork == null
                ? null : connectivityManager.getLinkProperties(activeNetwork);

        ArrayList<String> ipv4Addresses = new ArrayList<>();
        if (linkProperties != null) {
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                if (linkAddress.getAddress() instanceof Inet4Address &&
                        !linkAddress.getAddress().isLoopbackAddress()) {
                    String address = linkAddress.getAddress().getHostAddress();
                    if (address != null && !address.isEmpty()) ipv4Addresses.add(address);
                }
            }
        }
        Collections.sort(ipv4Addresses);

        WifiInfo wifiInfo = null;
        if (capabilities != null && capabilities.getTransportInfo() instanceof WifiInfo) {
            wifiInfo = (WifiInfo) capabilities.getTransportInfo();
        }
        if (wifiInfo == null) {
            WifiManager wifiManager = context.getSystemService(WifiManager.class);
            if (wifiManager != null) {
                try {
                    wifiInfo = wifiManager.getConnectionInfo();
                } catch (SecurityException ignored) {
                    // The snapshot explicitly reports unavailable values below.
                }
            }
        }

        String ssid = normalizeWifiIdentifier(wifiInfo == null ? null : wifiInfo.getSSID());
        String bssid = normalizeWifiIdentifier(wifiInfo == null ? null : wifiInfo.getBSSID());
        String bssidHash = "unavailable".equals(bssid) ? "unavailable" : shortHash(bssid);
        int rawRssi = wifiInfo == null ? 0 : wifiInfo.getRssi();
        Integer rssi = rawRssi <= -127 || rawRssi >= 0 ? null : rawRssi;
        Integer frequency = wifiInfo == null || wifiInfo.getFrequency() <= 0
                ? null : wifiInfo.getFrequency();
        boolean validated = capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean captivePortal = capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        boolean metered = connectivityManager.isActiveNetworkMetered();
        return new Snapshot(ssid, bssidHash, ipv4Addresses, rssi, frequency,
                validated, captivePortal, metered);
    }

    @NonNull
    private static String normalizeWifiIdentifier(@Nullable String value) {
        if (value == null || value.isEmpty() || "<unknown ssid>".equalsIgnoreCase(value) ||
                "02:00:00:00:00:00".equals(value)) return "unavailable";
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replaceAll("\\s+", "_");
    }

    @NonNull
    private static String shortHash(@NonNull String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 6; i++) result.append(String.format(Locale.US, "%02x", digest[i]));
            return result.toString();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private static final class Snapshot {
        @NonNull final String ssid;
        @NonNull final String bssidHash;
        @NonNull final List<String> ipv4Addresses;
        @Nullable final Integer wifiRssi;
        @Nullable final Integer frequencyMhz;
        final boolean validated;
        final boolean captivePortal;
        final boolean metered;

        Snapshot(
                @NonNull String ssid,
                @NonNull String bssidHash,
                @NonNull List<String> ipv4Addresses,
                @Nullable Integer wifiRssi,
                @Nullable Integer frequencyMhz,
                boolean validated,
                boolean captivePortal,
                boolean metered) {
            this.ssid = ssid;
            this.bssidHash = bssidHash;
            this.ipv4Addresses = Collections.unmodifiableList(new ArrayList<>(ipv4Addresses));
            this.wifiRssi = wifiRssi;
            this.frequencyMhz = frequencyMhz;
            this.validated = validated;
            this.captivePortal = captivePortal;
            this.metered = metered;
        }

        @NonNull String transitionKey() {
            return NetworkDiagnostics.transitionKey(
                    ssid, bssidHash, ipv4Addresses, validated, captivePortal, metered);
        }
    }
}
