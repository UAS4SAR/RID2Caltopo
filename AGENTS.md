# UI-thread performance — Android and Apple

This is an explicit, permanent requirement from the project owner.

**Never run a time-consuming or potentially time-consuming task on the main UI thread on either platform.** Main-thread rendering, small in-memory cache lookups, and other demonstrably quick, bounded work are fine. If completion time can grow with stored data, image size, mission size, filesystem/provider latency, network conditions, or contention, move the work to a worker before it starts.

- Keep cache scans, migration, eviction, bulk file access, archive creation/extraction, database/SAF operations, image decoding/encoding/processing, large parsing/geometry work, and blocking network operations off the UI thread. Check constructors, lazy initializers, view bodies, map tile callbacks, and completion handlers as well as explicit button actions.
- Android: use an appropriate background dispatcher/executor. A coroutine launched from a UI scope normally still runs on Main until explicitly switched. Never wait on a future, semaphore, worker, or blocking coroutine from Main.
- Apple: `Task {}` and `async` do **not** move work off `@MainActor`. Use a background actor, worker queue, or detached task with immutable Sendable inputs. Return only UI state and prepared render data to MainActor.
- Do not make the UI wait on a lock that a background task holds during disk access, cleanup, or other slow work. Reading a small status value must not wait for an entire cache operation.
- Preserve ordered durable writes, consent-before-publication guarantees, cancellation, and stale-result checks when moving work off-thread. Do not replace blocking UI work with races or unbounded background jobs.
- For performance-sensitive changes, trace the actual execution context and validate relevant behavior. A method named `async`, `load`, or `cache` is not proof that it is safe for the UI thread. If quick completion cannot be established, use a worker.

# Routine installs and physical-device testing

This is an explicit requirement from the project owner (October 26, 2026).

- A routine request to install or update the app on a tablet authorizes building/signing as needed, an in-place installation, and lightweight verification of the installation result, app identity and version.
- Keep the existing marketing version and build counter for intermediate local debug installs. Increment only when the user requests a new version or a distribution/upload requirement needs a new build identity. Record the artifact hash and install timestamp to distinguish local replacements.
- Full app-private, external-storage or archive backups require an explicit user request. Do not add them automatically to routine installs or to a request for device testing.
- Extra on-device UI, functional or instrumentation testing requires an explicit user request. Do not expand an install-only request into device testing or release qualification, automatically launch/exercise the app, or add storage scans, data dumps or before/after data comparisons.
- Installation and basic read-only OS installation/version checks are not physical-device tests and do not require a backup. Existing app data, settings, credentials and permissions must be preserved.
- Install updates only in place (`adb install -r` on Android). Do not uninstall, clear data, or reset a user's device.
- Never run Gradle `connectedAndroidTest` / `connectedDebugAndroidTest` on a user's populated device. The managed test runner can uninstall the target app during cleanup and erase its private data and app-owned removable storage.
- For explicitly requested physical Android instrumentation, install APKs in place and invoke `adb shell am instrument` directly; do not use a runner that manages target installation, uninstall, or data clearing.

# App-managed operational storage

This is an explicit requirement from the project owner (October 25, 2026).

- All significant storage resources used by the app must be accounted for and manageable through Main menu > Manage Storage on both platforms.
- Android retained operational data belongs under the Map Tile Cache or application-log/archive subdirectories of the selected Archive Dir. Independently selected app-private SD-card locations are not a substitute for Archive Dir. Existing exceptions are migration work to resolve, not the intended design.
- Temporary working files are invocation-scoped: clean them after their operation and recover/clean abandoned temporary work at startup, on a worker before new work can conflict with it. Normal exit cleanup alone is insufficient after a crash or process termination.
- Temporary files may use any suitable filesystem; the Archive Dir requirement applies to retained operational data. Report temporary working space separately from retained data and its storage allowances.
- An intermediate file unchanged for 60 minutes must not imply an active writer merely because it exists or has a staging filename. Preserve explicit live-operation protections; reaching the inactivity threshold alone is not a deletion command.
- Originals, saved maps/models, unfinished recordings, pending uploads and processing checkpoints needed after restart are retained operational data. Do not label them temporary or delete them as scratch; place and account for them in the managed retained categories.
- Preserve existing settings and credential persistence/security requirements. Do not move credentials into shared Archive Dir or credential-inclusive backups as part of operational-storage consolidation.
- Owner-approved exception: private settings, saved browser sign-ins, credentials, connection/enrollment keys and certificates, and Android/service SDK installation state remain in their private stores and are excluded from Manage Storage totals and cleanup controls. This exception does not cover retained maps, media, logs, uploads or processing checkpoints.
- Migration must preserve data and references, verify durable destination contents before removing source copies, handle unavailable archive access without silently creating another persistent bulk-data store, and remain entirely off the UI thread. Report incomplete/unavailable storage accurately.
