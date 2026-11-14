import Foundation
import CoreLocation

// Keep the actual provider source unchanged. These platform adapters simulate
// permission callbacks and cached fixes without modifying any device's permissions.
enum CLAuthorizationStatus { case notDetermined, restricted, denied, authorizedAlways, authorizedWhenInUse }
enum CLAccuracyAuthorization { case fullAccuracy, reducedAccuracy }
protocol CLLocationManagerDelegate: AnyObject {}
@MainActor final class CLLocationManager: NSObject {
    static var initialStatus: CLAuthorizationStatus = .notDetermined
    static var cachedLocation: CLLocation?
    static var latest: CLLocationManager?
    nonisolated(unsafe) static var servicesEnabled = true
    static var initialAccuracy: CLAccuracyAuthorization = .fullAccuracy
    var accuracyAuthorization: CLAccuracyAuthorization = CLLocationManager.initialAccuracy
    var fullAccuracyRequests: [String] = []
    func requestTemporaryFullAccuracyAuthorization(withPurposeKey purposeKey: String, completion: ((Error?) -> Void)? = nil) {
        fullAccuracyRequests.append(purposeKey)
        completion?(nil)
    }
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
    nonisolated static func locationServicesEnabled() -> Bool { servicesEnabled }
    nonisolated static func headingAvailable() -> Bool { true }
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
