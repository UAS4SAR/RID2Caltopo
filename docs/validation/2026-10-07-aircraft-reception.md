# Aircraft and Reception parity — October 7, 2026

Both platforms rename the bridge-meter panel to Aircraft and Reception, including accessibility labels. Apple previously gated the header on a positive aircraft count; the presentation policy now keeps the header visible when empty. Its regression test verifies zero and one aircraft and the common BT4, BT5, WiFi, NaN column order.

Apple's table was also missing WiFi and NaN columns. They now render the existing source counts and RSSI fields through the same transport-cell renderer used for Bluetooth, with the grouped header width updated. This changes presentation, not receiver capability or source collection. Android's existing table remains unchanged.

Android debug build and the focused Apple regression test passed. Signed iPad build passed. Log: `/tmp/r2c-reception-apple.log`; Android log: `/tmp/r2c-reception-android.log`; test log: `/tmp/r2c-reception-swift.log`. `git diff --check` passed. Version remains 2.5.0 (339).

User explicitly authorized installation. A5 Pro in-place install and launch succeeded; device metadata confirms 2.5.0 (339). iPad in-place install succeeded with signed artifact 2.5.0 (339); automatic launch was blocked because iPadOS was locked. Unlock and open the app to finish launch verification. No device reset, uninstall, or field-data changes were performed. Empty/live pane behavior still needs physical acceptance.
