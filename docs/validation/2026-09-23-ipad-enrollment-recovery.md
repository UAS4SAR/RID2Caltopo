# iPad organization enrollment recovery

Fresh device evidence: Ken's iPad, build 2.3.5 (249),
`outputs/ipad-config-20260923/latest.txt`.

At 21:24:02 and 21:24:32 PDT enrollment returned HTTP 200 and required
reauthentication. Both initial protected configuration downloads were deferred.
No successful managed configuration application was logged; the coordinator
later reported "Standalone flights stay independent" with no selected map.
The operator reports completing the sign-in page.

The Apple callback and browser-return paths only reconfigured the live peer
coordinator. That coordinator deliberately does not connect without a selected
map, leaving a fresh installation unable to retrieve the organization credentials
needed to choose one. Android already calls retryManagedConfigurationBootstrap
from resumeTrackerAfterReauthentication independently of peer coordination.
The direct Apple enrollment path also reported success while authentication was
pending, competing with its sign-in alert.

Build 250 adds a separate foreground/startup/sign-in configuration bootstrap,
coalesces duplicate requests, throttles ordinary foreground retries, bypasses
that throttle for explicit sign-in returns, and discards responses after the
credential or active profile changes. Applying a recovered snapshot also saves
the recovered home profile. Pending sign-in no longer emits an imported-success
alert. Replayed coordinator generations without a current challenge no longer
clear credentials.

Validation: 385 Swift tests passed, including no-map bootstrap, post-auth retry,
old-organization response rejection, duplicate in-flight suppression, and same-version credential recovery without reapplying an unchanged snapshot.
Device build and physical recovery evidence are recorded in
`outputs/ipad-config-20260923/build250`.
The published v2.3.5 tag remains unchanged; this is a follow-up fix.

## Subsequent reset/import attempts and campaign state

The operator reset persistent state and reused the same token. Fresh iPad logs
at 21:39:29 and 21:40:34 PDT show HTTP 400 with "Enrollment campaign is not
active." This is separate from the post-authentication bootstrap defect.

Read-only production database verification found NCSSAR's "Fly More!" campaign
exhausted at 40/40 uses, with its expiry still 2026-10-07 22:17:42 UTC. Its last
successful redemption was Android at 2026-09-24 04:34:50 UTC (21:34:50 PDT).
The 40 enrollments comprise 29 Android enrollments across 12 installation IDs
and 11 Apple enrollments across 4 installation IDs. No campaign modification
was performed as part of this diagnosis.

Build 251 additionally keeps Import Config open during the request and shows
failures inline, avoiding a fast error alert during sheet dismissal. Success
and sign-in prompts are deferred until the sheet finishes closing. All 385
Swift tests and the signed Release device build passed. Installed in place on
Ken's iPad, installation sequence 1916. End-to-end enrollment verification
requires available campaign capacity; the existing token need not be replaced.

## Browser callback lost behind privacy gate — build 252

After explicit operator approval, campaign capacity was increased from 40 to
100 and its state restored to active at 21:51:52 PDT. The token generation,
October 7 expiry, redemption count, and existing credentials were preserved;
audit event 2e672bc8-2705-46c5-9539-567274d48b53 records the change.

The fresh iPad log shows enrollment HTTP 200 at 21:52:47, repeated main-screen
mounts, and no sign-in completion callback. The server confirms the iOS device
is active and signed in, with a successful reauthentication audit at 21:52:59.
The privacy gate removes monitoredRoot while inactive; both onOpenURL and the
foreground recovery handler were attached to that removable subtree. Build 252
moves both handlers onto the persistent outer NavigationStack, preserving the
existing device-unlock gate and deferred-URL handling. Android handles intents
at Activity level and does not share this SwiftUI handler-lifetime defect.

A structural regression test checks that URL and browser-return handlers remain
on the persistent root. Physical browser-return qualification remains separate
from the automated source/build checks.

Build 252 validation: all 386 Swift tests passed; signed Release device build
and strict signature verification passed. Installed in place on the connected
iPad at 21:59:08 PDT, installation sequence 1924, and launched at 21:59:20.
Device unlock and verification of restored settings remain pending operator
interaction. No token replacement or persistent-state reset is needed.

## Physical startup failure in build 252; replacement build 253

The operator unlocked the iPad and reported an exit. Two new device crash
reports at 21:59:36 and 21:59:41 confirm EXC_BAD_ACCESS / stack-guard protection
failure while Swift decodes nested generic view metadata. The main-thread
stack leads through ContentView.startupRoot, lifecycleRoot, mediaMonitoredRoot,
lifecycleEventRoot, monitoredRoot, and body. This is a confirmed runtime failure
of build 252 despite passing source tests and compilation; it is not evidence
of an enrollment rejection or configuration-apply failure.

Build 253 separates navigation and authentication presentation from startup
and uses stable AnyView boundaries between the existing screen layers to bound
the generic type depth. It retains the persistent callback/foreground handlers.
All 386 Swift tests passed again. Device-build and physical validation follow.

Build 253 signed Release build and strict signature verification passed.
Installed at 22:04:11 PDT (sequence 1932), launched at 22:04:24, and process
1363 was still present in a subsequent device process snapshot. Post-unlock
startup and restored organization settings remain pending operator validation;
process presence alone does not qualify the main-screen crash fix.

Operator follow-up confirmed build 253 integrated the credentials, then a full
persistent-state reset and clean enrollment completed without problems. The
operator observed two Face ID scans during initial recovery; this remains a
separate local-authentication UX observation, not a failed Tracker sign-in.
