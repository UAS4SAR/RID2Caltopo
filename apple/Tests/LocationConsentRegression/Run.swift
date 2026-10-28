import Foundation
import CoreLocation

@main struct LocationConsentRegression {
    @MainActor static func main() {
        let suite = "org.ncssar.location-consent-regression.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let fix = CLLocation(latitude: 39.1, longitude: -104.2)
        func savedFix() {
            defaults.set(39.1, forKey: "location.lastPhysical.latitude")
            defaults.set(-104.2, forKey: "location.lastPhysical.longitude")
            defaults.set(1, forKey: "location.lastPhysical.horizontalAccuracy")
            defaults.set(Date().timeIntervalSince1970, forKey: "location.lastPhysical.timestamp")
        }
        savedFix()
        CLLocationManager.initialStatus = .denied
        CLLocationManager.cachedLocation = fix
        let denied = AppleLocationProvider(defaults: defaults)
        denied.start()
        precondition(denied.lastLocation == nil)
        precondition(defaults.object(forKey: "location.lastPhysical.timestamp") == nil)
        precondition(denied.consent.currentGeneration == nil)
        print("PASS denied startup rejects both system and persisted cache")
        denied.stop()

        CLLocationManager.initialStatus = .authorizedWhenInUse
        let provider = AppleLocationProvider(defaults: defaults)
        var invalidations = 0
        provider.locationUnavailableHandler = { invalidations += 1 }
        provider.start()
        let manager = CLLocationManager.latest!
        provider.locationManager(manager, didUpdateLocations: [fix])
        precondition(provider.lastLocation != nil && manager.locationUpdates)
        let oldGrant = provider.consent.currentGeneration!
        precondition(provider.consent.allows(oldGrant))
        precondition(defaults.object(forKey: "location.lastPhysical.timestamp") != nil)
        manager.authorizationStatus = .denied
        precondition(provider.lastLocation == nil) // before the authorization callback
        provider.locationManagerDidChangeAuthorization(manager)
        precondition(provider.lastLocation == nil && provider.locationOverride == nil)
        precondition(!manager.locationUpdates && !manager.headingUpdates)
        precondition(defaults.object(forKey: "location.lastPhysical.timestamp") == nil)
        precondition(!provider.consent.allows(oldGrant) && invalidations == 1)
        provider.locationManager(manager, didUpdateLocations: [fix])
        provider.setLocationOverride(latitude: 1, longitude: 2)
        precondition(provider.lastLocation == nil && provider.locationOverride == nil)
        print("PASS revocation clears state, invalidates queued work, and rejects late fixes/overrides")
        manager.location = nil
        manager.authorizationStatus = .authorizedWhenInUse
        provider.locationManagerDidChangeAuthorization(manager)
        precondition(provider.lastLocation == nil)
        provider.locationManager(manager, didUpdateLocations: [fix])
        precondition(provider.lastLocation != nil && !provider.consent.allows(oldGrant))
        provider.stop()
        precondition(provider.lastLocation == nil)
        precondition(defaults.object(forKey: "location.lastPhysical.timestamp") != nil)
        provider.locationManager(manager, didUpdateLocations: [fix])
        precondition(provider.lastLocation == nil)
        print("PASS regrant requires an authorized fix, old grants stay invalid, normal stop preserves restart cache")
        CLLocationManager.initialStatus = .notDetermined
        let pending = AppleLocationProvider(defaults: defaults)
        pending.start()
        precondition(pending.lastLocation == nil)
        precondition(CLLocationManager.latest!.permissionRequests == 1)
        print("PASS undetermined permission does not use a cache")
    }
}
