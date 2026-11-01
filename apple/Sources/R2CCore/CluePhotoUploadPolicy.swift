import Foundation

/// Shared clue-photo upload timeouts and CalTopo 400 classification (keep in sync with Android).
public enum CluePhotoUploadPolicy {
    /// Marker / media metadata / MapMediaObject JSON calls.
    public static let jsonTimeoutSeconds: TimeInterval = 15
    /// Base allowance for the JPEG `/data` POST before size scaling.
    public static let photoTimeoutBaseSeconds: TimeInterval = 45
    /// Extra seconds per megabyte of JPEG payload (wire is larger after base64).
    public static let photoTimeoutPerMegabyteSeconds: TimeInterval = 30
    public static let photoTimeoutCapSeconds: TimeInterval = 180
    /// Overhead for marker/media/link steps around the photo POST in one send budget.
    public static let attemptBudgetOverheadSeconds: TimeInterval = 45
    /// Extra wall-clock from Submit for folder ensure / map readiness before the first wire send.
    /// Sized for one shared full-map-read gate interval so reconnect cannot starve the overall deadline.
    public static let submitDeadlineFolderAllowanceSeconds: TimeInterval = 90

    public static let existingBackendMediaToken = "Cannot update existing backendMedia"

    /// Seconds for the media `/data` HTTP call from JPEG byte length.
    public static func photoUploadTimeoutSeconds(jpegBytes: Int) -> TimeInterval {
        let bytes = max(0, jpegBytes)
        let megabytes = Double(bytes) / (1024.0 * 1024.0)
        let scaled = photoTimeoutBaseSeconds + ceil(megabytes) * photoTimeoutPerMegabyteSeconds
        return min(photoTimeoutCapSeconds, max(photoTimeoutBaseSeconds, scaled))
    }

    /// Multi-step publish budget measured from when the first clue request is sent (not folder ensure).
    public static func attemptBudgetSeconds(jpegBytes: Int) -> TimeInterval {
        min(
            photoTimeoutCapSeconds + attemptBudgetOverheadSeconds,
            photoUploadTimeoutSeconds(jpegBytes: jpegBytes) + attemptBudgetOverheadSeconds
        )
    }

    /// Overall wall-clock from Submit / attempt start covering folder ensure + send budget.
    public static func submitDeadlineSeconds(jpegBytes: Int) -> TimeInterval {
        attemptBudgetSeconds(jpegBytes: jpegBytes) + submitDeadlineFolderAllowanceSeconds
    }

    /// CalTopo rejects re-POSTing an id that already has backend media (common after client timeout).
    public static func isExistingBackendMediaError(statusCode: Int, message: String?) -> Bool {
        guard statusCode == 400 else { return false }
        let text = message ?? ""
        return text.range(of: existingBackendMediaToken, options: .caseInsensitive) != nil
    }

    /// Plain-language last-error text for the pending-clues sheet.
    public static func plainUploadError(_ raw: String?) -> String {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return "Waiting to upload" }
        if text.range(of: existingBackendMediaToken, options: .caseInsensitive) != nil {
            return "Photo already on CalTopo; finishing link"
        }
        if text.localizedCaseInsensitiveContains("timed out")
            || text.localizedCaseInsensitiveContains("timeout") {
            return "Upload timed out; will retry"
        }
        if text.localizedCaseInsensitiveContains("unavailable") {
            return "CalTopo temporarily unavailable"
        }
        if text.localizedCaseInsensitiveContains("login")
            || text.localizedCaseInsensitiveContains("permission") {
            return "CalTopo login or permissions need attention"
        }
        if text.count > 160 { return String(text.prefix(157)) + "…" }
        return text
    }
}
