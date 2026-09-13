# Tablet incident briefing

The operator requested that per-search information be entered on tablets, not by editing the organization's standing BVLOS profile. The accepted UI is the connected map plus an optional incident name/reference and one briefing-notes field.

## Operator workflow

In the DCP, open **Incident & briefing**. The connected incident map is displayed and recorded automatically. Enter an optional incident name/reference (160 characters) and optional notes (4,000 characters). Notes may describe VO, operating area, limits, RTH, or other incident particulars. No field blocks publishing or recording.

Remembering the profile and incident details across batteries is on by default. Publishing stores them in the tablet's current assignment session. Map or organization changes clear that assignment; **New incident / clear details** clears it explicitly for another search using the same map or standalone mode. App restart ends this session; notes already in flight records remain intact. Briefing checklist acknowledgments remain per flight.

Tracker's profile editor now describes its fields as standing authority and standard, reusable conditions. Optional standing map restrictions are tucked under Advanced, preserving any existing restriction rather than removing it. No profile edit is needed for each search.

## Record preservation

`incidentBriefing` is stored beside the authority profile in the per-flight operating snapshot, together with the automatic incident map, organization scope, and assignment ID. It does not modify the organization catalog. Changes to notes or assignment context preserve the initial snapshot and append a timestamped transition. Reopening a confirmed flight can restore its notes only within the same organization/map; an old flight cannot seed notes for a new flight after the assignment has ended.

Tracker preserves the nested snapshot and shows the latest tablet briefing in the flight record. A later authority-profile correction retains the incident map and notes but does not carry old checklist acknowledgments into the corrected authority. Original uploaded values remain intact.

## Validation and rollout

- Swift OperatingProfilesTests: 9 passed.
- Android OperatingProfilesTest: 8 passed, Android compilation passed.
- Tracker operating-profile and flight-readiness tests: 12 passed.
- Tracker templates compile; the profile editor renders the per-search tablet guidance and optional map restrictions.
- Release notes are synchronized; App Store metadata validation passed (3,987 characters).
- iPad device-target app build passed; log: `/tmp/incident-briefing-ipad-build.log`.
- Tracker flight-record rendering displays incident details and escapes entered notes.

Installed at the user’s request on both tablets as 2.2.7 build 229; installation, launch, and installed version verified. Tracker website changes remain undeployed. See `outputs/tablet-install-20260913-build229/installation.md`.
