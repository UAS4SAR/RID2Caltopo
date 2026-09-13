# iPad main-menu refresh

The operator reported that the Main Screen menu pulses about once per second after returning from entering Part 107 credentials. Source inspection found `monitorOperationalState()` runs every second and `AppleOperationalAlertCenter.update()` assigns published mute sets and alert arrays even when unchanged. `ContentView` observes that center and previously constructed the native menu inline beside the live bridge indicator.

The alert center now publishes only changed values. The static main menu lives in its own equatable view with stable presentation bindings and a separate toolbar item, so live telemetry/status invalidations do not rebuild its contents. All menu actions retain the same destinations. Alert computation and genuine status changes continue normally. Android has a separate Compose menu implementation; this fix is specific to SwiftUI.

The user's credential entry is acknowledged. No live pilot roster was inspected, so this work does not establish whether synchronization, callsign matching, or eligibility caused the earlier DCP warning. The menu issue is not evidence of a credential failure.

Validation: unsigned iOS device-target build log at `/tmp/menu-apple-build.log`. An open-menu check on the physical iPad remains required; no installation was performed.

Validation completed: unsigned iOS app build passed with the menu isolation and currency-date guidance. Both focused Swift tests and all six Android AircraftReadiness tests passed. App Store metadata verified at 3,995 characters. No on-device visual verification or installation.
