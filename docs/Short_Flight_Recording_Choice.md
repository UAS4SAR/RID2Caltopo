# Short-flight recording choice

Implemented locally for Android and Apple, September 11, 2026. Not deployed or device-qualified.

When a completed flight is both shorter than 0.1 statute mile (160.9344 metres) and briefer than 60 seconds, the foreground app offers:

**Short flight — Record (Yes/No)?**

The nonmodal panel identifies the aircraft, shows a 10-second countdown, and emphasizes Yes. Yes records immediately. No skips this device's pending flight log and its Tracker upload. No response records automatically at the deadline. A No arriving at or after the deadline cannot reverse Yes. Every pending flight has its own identity and deadline; there is no shared decision between aircraft.

Backgrounding or removing the panel accepts Yes for pending requests. Flights finalized without an active panel record normally. Unknown/invalid measurements and flights at either exact threshold record normally without a prompt. Duration is observed flight/track time, not the later inactivity-detection waiting period.

Archiving choices do not delay ingestion, aircraft cleanup, or subsequent missions. Flight metadata and aircraft identity are captured before the choice. Existing Tracker reports, video recordings, and already-published live tracks are not deleted. Tracker's acceptance of short flights remains unchanged; this is a choice made on the recording device, not a new server import filter.

## Validation

Android compilation and the full 1,013-test regression suite passed before the additional explicit no-response test; the five focused short-flight tests cover strict boundaries, unknown measurements, Yes/No, independent aircraft, background acceptance, no-response timeout, duplicate protection, and late No. Apple has corresponding five focused tests and an arm64 Simulator build. Physical device/UI operation remains to be checked.
