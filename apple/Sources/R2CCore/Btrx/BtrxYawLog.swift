import Foundation

/// Machine-readable gyro-yaw and SEI yaw lines for the session / BTRX log
/// (firmware 0x010C STEER format 2, PROTOCOL.md "Gyro yaw" and "Drift test").
/// Nothing here is sent to CalTopo. Same text as Android `BtrxYawLog`.
public enum BtrxYawLog {
    /// Minimum gap between `Btrx sei_yaw` lines (10 Hz is enough for interpolation).
    public static let seiLogIntervalMs: Int64 = 100

    public struct SeiSample: Sendable, Equatable {
        public let tMs: Int64, yawTrueDeg: Double, stream: String
        public init(tMs: Int64, yawTrueDeg: Double, stream: String = "") {
            self.tMs = tMs; self.yawTrueDeg = yawTrueDeg; self.stream = stream
        }
    }

    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var sink: (@Sendable (String) -> Void)?
        var latestSei: SeiSample?
        var lastSeiLogMs: Int64 = Int64.min / 2
    }
    private static let state = State()

    public static var sink: (@Sendable (String) -> Void)? {
        get { state.lock.lock(); defer { state.lock.unlock() }; return state.sink }
        set { state.lock.lock(); defer { state.lock.unlock() }; state.sink = newValue }
    }

    public static var latestSei: SeiSample? {
        state.lock.lock(); defer { state.lock.unlock() }
        return state.latestSei
    }

    public static func resetForTest() {
        state.lock.lock(); defer { state.lock.unlock() }
        state.latestSei = nil
        state.lastSeiLogMs = Int64.min / 2
    }

    public static func steerFields(_ st: BtrxProtocol.Steer) -> String {
        guard let y = st.yaw else { return "" }
        let sample = y.sampleMs(st.windowEndMs).map(String.init) ?? "-"
        return String(format: "yaw_x10=%d yaw_fixed_x10=%d turns=%d bias_z_mdps=%d t_off_ms=%d sample_ms=%@ yflags=0x%02x",
                      y.yawX10, y.yawFixedX10, y.turns, y.biasZMdps, y.tOffMs, sample, y.yflags)
    }

    public static func steerLine(arrivalMs: Int64, _ st: BtrxProtocol.Steer, backlog: Bool = false) -> String? {
        guard let y = st.yaw else { return nil }
        let sample = y.sampleMs(st.windowEndMs).map(String.init) ?? "-"
        var line = String(format: "Btrx gyro_yaw arrival_ms=%lld window_end_ms=%u sample_ms=%@ yaw_x10=%d yaw_fixed_x10=%d turns=%d bias_z_mdps=%d yflags=0x%02x t_off_ms=%d backlog=%d",
                          arrivalMs, st.windowEndMs, sample, y.yawX10, y.yawFixedX10, y.turns, y.biasZMdps, y.yflags, y.tOffMs, backlog ? 1 : 0)
        if let sei = latestSei {
            line += String(format: " sei_t_ms=%lld sei_yaw_true_deg=%.3f", sei.tMs, sei.yawTrueDeg)
            if !sei.stream.isEmpty { line += " sei_stream=\(sei.stream)" }
        }
        return line
    }

    public static func seiLine(_ sample: SeiSample) -> String {
        var line = String(format: "Btrx sei_yaw t_ms=%lld yaw_true_deg=%.3f", sample.tMs, sample.yawTrueDeg)
        if !sample.stream.isEmpty { line += " stream=\(sample.stream)" }
        return line
    }

    public static func noteSei(tMs: Int64, yawTrueDeg: Double, stream: String = "") {
        guard yawTrueDeg.isFinite else { return }
        let sample = SeiSample(tMs: tMs, yawTrueDeg: yawTrueDeg, stream: stream)
        let emit: String? = {
            state.lock.lock(); defer { state.lock.unlock() }
            state.latestSei = sample
            if tMs - state.lastSeiLogMs >= seiLogIntervalMs || tMs < state.lastSeiLogMs {
                state.lastSeiLogMs = tMs
                return seiLine(sample)
            }
            return nil
        }()
        if let emit { sink?(emit) }
    }

    public static func logSteer(arrivalMs: Int64, _ st: BtrxProtocol.Steer, backlog: Bool = false) {
        if let line = steerLine(arrivalMs: arrivalMs, st, backlog: backlog) {
            sink?(line)
        }
    }
}
