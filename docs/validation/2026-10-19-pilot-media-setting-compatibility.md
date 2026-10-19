# Pilot 2 media getter compatibility — 2.5.3 (398)

## Report and diagnosis limits

The owner reported that connecting Pilot 2 with M4T stopped at
“Pilot 2 has not confirmed download owner.” The controller's actual getter
response and Pilot version were not captured. The exact field cause is therefore
not established by this source review.

The shared H5 bridge required `mediaGetDownloadOwner` data to be strictly the
number `0`. A successful response with string data `"0"` failed this check, even
though the value represents the configured owner. Similar strict comparisons
existed for original-photo type and automatic photo/video upload flags.

DJI's [JSBridge reference](https://developer.dji.com/doc/cloud-api-tutorial/en/api-reference/pilot-to-cloud/jsbridge.html)
([official source](https://github.com/dji-sdk/Cloud-API-Doc/blob/master/docs/en/60.api-reference/10.pilot-to-cloud/30.jsbridge.md))
defines owner `0` as this controller and `1` as another. It describes the outer
bridge reply as JSON text with success code and typed `data`. Actual firmware
serialization variation remains a hardware verification item.

## Change

- Enum checks accept the expected number or its exact decimal string.
- Boolean settings accept the expected boolean, 0/1 or exact corresponding
  `"true"`/`"false"`/`"0"`/`"1"` scalar. No general truthiness or numeric coercion.
- Null, missing, empty, padded, array, object, unknown string, invalid enum and
  wrong-owner values remain unverified. Existing bridge error codes remain fatal.
- Readback, bounded asynchronous propagation, drift repair and stale-attempt
  guards remain in place. The mission section still stays hidden until all four
  photo-return settings are verified.
- A failure identifies the setting, observed primitive type and recognized scalar
  value. Arbitrary strings and object contents are omitted, avoiding credentials
  or controller identifiers in the diagnostic.
- Shared asset ships in both apps. Both platforms incremented to build 398,
  marketing version remains 2.5.3; canonical notes and store mirror updated.

The new checks run in the existing asynchronous H5 connection flow, with fixed
four-item scans and timers. No native UI-thread disk/network/image work added.

## Verification

`tools/mapping/tests/test_native_cloud_bridge.cjs` now covers owner-only string
encoding, all-string settings, numeric/string boolean encodings, asynchronous
setter propagation, post-connection drift repair, wrong/malformed responses,
credential-safe errors and the existing license/token/callback recovery cases.

- New regression against the pre-fix bridge: fails with `Connection needs
  attention` where `Connected` is expected, matching the reported failure stage.
- Same regression against the repaired bridge: passes.
- Existing no-flight-control assertions, refusal handling and stale callback
  tests: pass.
- Android debug build: passed. Apple development device build and packaged-asset
  checks are recorded below after completion.

This fixes a reproducible compatibility defect, but does not yet prove that
string serialization caused the owner's M4T failure. If Pilot still reports a
problem, the new diagnostic distinguishes a wrong owner from a data-shape issue.

## Packaged verification

- Apple development device build: passed; signature verification passes with
  normal signing trust-store access (the sandboxed verifier could not access
  the trust chain).
- Both app packages report 2.5.3/build 398 and contain identical repaired bridge
  bytes plus canonical notes. Bridge SHA-256:
  `a6a3635a0b6d81049b9571b6c11ed68d7801fb30557d7389a7f461df2d6f765c`.
- Store notes, metadata and UI copy checks pass.

## Owner retest handoff

The owner confirmed the failed connection was hosted by the iPad. Build 398 was
installed in place over the paired device connection; independent device query
confirms 2.5.3/build 398. No app data was cleared and no operational session or
flight was started. The A5 Pro was disconnected; its build-398 APK is prepared
but has not been installed.

The M4T's connected DJI RC Plus 2 controller reports Android 11. Pilot package
`com.dji.industry.pilot` reports updated version 17.7.1.3 (code 388876), with an
older system package 17.7.0.11 also present in the package-manager dump. No
controller configuration or installed software was changed. The actual failed
getter value remains unknown; owner retest on build 398 is pending.
