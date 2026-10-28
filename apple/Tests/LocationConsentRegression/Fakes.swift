import Foundation
import CoreLocation

// Keep the actual provider source unchanged. These platform adapters simulate
// permission callbacks and cached fixes without modifying any device's permissions.
enum CLAuthorizationStatus { case notDetermined, restricted, denied, authorizedAlways, authorizedWhenInUse }
protocol CLLocationManagerDelegate: AnyObject {}
@MainActor final class CLLocationManager: NSObject {
    static var initialStatus: CLAuthorizationStatus = .notDetermined
    static var cachedLocation: CLLocation?
    static var latest: CLLocationManager?
    static var servicesEnabled = true
    weak var delegate: (any CLLocationManagerDelegate)?
    var authorizationStatus: CLAuthorizationStatus
    var location: CLLocation?
    var desiredAccuracy: CLLocationAccuracy = 0
    var distanceFilter: CLLocationDistance = 0
    var activityType: CLActivityType = .other
    var allowsBackgroundLocationUpdates = false
    var showsBackgroundLocationIndicator = false
    var pausesLocationUpdatesAutomatically = false
    var locationUpdates = false
    var headingUpdates = false
    var permissionRequests = 0
    override init() {
        authorizationStatus = Self.initialStatus
        location = Self.cachedLocation
        super.init()
        Self.latest = self
    }
    static func locationServicesEnabled() -> Bool { servicesEnabled }
    static func headingAvailable() -> Bool { true }
    func requestWhenInUseAuthorization() { permissionRequests += 1 }
    func startUpdatingLocation() { locationUpdates = true }
    func stopUpdatingLocation() { locationUpdates = false }
    func startUpdatingHeading() { headingUpdates = true }
    func stopUpdatingHeading() { headingUpdates = false }
}
enum AppleLog {
    static func info(_ tag: String, _ text: String) {}
    static func error(_ tag: String, _ text: String) {}
}
