import Foundation

/// Advisory estimate using the same CPU and stream-count thresholds as Android.
public enum OperationalAnomalyHeadroom {
    public static func assess(cpuFraction: Double?, thermalPressure: Int, liveStreams: Int,
                              softwareDecodedStreams: Int, anomalyEnabledStreams: Int) -> String {
        if thermalPressure >= 2 { return "hot" }
        guard let cpuFraction, cpuFraction.isFinite, liveStreams > 0 else { return "unknown" }
        if cpuFraction >= 0.85 { return "hot" }
        if cpuFraction >= 0.65 || thermalPressure >= 1 || softwareDecodedStreams >= 2
            || anomalyEnabledStreams >= 1 || liveStreams >= 3 { return "limit" }
        return "ok"
    }
}
