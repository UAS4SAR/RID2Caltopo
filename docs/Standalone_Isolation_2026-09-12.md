# Standalone flight independence — build 224

The operator confirmed that standalone tablets must not share live aircraft data, ownership, or flight confirmations. Organization/configuration access and archive uploads remain available.

## Behavior

- Apple requires a nonempty incident map ID before starting live Tracker coordination. Entering standalone stops the existing transport, resets peer protocol state, and reports standalone status.
- Android ignores the legacy standalone coordination preference at the runtime getter and startup gate. Cached location remains local; no no-map transport starts. Leaving an incident map stops peer coordination.
- Both settings screens remove the standalone coordination switch. Managed enrollment and imported configuration cannot enable it. Incident-map coordination remains supported.
- Archive upload credentials and HTTP upload paths are unchanged. This change does not prevent completed standalone archives from reaching the organization server.
- Previously implemented map-scoped peer confirmations remain as a second safeguard.

## Grounded altitude recording

`Screen_Recording_20260912_185043_RID2Caltopo.mp4` shows ATO alternating between rounded 0 and -0. Both measurement formatters now render signed zero as 0. Raw measurements and genuinely negative rounded values are preserved. The recording also shows AOL pending/numeric transitions; this formatting change does not resolve that separate refresh behavior.

## Validation

- Apple: 341 Swift Testing tests and 11 XCTest tests passed; iPad build succeeded.
- Android: 1,064 unit tests passed. Regression cases cover legacy opt-in, no-map location updates, active flight transitions, and leaving an incident map. Both platforms test signed-zero display and preservation of stale/unknown statuses.
- Canonical and Apple release notes match and pass metadata verification (3,996 characters).
- Device installation and release verification evidence is in `outputs/standalone-isolation-224/`.
- Automated checks are not field proof of two-tablet isolation, flight confirmation, or altitude behavior.

## Fresh iPad evidence

Build 224 was installed and launched in place. The new log identifies build 224, queues and presents the Matrice confirmation panel at 19:16:58 PDT, and records local confirmation at 19:17:03. Grounded ATO is displayed as 0 ft. AOL still alternates pending/available during reference refresh; that remains separate.

Android release verification and signed build passed. Build 224 installed in place on A5 Pro R52Y90C9XST at 19:22:31 PDT and launched successfully. Queried version is 2.2.7 (224); original installation time remains 07:27:53. All Crashlytics artifact upload tasks were excluded from this local build.
