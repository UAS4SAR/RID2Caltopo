import Foundation

public enum ArchiveFolderDisplay {
    private static let minute: TimeInterval = 60
    private static let hour = 60 * minute
    private static let day = 24 * hour
    private static let month = 30 * day
    private static let year = 365 * day

    public static func age(_ ageSeconds: TimeInterval) -> String {
        let age = max(0, ageSeconds)
        if age < minute { return "<1 minute" }
        if age < hour { return unit(Int(age / minute), singular: "minute") }
        if age < day { return unit(Int(age / hour), singular: "hour") }
        if age < month { return unit(Int(age / day), singular: "day") }
        if age < year { return unit(Int(age / month), singular: "month") }
        return unit(Int(age / year), singular: "year")
    }

    /// Row detail for the flight-folder list. Same text as Android
    /// `archiveCleanupDetail` (ArchiveCleanup.kt).
    public static func detail(age: String, size: String, protectionReason: String?, unuploadedClueCount: Int) -> String {
        var text = "Age \(age) • \(size)"
        if let protectionReason { text += " • protected: \(protectionReason)" }
        if unuploadedClueCount > 0 {
            text += " • \(unuploadedClueCount) clue\(unuploadedClueCount == 1 ? "" : "s") not uploaded"
        }
        return text
    }

    /// Delete confirmation, warning when clues that never reached CalTopo would be lost.
    public static func deleteConfirmation(folderCount: Int, sizeLabel: String, unuploadedClueCount: Int) -> String {
        var text = "Permanently delete \(folderCount) archive folder\(folderCount == 1 ? "" : "s") totaling \(sizeLabel)?"
        if unuploadedClueCount > 0 {
            text += " \(unuploadedClueCount) clue\(unuploadedClueCount == 1 ? " was" : "s were") never uploaded to CalTopo and will be lost."
        }
        return text
    }

    public static func size(_ bytes: Int64) -> String {
        let safeBytes = max(0, bytes)
        if safeBytes < 1_024 { return "\(safeBytes) B" }
        let units = ["KB", "MB", "GB", "TB"]
        var value = Double(safeBytes) / 1_024
        var unitIndex = 0
        while value >= 1_024, unitIndex < units.count - 1 {
            value /= 1_024
            unitIndex += 1
        }
        if value >= 10 {
            return String(format: "%.0f %@", locale: Locale(identifier: "en_US_POSIX"), value, units[unitIndex])
        }
        return String(format: "%.1f %@", locale: Locale(identifier: "en_US_POSIX"), value, units[unitIndex])
    }

    private static func unit(_ count: Int, singular: String) -> String {
        "\(count) \(singular)\(count == 1 ? "" : "s")"
    }
}
