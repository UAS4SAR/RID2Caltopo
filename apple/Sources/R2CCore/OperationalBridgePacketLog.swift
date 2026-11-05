import Foundation

/// DroneScout bridge packet log — mirrors Android OpenDroneIdDataManager:
/// per-packet detail at Ludicrous; compact DEBUG summary every 5s via LogThrottle.
public enum OperationalBridgePacketLog {
    public typealias Writer = @Sendable (_ level: String, _ category: String, _ message: String) -> Void

    /// Injected by the app target (AppleLog). Safe to call off MainActor.
    nonisolated(unsafe) public static var write: Writer?

    /// When false (default), per-packet detail is suppressed — same as Android below Ludicrous.
    nonisolated(unsafe) public static var ludicrousEnabled: Bool = false

    private static let lock = NSLock()
    // Protected by `lock`; brief critical sections only.
    nonisolated(unsafe) private static var eventCount: UInt64 = 0

    /// Note one confirmed bridge packet. Call from a worker; never blocks on disk.
    public static func note(
        classification: String,
        transmitterID: String,
        messageCounter: UInt8,
        aircraftID: String,
        bridgeRssiDbm: Int
    ) {
        lock.lock()
        eventCount &+= 1
        let count = eventCount
        lock.unlock()
        let detail =
            "Bridge event=\(count) classification=\(classification) " +
            "transmitter=\(transmitterID) messageCounter=\(messageCounter) " +
            "aircraft=\(aircraftID) bridgeRssiDbm=\(bridgeRssiDbm)"
        if ludicrousEnabled {
            write?("LUDICROUS", "DroneScoutBridge", detail)
        }
    }

    public static func resetForTest() {
        lock.lock()
        eventCount = 0
        lock.unlock()
        write = nil
        ludicrousEnabled = false
        LogThrottle.resetForTest()
    }
}
