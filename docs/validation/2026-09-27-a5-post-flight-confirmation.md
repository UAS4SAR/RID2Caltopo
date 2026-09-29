# A5 Pro post-flight Update Saved Drone diagnosis

Read-only capture from SM-X350 R52Y90C9XST on 2026-09-27, running 2.3.7 (286), PID 25005. App was not restarted, reinstalled, or dismissed. User reports the drone and controller had both been switched off. Raw capture: /private/tmp/a5-update-saved-drone-logcat.txt. Selected evidence: outputs/a5-post-flight-confirmation-2026-09-27/timeline.txt. All times PDT.

Flight: 1sar7DjMn4Pr_105008Sep27, DJI Mini 4 Pro, remote ID 1581F6Z9C24BH0036EJL.

- 10:49:43.772: initial confirmation queued from unique configured live stream.
- 10:49:46.070: operator saved local confirmation.
- 10:54:44.410: aircraft updates aged out; publisherActive=true, videoActivityAgeMs=-1, combinedIdleMs=25372, peerAgeMs=-1.
- 10:54:49.532: terminateTrack begins after combined idle 30.495 seconds, despite pairedVideoActive=true.
- 10:54:49.539: local-track-finished callback clears prompted/confirmed flight state.
- 10:54:49.541: drone marked inactive.
- 10:54:49.542: displayed drone list becomes empty.
- 10:54:49.543: confirmation queued again for the same Mini with reason=active flight.
- 10:56:31.672: StreamsViewModel still reports live=1, ffmpeg=1.

Source chain: R2CActivity.kt passes StreamState.LIVE, non-local-playback designators to R2CViewModel.onLiveStreamDesignatorsChanged, which retains matching saved specs in liveStreamConfirmationSpecs. onLocalTrackFinished clears confirmedCurrentFlightRemoteIds, peer confirmation, and prompt suppression but does not retire that stream-derived eligibility. onDroneSpecsChanged scans the union of droneSpecs and liveStreamConfirmationSpecs and explicitly accepts membership in the latter even when no active RID drone remains. It therefore queues the same saved aircraft again immediately after track termination. R2CView.kt renders the title Update Saved Drone for an existing mapped identity.

Conclusion: the prompt was locally regenerated from lingering live-video eligibility after confirmation cleanup, not evidence of a new received flight. The log/source timing identifies the immediate trigger. The reason the video registry continued to report LIVE after power-off is not yet established; a retained LIVE flag does not prove incoming video or telemetry. Exact hardware power-off times are user-reported and not independently logged.

No source fix or installation was performed during this diagnosis. Corrective work should retire completed-session confirmation eligibility until fresh aircraft evidence or a genuinely new video session arrives, while preserving required confirmation for the next flight. Existing tests cover saved-decision reset and ignored-flight suppression separately; the saved-confirmation plus lingering-LIVE combination needs a regression test. Apple behavior has not been established for this incident; audit its counterpart before implementing a shared behavioral fix.
