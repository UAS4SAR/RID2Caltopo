# 2.5.4 build 418 — stable iPad main menu

October 25, 2026. Installed in place and launched on the iPad as **2.5.4 (418)**.
The A5 Pro remains on 417; this correction changes the Apple menu only.

## Cause and correction

The user supplied a recording showing the open main menu's rows redrawing about
once per second. The live screen constructed the outer SwiftUI Menu inline.
Its separate item view also subscribed to a display-units setting it never used.
Isolating only the items did not prevent parent updates from rebuilding the menu.

Moved the complete menu, including its label, into an equatable view without the
unused settings subscription. Its labels and enabled states are constant; action
bindings retain their SwiftUI state locations. Parent telemetry updates therefore
leave the open menu intact. Live status updates continue normally. No filesystem,
network, parsing, lock waits or other unbounded work was added to the UI thread.

## Regression evidence

A Simulator-only fixture exercises the production menu with one-second parent
updates and counts menu body evaluations. Before the fix, its counter increased
from **6 to 14** across eight updates, failing the stability assertion. After the
fix, the same test passed: the counter remained unchanged while updates continued,
the menu stayed available, and About & Privacy and Manage Storage actions updated
their presentation bindings correctly.

The fixture bypasses accounts, hardware, stored operational data and acknowledgment
actions. It is excluded from the physical-device Release package. The test and
project generator are retained under `apple/Tests`; run the generated MainMenu
scheme on a dedicated Simulator:

```sh
python3 apple/Tests/run-settings-units-ui.py /tmp/r2c-menu-ui MainMenu
```

The baseline assertion and passed-result summary are retained in
`apple/Build/ReviewCandidate-418/menu-before.json` and `menu-fixed-results.json`.
The Simulator screenshot was inspected. The physical-device launch check does
not itself establish a visual check of the open menu on that device.

## Package and installation checks

- Apple Release archive compilation, unsigned-archive checks, device signing,
  team identity, target-device provisioning and strict signature checks passed.
- The package identifies 2.5.4 (418), contains exact canonical release notes and
  the correct policy URL, retains all 24 shared About rows and the existing safety
  acknowledgment, and excludes the Simulator test fixture.
- Metadata validation, four store-wording tests and seven privacy tests passed.
- Refreshed the operational-only safety backup with three changed/new files
  since 417. The active diagnostic log includes entries appended after inventory.
  Authentication and saved-configuration stores were not copied.
- The update retained app and Keychain identity. No uninstall, data clear, reset
  or physical-device test runner was used. No acknowledgment was accepted.
- The app reports 418 and launched with a running process. All **15,459**
  pre-install operational files remained. Only the active diagnostic log grew,
  from 8,688 to 9,019 bytes; other baseline sizes were unchanged.

[Package hash](../../apple/Build/ReviewCandidate-418/artifacts.json),
[source inventory](../../apple/Build/ReviewCandidate-418/source-inputs.json),
build logs and device summaries are retained locally. The new public policies
remain unpublished. No store upload, review message or resubmission occurred.
