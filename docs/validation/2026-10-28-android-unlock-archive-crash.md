# Android unlock / archive recovery crash — 2026-10-28

The initial local build below used 2.5.4 (432). The owner subsequently requested
2.5.5 (432) for both platforms; that is the distribution target. Historical
artifact hashes and installation records below describe the earlier local build.

## Incident evidence

The Galaxy Tab S11 Ultra (SM-X930) running the Play-distributed 2.5.4 (431)
accepted the device credential, then crashed during operational-storage migration.
The first recorded failure was archive replacement verification; the subsequent
startup failure was `Archive recovery requires its verified staging file; nothing
was removed.` Android recorded repeated application crashes at 16:01–16:03 PDT.

The failed recovery journal referred to a mission original JPEG. The final file
existed, its staging and previous paths did not, and the 3,514,368-byte final
file's on-disk SHA-256 exactly matched the journal. No original was deleted or
modified during diagnosis.

The matching build-431 R8 mapping resolves the first fatal path to:

- `AwaitingMapPublicationPanel` pending-flight refresh;
- `AwaitingMapFlights.pending()` → operational-state file preparation;
- `OperationalStorage` migration → `VerifiedArchiveTree.put/recover/finish`.

Subsequent fatal stacks came from `MediaMTXRecordingSync.syncAll()` during
service shutdown on `mediamtx-native-control`. Its staging-directory preparation
was outside the method's exception handler.

## Storage read behavior

The archive adapter used `ContentResolver.openInputStream()`. Android's
[FileSystemProvider implementation](https://android.googlesource.com/platform/frameworks/base/+/988b22c44f51/core/java/com/android/internal/content/FileSystemProvider.java)
routes read-only opens of visible storage through MediaStore, while non-truncating
read/write document descriptors use the stored file. Media processing can change
the returned media bytes, including EXIF redaction. Renaming an opaque staging
file to `.jpg` can therefore change verification results without changing the
durable file.

This explains the observed correct final checksum and failing document read.
The original release process's transformed stream was not captured directly;
the provider mechanism is an evidence-based diagnosis, reproduced with the
provider fixture. Verification on this tablet still requires the repaired,
Play-signed update.

## Repair

- Read retained document bytes through a non-truncating `rw` descriptor. A
  provider that cannot supply it fails before source removal; there is no
  fallback accepting a different checksum or discarding the recovery journal.
- Return pending-flight storage errors as panel state with explicit retry.
  Stop the failed polling loop rather than repeatedly starting bulk migration.
  Cancellation propagates. Publication decisions still require a durable save.
- Include recording-staging preparation in the sync error handler so service
  teardown cannot terminate the app when Archive Dir recovery fails.
- Compile the instrumentation APK in `releaseVerification`. Repair stale
  instrumentation calls to the background catalog and mapping-overlay APIs.
- The initial local repair used 2.5.4 (432). The owner then selected 2.5.5 (432)
  on both platforms. The initial 2.5.4 artifacts are historical records and must
  not be used for the new distribution.

All archive work runs on existing storage workers. `awaitingMapStorage` switches
to `Dispatchers.IO` before invoking storage code, then returns results for UI
state updates. Track/incident relevance geometry, review ordering and clue-count
preparation also run there rather than in the Compose body. No UI thread waits
on the archive locks or provider operations.

## Why previous testing missed it

The earlier release gate passed 1,773 JVM tests and native/packaging checks but
did not compile or run instrumentation tests. The instrumentation sources had
stale API calls. The filesystem tests and document-provider fixture returned the
same bytes for ordinary media reads and stored-document reads, so they did not
model the media transformation after publication. Fault tests also did not cover
archive preparation failing at the UI pending-flight or service-shutdown entry
points. The test count did not establish coverage of this upgrade state.

## Validation

- 18 targeted JVM regressions passed, including failure containment, cancellation,
  worker dispatch, interrupted writes, and independent archive/recorder lanes.
- Full JVM suite: 1,777 tests, zero failures/errors, one skipped.
- The host logs include JaCoCo 0.8.12 warnings for Java 25 JDK locale classes.
  These do not fail test execution; they are not evidence of complete coverage.
- Emulator `OperationalStorageTest`: nine tests passed. The new provider case
  first reproduces the exact staging-file exception with the old read behavior,
  then recovers the same journal with the corrected adapter. It checks original
  bytes across migration and restart.
- Instrumentation APK now compiles. Tests ran with direct instrumentation on
  the isolated emulator, never a managed runner on the populated tablet.
- Final build 432: strengthened release gate, APK/bundle production and APK
  signature verification passed. All 1,037 bundle payload entries passed
  signature/content-digest verification. Candidate records, source hashes,
  checksums, R8 mapping and test/build logs are in `build/releases/2.5.4-432/`.
  Bundle SHA-256: `a10b79df7fdf01a5fe4d3d4985be89a0745db244bb69c6932c168f98c7bbaea3`.
  Store publication is pending owner approval.

The tablet's Play signing certificate differs from the local signing certificate.
An in-place repair must be delivered through Play; uninstalling would lose the
installed app's private state and is not an acceptable workaround.
