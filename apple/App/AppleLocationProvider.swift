import CoreLocation
import Foundation
import Combine

/// A bounded in-memory gate shared with background publishers. An old grant cannot
/// authorize queued work after permission is withdrawn and granted again.
final class AppleLocationConsent: @unchecked Sendable {
    private let lock = NSLock()
    private var authorized = false
    private var generation: UInt = 0

    func update(authorized value: Bool) {
        lock.lock(); defer { lock.unlock() }
        if authorized != value { generation &+= 1 }
        authorized = value
    }

    var currentGeneration: UInt? {
        lock.lock(); defer { lock.unlock() }
        return authorized ? generation : nil
    }

    func allows(_ value: UInt) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return authorized && generation == value
    }
}

@MainActor
enum AppleMagneticNorth {
    static var declinationDegrees: Double?

    static func update(from heading: CLHeading) {
        guard heading.headingAccuracy >= 0,
              heading.trueHeading >= 0,
              heading.magneticHeading >= 0
        else { return }
        var value = heading.trueHeading - heading.magneticHeading
        while value <= -180 { value += 360 }
        while value > 180 { value -= 360 }
        declinationDegrees = value
    }
}

private actor LocationServiceAvailability {
    struct Capabilities: Sendable { let enabled: Bool; let heading: Bool }
    func capabilities() throws -> Capabilities {
        try Task.checkCancellation()
        // These process-wide queries can wait on the location service's XPC reply.
        return .init(enabled: CLLocationManager.locationServicesEnabled(), heading: CLLocationManager.headingAvailable())
    }
}

@MainActor
final class AppleLocationProvider: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published private(set) var authorizationStatus: CLAuthorizationStatus
    @Published private var publishedLocation: CLLocation?
    let consent = AppleLocationConsent()
    var locationUnavailableHandler: (() -> Void)?

    /// Recheck the manager's current status at consumption, including before its
    /// authorization-change callback has reached downstream observers.
    var lastLocation: CLLocation? {
        guard started, hasLocationAuthorization else { return nil }
        return publishedLocation
    }

    private var hasLocationAuthorization: Bool {
        let status = manager?.authorizationStatus ?? authorizationStatus
        return status == .authorizedAlways || status == .authorizedWhenInUse
    }
    @Published private(set) var locationOverride: CLLocation?
    @Published private(set) var errorMessage: String?

    private var manager: CLLocationManager?
    private var physicalLocation: CLLocation?
    private var physicalLocationIsProvisional = false
    private var started = false
    private var startGeneration = 0
    private var startTask: Task<Void, Never>?
    private let availability = LocationServiceAvailability()
    private var headingAvailable = false
    /// True from session start until full cleanup, mirroring Android's
    /// ScanningService foreground-service lifetime. While true, standard location
    /// updates continue after the display locks, which keeps RID scanning, video
    /// ingest, and spoken advisories running. Not tied to an incident map.
    private var backgroundOperationEnabled = false

    // A Wi-Fi-only iPad can temporarily have no live Core Location fix even
    // though Location Services remain authorized. Prefer the device's recent
    // last-known position to an application-wide map default while a fresh fix
    // is requested. The status text continues to make the fallback explicit.
    private let maximumProvisionalLocationAge: TimeInterval = 2 * 60 * 60
    private let defaults: UserDefaults

    private enum PersistedLocationKey {
        static let latitude = "location.lastPhysical.latitude"
        static let longitude = "location.lastPhysical.longitude"
        static let altitude = "location.lastPhysical.altitude"
        static let horizontalAccuracy = "location.lastPhysical.horizontalAccuracy"
        static let verticalAccuracy = "location.lastPhysical.verticalAccuracy"
        static let timestamp = "location.lastPhysical.timestamp"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        authorizationStatus = .notDetermined
        super.init()
    }

    private func makeManager() -> CLLocationManager {
        if let manager { return manager }
        let manager = CLLocationManager()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
        manager.activityType = .otherNavigation
        self.manager = manager
        applyBackgroundMonitoringConfiguration(to: manager)
        return manager
    }

    func start() {
        guard !started else { return }
        started = true
        backgroundOperationEnabled = true
        if let manager { applyBackgroundMonitoringConfiguration(to: manager) }
        AppleLog.info("Location", "Background operation enabled for this session")
        startGeneration &+= 1
        let generation = startGeneration, availability = self.availability
        startTask = Task { [weak self] in
            guard let capabilities = try? await availability.capabilities(),
                  let self, !Task.isCancelled, self.started, self.startGeneration == generation else { return }
            self.startTask = nil
            self.headingAvailable = capabilities.heading
            guard capabilities.enabled else {
                self.clearLocationUse()
                self.errorMessage = "Location Services are disabled"
                AppleLog.error("Location", "Location Services are disabled")
                return
            }
            let manager = self.makeManager()
            self.applyAuthorizationStatus(manager.authorizationStatus)
        }
    }

    func stop() {
        startGeneration &+= 1
        startTask?.cancel(); startTask = nil
        manager?.stopUpdatingLocation()
        manager?.stopUpdatingHeading()
        started = false
        clearLocationUse(removePersistedFix: false)
        backgroundOperationEnabled = false
        if let manager { applyBackgroundMonitoringConfiguration(to: manager) }
        AppleLog.info("Location", "Background operation disabled")
    }

    /// Whether this session will keep running after the display locks. Requires
    /// location authorization; without it iOS suspends the app in background.
    var backgroundOperationAvailable: Bool {
        started && backgroundOperationEnabled
            && (authorizationStatus == .authorizedWhenInUse || authorizationStatus == .authorizedAlways)
    }

    func requestPermission() {
        if !started { start() }
        else if startTask == nil { applyAuthorizationStatus(makeManager().authorizationStatus) }
    }

    func setLocationOverride(latitude: Double, longitude: Double) {
        guard started, hasLocationAuthorization,
              CLLocationCoordinate2DIsValid(.init(latitude: latitude, longitude: longitude)) else { return }
        let location = CLLocation(
            coordinate: .init(latitude: latitude, longitude: longitude),
            altitude: physicalLocation?.altitude ?? 0,
            horizontalAccuracy: 1,
            verticalAccuracy: physicalLocation?.verticalAccuracy ?? -1,
            timestamp: Date()
        )
        locationOverride = location
        publishedLocation = location
        errorMessage = nil
        AppleLog.info(
            "Location",
            "Developer override enabled latitude=\(String(format: "%.6f", latitude)) longitude=\(String(format: "%.6f", longitude))"
        )
    }

    func clearLocationOverride() {
        guard locationOverride != nil else { return }
        locationOverride = nil
        publishedLocation = hasLocationAuthorization ? physicalLocation : nil
        if physicalLocationIsProvisional, physicalLocation != nil {
            errorMessage = "Using last known location; current fix unavailable"
        }
        AppleLog.info("Location", "Developer override cleared")
    }

    var statusText: String {
        if let errorMessage { return errorMessage }
        switch authorizationStatus {
        case .notDetermined: return "Permission needed"
        case .restricted: return "Restricted"
        case .denied: return "Denied"
        case .authorizedAlways, .authorizedWhenInUse:
            guard let lastLocation else { return "Locating…" }
            return "±\(Int(lastLocation.horizontalAccuracy.rounded())) m"
        @unknown default: return "Unknown"
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        applyAuthorizationStatus(manager.authorizationStatus)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard started, hasLocationAuthorization,
              let location = locations.last(where: { $0.horizontalAccuracy >= 0 }) else { return }
        let age = max(0, -location.timestamp.timeIntervalSinceNow)
        guard age < 30 else {
            publishProvisionalLocationIfUsable(location, source: "location update cache")
            return
        }
        publishPhysicalLocation(location, provisional: false, source: "fresh fix")
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard started, hasLocationAuthorization else { return }
        AppleMagneticNorth.update(from: newHeading)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let locationError = error as? CLError, locationError.code == .locationUnknown {
            return
        }
        if physicalLocationIsProvisional, physicalLocation != nil {
            errorMessage = "Using last known location; current fix unavailable"
        } else {
            errorMessage = error.localizedDescription
        }
        AppleLog.error("Location", "Update failed: \(error.localizedDescription)")
    }

    private func applyAuthorizationStatus(_ status: CLAuthorizationStatus) {
        authorizationStatus = status
        consent.update(authorized: started && hasLocationAuthorization)
        AppleLog.info("Location", "Authorization: \(authorizationDescription(status))")
        if !hasLocationAuthorization { clearLocationUse() }
        switch status {
        case .notDetermined:
            if started { manager?.requestWhenInUseAuthorization() }
        case .authorizedAlways, .authorizedWhenInUse:
            errorMessage = nil
            if started {
                publishProvisionalLocationIfUsable(manager?.location, source: "Core Location cache")
                publishProvisionalLocationIfUsable(persistedPhysicalLocation(), source: "persisted device fix")
                manager?.startUpdatingLocation()
                if headingAvailable {
                    manager?.startUpdatingHeading()
                }
            }
        case .denied:
            manager?.stopUpdatingLocation()
            errorMessage = "Location permission denied"
        case .restricted:
            manager?.stopUpdatingLocation()
            errorMessage = "Location permission restricted"
        @unknown default:
            manager?.stopUpdatingLocation()
            errorMessage = "Unknown location authorization state"
        }
    }

    private func clearLocationUse(removePersistedFix: Bool = true) {
        consent.update(authorized: false)
        manager?.stopUpdatingLocation()
        manager?.stopUpdatingHeading()
        physicalLocation = nil
        physicalLocationIsProvisional = false
        locationOverride = nil
        publishedLocation = nil
        AppleMagneticNorth.declinationDegrees = nil
        for key in [PersistedLocationKey.latitude, PersistedLocationKey.longitude,
                    PersistedLocationKey.altitude, PersistedLocationKey.horizontalAccuracy,
                    PersistedLocationKey.verticalAccuracy, PersistedLocationKey.timestamp] {
            if removePersistedFix { defaults.removeObject(forKey: key) }
        }
        locationUnavailableHandler?()
    }

    private func applyBackgroundMonitoringConfiguration(to manager: CLLocationManager) {
        manager.allowsBackgroundLocationUpdates = backgroundOperationEnabled
        manager.showsBackgroundLocationIndicator = backgroundOperationEnabled
        manager.pausesLocationUpdatesAutomatically = !backgroundOperationEnabled
    }

    private func publishProvisionalLocationIfUsable(_ location: CLLocation?, source: String) {
        guard started, hasLocationAuthorization, physicalLocation == nil,
              let location,
              location.horizontalAccuracy >= 0
        else { return }
        let age = max(0, -location.timestamp.timeIntervalSinceNow)
        guard age <= maximumProvisionalLocationAge else {
            AppleLog.info(
                "Location",
                "Ignored stale \(source) ageSeconds=\(Int(age.rounded()))"
            )
            return
        }
        publishPhysicalLocation(location, provisional: true, source: source)
    }

    private func publishPhysicalLocation(
        _ location: CLLocation,
        provisional: Bool,
        source: String
    ) {
        guard started, hasLocationAuthorization else { return }
        physicalLocation = location
        physicalLocationIsProvisional = provisional
        if !provisional {
            persistPhysicalLocation(location)
        }
        if locationOverride == nil {
            publishedLocation = location
        }
        errorMessage = provisional
            ? "Using last known location while locating…"
            : nil
        let age = max(0, -location.timestamp.timeIntervalSinceNow)
        AppleLog.info(
            "Location",
            "Published \(source) latitude=\(String(format: "%.6f", location.coordinate.latitude)) " +
                "longitude=\(String(format: "%.6f", location.coordinate.longitude)) " +
                "accuracyMeters=\(Int(location.horizontalAccuracy.rounded())) " +
                "ageSeconds=\(Int(age.rounded())) provisional=\(provisional)"
        )
    }

    private func persistPhysicalLocation(_ location: CLLocation) {
        defaults.set(location.coordinate.latitude, forKey: PersistedLocationKey.latitude)
        defaults.set(location.coordinate.longitude, forKey: PersistedLocationKey.longitude)
        defaults.set(location.altitude, forKey: PersistedLocationKey.altitude)
        defaults.set(location.horizontalAccuracy, forKey: PersistedLocationKey.horizontalAccuracy)
        defaults.set(location.verticalAccuracy, forKey: PersistedLocationKey.verticalAccuracy)
        defaults.set(location.timestamp.timeIntervalSince1970, forKey: PersistedLocationKey.timestamp)
    }

    private func persistedPhysicalLocation() -> CLLocation? {
        guard defaults.object(forKey: PersistedLocationKey.timestamp) != nil else { return nil }
        let coordinate = CLLocationCoordinate2D(
            latitude: defaults.double(forKey: PersistedLocationKey.latitude),
            longitude: defaults.double(forKey: PersistedLocationKey.longitude)
        )
        guard CLLocationCoordinate2DIsValid(coordinate) else { return nil }
        return CLLocation(
            coordinate: coordinate,
            altitude: defaults.double(forKey: PersistedLocationKey.altitude),
            horizontalAccuracy: defaults.double(forKey: PersistedLocationKey.horizontalAccuracy),
            verticalAccuracy: defaults.double(forKey: PersistedLocationKey.verticalAccuracy),
            timestamp: Date(timeIntervalSince1970: defaults.double(forKey: PersistedLocationKey.timestamp))
        )
    }

    /// For lifecycle and background-heartbeat log lines.
    var backgroundStatusDescription: String {
        "locationAuth=\(authorizationDescription(authorizationStatus).replacingOccurrences(of: " ", with: "")) "
            + "backgroundLocation=\(backgroundOperationAvailable ? "active" : "unavailable")"
    }

    private func authorizationDescription(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: "not determined"
        case .restricted: "restricted"
        case .denied: "denied"
        case .authorizedAlways: "always"
        case .authorizedWhenInUse: "when in use"
        @unknown default: "unknown"
        }
    }
}
