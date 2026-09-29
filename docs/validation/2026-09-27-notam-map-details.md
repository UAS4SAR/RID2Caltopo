# NOTAM map navigation and complete details — 2026-09-27

The operator's screenshots show KZOA 6/5757/2026. Its text describes possible ADS-B / ADS-R / TIS-B / FIS-B service unavailability in a 590 NM radius, effective October 1, 2026, 09:00–11:00 UTC. Zooming out on the device revealed its mapped boundary. This confirmed that the initial missing boundary was a viewport-scale issue, not proof of missing geometry.

A separate Android display bug hid the FAA source text when the translation field was empty, even when the original notice title/text was available. The summary also classified “AREA DEFINED AS 590NM RADIUS” as a generic polygon before checking for the radius.

Changes:

- Android and Apple notice lists offer **Show on map** for notices with valid geographic coordinates. The action opens the full local map and fits the notice's geometry with padding; point notices receive a useful local zoom. Missing geometry is stated explicitly and never replaced with the operator/query location.
- The map action clears aircraft focus, suspends automatic viewport adjustment, and consumes the request after applying it to a measured full map. Ordinary navigation does not retain a pending request to replay. Apple enables its NOTAM map layer when showing a notice.
- Android's map detail dialog also offers **Show on map**, and displays the original FAA text even without a translation.
- Apple's map points, lines and polygons expose the full notice through the existing map inspection sheet. The parser retains the source notice text even when no translation was supplied.
- Both platforms summarize service unavailability and use neutral **Effective** date labels. Android checks radius wording before generic area wording and no longer calls every radius a restriction. This does not change the existing NOTAM severity or temporal filtering policy.

Validation:

- Android: 20 NOTAM tests passed; debug APK build passed.
- Apple: six NOTAM tests passed, including the source-text-without-translation and large-area geometry regressions.
- Apple iOS Simulator app build: passed (arm64, unsigned Debug; final incremental build exited 0).
- No physical device installation, store submission or physical UI acceptance was performed. Existing unrelated workspace changes were preserved.

Logs are in `docs/validation/notam-map-20260927/`. The regression cases include a large intersecting area, invalid/missing geometry, repeated request identities on Android, and the service notice's wording and missing translation.
