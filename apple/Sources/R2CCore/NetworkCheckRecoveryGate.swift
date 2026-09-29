public struct NetworkCheckRecoveryGate: Sendable {
    private var available = false
    public init() {}
    public mutating func update(available: Bool) -> Bool {
        let restored = available && !self.available
        self.available = available
        return restored
    }
}
