import Foundation

/// Compact headings for the always-visible operational status strip.
/// Detailed state text remains available in the panel opened from each chip.
public enum OperationalStatusChipText {
    public static func airspace(
        severity: OperationalAirspaceSeverity,
        detailedLabel: String
    ) -> String {
        if ["updating", "unavailable", "stale", "pending"].contains(where: { detailedLabel.lowercased().contains($0) }) { return detailedLabel }
        return switch severity {
        case .danger: "Authorization required"
        case .caution: "Airspace nearby"
        case .normal: detailedLabel
        case .neutral: conciseNeutral(detailedLabel, fallback: "Airspace status")
        }
    }

    public static func notam(
        severity: OperationalNotamSeverity,
        detailedLabel: String
    ) -> String {
        if ["updating", "unavailable", "stale", "pending"].contains(where: { detailedLabel.lowercased().contains($0) }) { return detailedLabel }
        return switch severity {
        case .danger: "NOTAM warning"
        case .caution: "NOTAMs nearby"
        case .normal: detailedLabel
        case .neutral: conciseNeutral(detailedLabel, fallback: "NOTAM status")
        }
    }

    public static func land(
        severity: OperationalLandSeverity,
        detailedLabel: String
    ) -> String {
        switch severity {
        case .danger: "Land restricted"
        case .caution: "Land rules nearby"
        case .normal: "Land rules clear"
        case .neutral: conciseNeutral(detailedLabel, fallback: "Land status")
        }
    }

    private static func conciseNeutral(_ detailedLabel: String, fallback: String) -> String {
        let trimmed = detailedLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.count <= 24 ? trimmed : fallback
    }
}
