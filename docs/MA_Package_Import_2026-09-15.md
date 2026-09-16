# MA package generation and import

## Screenshot findings

- 16:59:02: Import Config rejected an `r2cmapkg1://` transfer QR. Its validator recognized org/FAA/MA profile/enrollment tokens, but omitted MA package transfer tokens. The camera deep link used a separate handler that supported them.
- 17:07:52: The phone attempted the sender at `192.168.68.67:43363` from `100.76.61.125`, with 5G displayed. This proves connection timeout before download, not a corrupt package.
- 17:08:52: The tablet also could not connect to that endpoint. Its visible Wi-Fi address was `192.168.68.58`. The screenshot does not establish whether the sender was listening, sharing had ended, the address had changed, or the network isolated clients. The operator subsequently confirmed that the sender still displayed the QR panel.

## Attached sender evidence

The attached SM-X930 (S11 Ultra) runs 2.2.8 (240). Retained logs show the existing app completed sending 55,549,885 bytes to the SM-X350 at 192.168.68.58 between 17:04:14 and 17:04:16. This proves prior LAN transfer, not successful receiver import. Sender Wi-Fi logs retain 192.168.68.67 around 17:04–17:10 and the app process remains running. No later MA connection attempt or explicit sender failure appears in the retained MA log. The token prefix in the first screenshot decodes to port 39893 and session ID prefix aed0a9e0-a5c, matching the successful-send session prefix. The port was verified against the visible encoded characters (PdMHm2); port 43363 would encode differently (JsoyS2). Both later errors target port 43363 instead. These are different sharing endpoints, so a stale or different QR is plausible. Full screenshot-token OCR was not reliable enough to validate every field or its expiry. The third screenshot remains unresolved; closing the QR panel should not be assumed. No modified app was installed or physically transfer-tested.

## Changes

Both export panels default **Include shared map access** to off. This creates a cache-only package with an empty encrypted-profile field: no shared account, tracker key, or target-map profile. Existing cached map imagery, optional DEM, and AOL data retain their existing export behavior. Recipients retain their current account and map/bookmark. Enabling map access retains the previous profile-bearing package behavior and its credential/expiry requirements.

Android Import Config now accepts the same package token and QR URI as the camera path. File selection still supports JSON, ZIP, and QR images. Package previews distinguish cache-only packages.

Android transfers select a directly connected Wi-Fi/Ethernet route when present, rather than relying on the default internet route. The error panel offers Retry and explains that both devices must reach the same LAN and the sender must keep sharing. Expired tokens require a new QR. Sharing and importing use separate worker queues; stalled TLS/header reads time out. New exports use unique temporary filenames so preparing another package does not overwrite a live share.

Apple can export/import the cache-only ZIP format. Changing the map-access option invalidates a previously prepared share. The initial change supported file transfers only. The subsequent 2.2.9 update adds Android LAN-transfer QR reception through Apple Import Config, camera links, and QR images, plus direct ZIP routing. Apple continues to export ZIP files rather than hosting QR transfers.

## Field verification still needed

1. Export with shared map access off and no MA credentials configured; import on a device already using an incident bookmark. Confirm account, selected map, and bookmark access remain unchanged and cached tiles work offline.
2. Repeat with shared map access on; confirm the existing incident profile behavior.
3. On Android, import the same QR using Main Screen → Menu → Import Config, the camera link, and a saved QR image.
4. Test Wi-Fi without internet while cellular is enabled, interrupted transfer → Retry, sender Done → fresh share/QR, and repeated exports with the same incident name.
5. Test Android-to-Apple and Apple-to-Android ZIP imports.

## Validation

- Android focused import/token/preview checks: 14 passed.
- Android complete debug unit suite: 1,111 passed, no skipped tests or failures.
- Apple unsigned arm64 iOS Debug app build: passed after the final edits.
- Android releaseCheck passed, including release verification, APK assembly, and configured symbol processing. Its unit-test invocation was focused; the complete 1,111-test suite passed separately.
- Source whitespace/diff check: passed.
- Sender evidence is saved in `docs/validation/MA_Sender_2026-09-15.log`; it describes the previously installed app, not a test of the changes.
- No installation, store publication, modified-app UI test, ZIP round-trip on devices, or new device-to-device transfer was performed.
