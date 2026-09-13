# iPad video-only startup correction

The 16:05 field test of build 215 failed: video played under the lowercase stream
`1sar7djmtrc4td`, but no aircraft track or altitude appeared. The copied log is
`outputs/ipad-video-only-20260912/flight-log.txt`.

At 16:04:37.133, confirmation succeeded for the configured Matrice. At
16:04:37.146, the log reported `activeTelemetry=0 liveUnpaired=1`. Recording
completion subsequently retained 3,492 SEI-bearing frames. No automatic binding
was logged. The test disproved the prior assumption that the configured identity
had already reached the telemetry ingestion path.

Two integration defects were found:

1. Confirmation could resolve a configured stream without RID, but automatic
   telemetry binding still searched existing tracks. Stream ingestion then
   required that missing binding, creating a circular dependency.
2. The publisher store normalizes IDs to uppercase while the live session retains
   the stream's lowercase spelling. The new provider tested the raw session ID
   against that normalized set and rejected it even if a binding existed.

Build 216 pairs uniquely configured live publishers before track lookup and uses
one normalized publisher-activity query. Manual pair/unpair choices remain intact;
ambiguous configuration cannot guess an aircraft. Diagnostic logs now identify a
configured video binding and the first accepted video position.

The regression begins with the actual lowercase session ID and an empty track
store, applies its mixed-case configured designator, passes the publisher check,
creates the video observation and verifies numeric altitude. Additional coverage
checks duplicate reconciliation, ambiguous mappings, explicit pairing/unpairing,
and publisher stop. All 334 Swift Testing tests and 10 XCTest tests passed.
Android's configured binding and publisher paths already normalize IDs; no Android
change was required for these two defects.

The signed iPad build and in-place reinstall are tracked in the same output folder.
A fresh physical stream test remains necessary to verify the complete native video,
binding, ingestion, tile and map behavior on the controller and iPad.

Build 216 installed successfully in place on Ken's iPad at 16:13. Launch succeeded
at 16:14 and the installed-app query confirmed version 2.2.7, build 216. No uninstall
or app-data reset was performed. Controller/video field verification remains pending.
