# Incident-map publication recovery candidate

27 September 2026. Isolated checkout: `/Users/kjt/.codex/worktrees/outage-recovery/RID2Caltopo`, based on `e3e1cab504e7cc1048a0d51b7ba146006870516e`. Includes the earlier two Android Tracker reconnect/confirmation repairs. No changes were merged into the shared working checkout, installed on a device, or deployed. No live map or Tracker objects were created.

## Operator behavior

- A confirmed flight retains its map/team association. Changing maps does not silently authorize publication to the new map.
- A flight approved before selecting a map remains available under **Flights awaiting a map**. The operator reviews the named destination and chooses Publish, Later, or Keep local.
- Accepting an active flight includes its recorded earlier points, then permits live publication subject to the existing ownership checks. Accepting a completed flight queues its recorded geometry for archival publication.
- Associated clues already marked for publication follow the accepted destination. Clues explicitly kept local remain local. Existing unassigned clues are matched by aircraft/designator and capture time within the flight; durable flight-ID association would further strengthen this matching.
- A recent flight near a Point named `IC` (case-insensitive, trimmed) receives a relevance suggestion. Travel tracks, arbitrary map features, and viewport extent are excluded. Provisional ranking window is 24 hours and 10 miles; this ranks offers and does not authorize publication or remove other flights from manual review.
- If publication to a selected map never began, retain a review offer for that original map rather than silently losing the flight or assuming another receiver did not publish it. Previously started interrupted publications retain automatic recovery.
- Per-flight decisions survive restart. Recovered sessions are treated as completed flights. The existing short-flight discard action removes only the corresponding offer.

## Recovery changes

Android retries interrupted track finalization on successful incremental map polls. In-flight recovery is serialized per object and active publications are excluded. Shape and LiveTrack deletion requests carry an explicit original map ID, including asynchronous completion paths. Deferred flight coordinates are converted from local longitude/latitude/altitude/timestamp records to CalTopo geometry without the timestamp component.

Apple retries pending track finalization every 30 seconds independently of device-marker/location updates. Configuration changes save buffered observations before replacing the client. Recovery excludes active identities and preserves failures added during actor suspension. Interrupted-publication records no longer truncate at 5,000 points.

Android clue records now persist destination team, upload state, and errors. New pending clues use stable marker, media, and attachment identities, one photo transaction at a time. Pending work is retried every 30 seconds while its map is available. Certain permanent HTTP failures stop automatic replay. Legacy records are not blindly republished because they lack reliable receipts.

Apple clue records now persist map/team destinations. Legacy records without destinations require review. Configuration generation checks prevent cancelled tasks from clearing replacement tasks or applying errors to a newer configuration. Photo attachment identity is stable across retries. Pending clue media is protected from automatic age/space cleanup on both platforms.

## Verification

- Complete Android unit suite passed after the implementation, including the earlier Tracker fixes and new consent, destination, restart, clue, IC-ranking, and serialized-recovery tests.
- Final Android coordinate-shape adjustment passed eight focused awaiting-map/journal tests.
- Swift shared suite: 420 tests passed.
- Apple Debug arm64 Simulator build passed with signing disabled.
- `git diff --check` passed.

Logs copied to `outputs/a5-wan-review-20260927/` in the shared checkout:
`phase2-android.log`, `phase2-final-geometry.log`, `phase2-swift.log`, `phase2-apple-build.log`.

## Qualification still required

This is a local test candidate, not field qualification. No physical UI, device installation, or live outage test has been performed. The new stable MapMediaObject request identity must be verified against CalTopo with controlled test objects, including a committed request whose response is lost. Local request tests do not establish server idempotence.

Before field release, test Taylor Site and the prepared mySAR organization with: flight beginning without a selected map; map selection during flight; map selection after landing; original-map outage through landing; map/team switch during retry; process restart at each upload stage; duplicate receiver ownership; Keep local; short-flight discard; and multi-day pending-photo retention. Verify the complete historical geometry and a single marker/photo attachment after recovery. Inspect both Android and Apple UI and preserve existing operational configuration.

The broader outage audit still has separate open work, including Tracker archive replay/receipts, ownership-lease expiration, and recording-transfer recovery. This candidate does not claim those are repaired. Legacy flights lacking recorded publication intent are not automatically inferred as new offers.
