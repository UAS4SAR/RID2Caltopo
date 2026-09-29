# Remembered decline and new local video — 2026-09-29

Both platforms preserve the current remembered “do not publish” decision for later RID-only flights. A new concrete local publisher identity mapped uniquely to the drone now permits one automatic Drone Confirmation panel. It does not clear publication suppression; the operator must confirm before publishing. Declining while video is active records the current publisher identity so repeated updates, retained LIVE state, and the same publisher returning do not repeatedly prompt. A genuinely different publisher can prompt again. Unknown publisher IDs are not new-session proof; resolving an already-declined unknown identity does not prompt by itself.

Android checks this exception before the normal ignored/local-archive-only and already-prompted gates. Apple passes eligible video exceptions into current-flight reconciliation; when another panel is open, it reconciles flight endings but defers consuming new candidates. Existing completed-flight guard remains in both paths.

Android: 33 ViewModel confirmation tests, four completed-flight guard tests, and 30 camera/PiP geometry/control tests passed (67 total). Debug APK built. Apple: 441 Swift Testing plus 34 XCTest tests passed (475 total); signed Debug build and deep/strict signature verification passed.

No device installation. Physical cases to test: decline a flight, later RID-only flight stays quiet; new local video offers confirmation; decline again and repeated same-stream updates stay quiet; new publisher offers again; publication remains off until confirmation. Also retest full-screen camera with expanded/collapsed split panes and PiP on/off.

Installation follow-up at 11:48 PDT: latest tested APK/app installed in place on A5 Pro R52Y90C9XST and Ken’s iPad 694108CB-8CBE-593D-ABE1-D9EDD947B901. Both launched successfully and report 2.3.7 (304). Android signature matches the previously verified installed certificate; iPad signature verified. Data preserved. Physical retest pending.
