# iPad retained Live View network header — 2026-09-29

User reports both SSID and RTMP URL remained unchanged during a network interruption flight on build 304. Read-only device query confirmed iPad 694108CB-8CBE-593D-ABE1-D9EDD947B901, 2.3.7 (304). No install, restart, or device setting changes during investigation.

Device evidence: Documents/RID2Caltopo/FlightStorage/2026-09-29/Log_29Sep2026-095320-PDT-0700.txt, copied to /tmp/r2c-ipad304-network-flight.txt. At 09:55:20.144 network snapshot net-7 acquired the new SSID; at 09:55:20.687 net-8 acquired its new IPv4 address; at 09:55:20.697 the root logged the changed controller RTMP URL. The return to the original network at 10:08:10 was also detected. Thus the logged monitor and root address state changed correctly; these logs do not prove the visible header updated.

Source finding: RIDTrackMapView retained a let networkSSID value supplied by its navigation parent. Replaced that captured label with AppleLiveViewNetworkStatus, which observes AppleNetworkDiagnosticCenter directly inside the retained destination and groups the SSID and controller URL component. Removed unused captured ingestAddress/networkSSID arguments from the map/grid/tile chain. The URL component already had a direct observation; this change makes the enclosing header observe updates too, but the exact mechanism of the reported stale URL is not established. Added header snapshot receipt logging for physical correlation.

Android audit: Live View reads SSID and endpoints from the same rememberControllerNetwork observed state; no analogous captured navigation argument was found. No Android changes required for this retained SwiftUI destination issue.

Apple package tests: 439 Swift Testing plus 34 XCTest tests passed, including a retained-header observation wiring regression. This source regression and device builds do not establish physical rendering. Installation and physical network-switch retest remain pending.

Signed iOS Debug build and deep/strict signature verification passed.

Installation follow-up: 2026-09-29 10:34 PDT, signed header-fix artifact installed in place on Ken’s iPad (694108CB-8CBE-593D-ABE1-D9EDD947B901). This rebuild retains version 2.3.7 (304); Android was not reinstalled. Installation preserved the app data container.
