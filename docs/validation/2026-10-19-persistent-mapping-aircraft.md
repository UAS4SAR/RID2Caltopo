# Persistent mapping aircraft — 2.5.3 (399)

The Define Map Mission aircraft choice now persists across dialog closure and
app restarts on both platforms. A fresh or unrecognized preference uses Matrice
4TD. Configured placeholders such as Matrice 400 retain their selection but
remain disabled for mission generation until a payload profile exists.

Android reads the small preferences DataStore on Dispatchers.IO before enabling
the aircraft chooser or mission connection. A process-owned, conflated writer
commits the most recent selection in order, outliving dialog cancellation and
without accumulating write jobs. Opening another panel sees the requested value
even while its write finishes. Camera selection remains compatible with the
chosen aircraft; an already-compatible restored camera is retained.

Apple loads and saves through a non-MainActor preference actor. The chooser and
mission connection remain disabled until the initial read completes. Each
selection captures a monotonic revision so an older delayed save cannot replace
a newer choice. Closing the panel does not cancel its save task. Aircraft
changes still choose an available camera. No filesystem preference work was
added to view initializers or bodies.

## Verification

- Android persistence test writes rapid M30/M30T/M4T changes, waits for the
  durable DataStore value, closes its scope, reopens the file and confirms M4T.
  Also checks retained placeholder selection and unknown-model fallback.
- Android mapping suite passed: 153 tests, no failures/errors, one optional skip.
- Apple persistence test reopens independent stores for the same isolated suite,
  checks M4T/M30T retention, stale-write rejection and fallback. Combined
  persistence/aircraft/route suite: 13 XCTest cases passed.
- The existing controller bridge regression still passes. Build 398's owner
  compatibility change remains unconfirmed on the owner's M4T; no further owner
  workaround or relaxed readiness claim is included here.

The owner confirmed the error is displayed in the R2C H5 connection page inside
Pilot 2 on the controller. Read-only Pilot logs contained no media-owner getter
value. Static inspection of the installed Pilot APK exposed only its protected
wrapper through the standard DEX tool, and did not establish runtime owner
behavior. No controller configuration/software was changed. The full controller
error, specifically its `(reported …)` detail, was requested.

Both build-399 device artifacts compile; canonical notes match their packages.
The signed Apple app passed verification and was installed in place on Ken's
iPad. Independent installed-app query confirms 2.5.3/build 399. Existing data was
not cleared; no app launch, automated device test or controller/flight action was
initiated. A5 Pro is disconnected, so its build-399 APK is prepared but not
installed. Pilot 2's full owner diagnostic remains outstanding.
