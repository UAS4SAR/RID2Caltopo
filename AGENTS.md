# UI-thread performance — Android and Apple

This is an explicit, permanent requirement from the project owner.

**Never run a time-consuming or potentially time-consuming task on the main UI thread on either platform.** Main-thread rendering, small in-memory cache lookups, and other demonstrably quick, bounded work are fine. If completion time can grow with stored data, image size, mission size, filesystem/provider latency, network conditions, or contention, move the work to a worker before it starts.

- Keep cache scans, migration, eviction, bulk file access, archive creation/extraction, database/SAF operations, image decoding/encoding/processing, large parsing/geometry work, and blocking network operations off the UI thread. Check constructors, lazy initializers, view bodies, map tile callbacks, and completion handlers as well as explicit button actions.
- Android: use an appropriate background dispatcher/executor. A coroutine launched from a UI scope normally still runs on Main until explicitly switched. Never wait on a future, semaphore, worker, or blocking coroutine from Main.
- Apple: `Task {}` and `async` do **not** move work off `@MainActor`. Use a background actor, worker queue, or detached task with immutable Sendable inputs. Return only UI state and prepared render data to MainActor.
- Do not make the UI wait on a lock that a background task holds during disk access, cleanup, or other slow work. Reading a small status value must not wait for an entire cache operation.
- Preserve ordered durable writes, consent-before-publication guarantees, cancellation, and stale-result checks when moving work off-thread. Do not replace blocking UI work with races or unbounded background jobs.
- For performance-sensitive changes, trace the actual execution context and validate relevant behavior. A method named `async`, `load`, or `cache` is not proof that it is safe for the UI thread. If quick completion cannot be established, use a worker.

# Physical-device testing must preserve user data

- Never run Gradle `connectedAndroidTest` / `connectedDebugAndroidTest` on a user's populated device. The managed test runner can uninstall the target app during cleanup and erase its private data and app-owned removable storage.
- Install updates only in place (`adb install -r` on Android). For physical Android instrumentation, install APKs in place and invoke `adb shell am instrument` directly; do not use a runner that manages target installation, uninstall, or data clearing. Back up app-private and app-owned external data before any physical-device test. Do not uninstall, clear data, or reset a user's device.
