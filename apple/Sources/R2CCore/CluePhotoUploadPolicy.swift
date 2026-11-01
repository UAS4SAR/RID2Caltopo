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

    public static let existingBackendMediaToken = "Cannot update existing backendMedia"
    /// Thrown/returned when media create/data state cannot be verified; caller must mint a new media id.
    public static let ambiguousMediaStateToken = "media state ambiguous"
    public static let needsAttentionMessage = "Needs attention"
    /// Terminal local failure when the JPEG cannot be recovered.
    public static let photoMissingMessage = "Photo missing"
    /// Appended to the CalTopo marker description when uploading a marker without its photo.
    public static let photoLostDescriptionNote =
        "Photo was lost on this device before upload; marker only."
    /// Identical permanent 4xx fingerprints before auto-retry stops (still saved; woken on reconnect).
    public static let permanentFailureLimit = 5

    /// True when auto-upload must stop and wait for an explicit operator action.
    public static func stopsAutoRetry(_ lastUploadError: String?) -> Bool {
        let text = (lastUploadError ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return false }
        if text.localizedCaseInsensitiveContains(needsAttentionMessage) { return true }
        if text.localizedCaseInsensitiveContains(photoMissingMessage) { return true }
        if text.caseInsensitiveCompare("missing-photo") == .orderedSame { return true }
        if text.caseInsensitiveCompare("Missing photo") == .orderedSame { return true }
        return false
    }

    public static func descriptionNotesLostPhoto(_ description: String?) -> Bool {
        let text = description ?? ""
        return text.localizedCaseInsensitiveContains(photoLostDescriptionNote)
    }

    public static func descriptionWithLostPhotoNote(_ description: String?) -> String {
        let base = (description ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if descriptionNotesLostPhoto(base) { return base }
        if base.isEmpty { return photoLostDescriptionNote }
        return "\(base)\n\n\(photoLostDescriptionNote)"
    }

    /// Seconds for the media `/data` HTTP call from JPEG byte length.
    public static func photoUploadTimeoutSeconds(jpegBytes: Int) -> TimeInterval {
        let bytes = max(0, jpegBytes)
        let megabytes = Double(bytes) / (1024.0 * 1024.0)
        let scaled = photoTimeoutBaseSeconds + ceil(megabytes) * photoTimeoutPerMegabyteSeconds
        return min(photoTimeoutCapSeconds, max(photoTimeoutBaseSeconds, scaled))
    }

    /// Multi-step publish budget measured from when the first clue request is sent.
    public static func attemptBudgetSeconds(jpegBytes: Int) -> TimeInterval {
        min(
            photoTimeoutCapSeconds + attemptBudgetOverheadSeconds,
            photoUploadTimeoutSeconds(jpegBytes: jpegBytes) + attemptBudgetOverheadSeconds
        )
    }

    /// Overall wall-clock from attempt start; send begins immediately (no folder wait).
    public static func submitDeadlineSeconds(jpegBytes: Int) -> TimeInterval {
        attemptBudgetSeconds(jpegBytes: jpegBytes)
    }

    /// CalTopo rejects re-POSTing an id that already has backend media (common after client timeout).
    public static func isExistingBackendMediaError(statusCode: Int, message: String?) -> Bool {
        guard statusCode == 400 else { return false }
        let text = message ?? ""
        return text.range(of: existingBackendMediaToken, options: .caseInsensitive) != nil
    }

    /// Media create/data failed in a way that must not reuse the same media id.
    public static func isAmbiguousMediaState(statusCode: Int, message: String?) -> Bool {
        let text = message ?? ""
        if text.range(of: ambiguousMediaStateToken, options: .caseInsensitive) != nil { return true }
        if statusCode == 409 && text.range(of: existingBackendMediaToken, options: .caseInsensitive) != nil {
            return true
        }
        return false
    }

    /// Deterministic client errors that will not succeed by blind retry of the same payload.
    public static func isPermanentClientFailure(statusCode: Int) -> Bool {
        switch statusCode {
        case 400, 401, 403, 404, 409, 410, 413, 422: return true
        default: return false
        }
    }

    /// Fingerprint for consecutive identical permanent failures (status + brief body token).
    public static func permanentFailureFingerprint(statusCode: Int, message: String?) -> String {
        let plain = plainUploadError(message)
        return "\(statusCode):\(plain)"
    }

    /// Plain-language last-error text for the pending-clues sheet.
    public static func plainUploadError(_ raw: String?) -> String {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return "Waiting to upload" }
        if text.localizedCaseInsensitiveContains(needsAttentionMessage) {
            return needsAttentionMessage
        }
        if text.localizedCaseInsensitiveContains(photoMissingMessage)
            || text.caseInsensitiveCompare("missing-photo") == .orderedSame
            || text.caseInsensitiveCompare("Missing photo") == .orderedSame {
            return photoMissingMessage
        }
        if text.range(of: ambiguousMediaStateToken, options: .caseInsensitive) != nil
            || text.range(of: existingBackendMediaToken, options: .caseInsensitive) != nil {
            return "Photo upload incomplete; replacing photo id"
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

    /// Marker description time: local with zone abbreviation plus UTC in parentheses.
    public static func formatClueMarkerTime(_ date: Date, timeZone: TimeZone = .current) -> String {
        let local = DateFormatter()
        local.locale = Locale(identifier: "en_US_POSIX")
        local.timeZone = timeZone
        local.dateFormat = "h:mm a zzz"
        let utc = DateFormatter()
        utc.locale = Locale(identifier: "en_US_POSIX")
        utc.timeZone = TimeZone(secondsFromGMT: 0)
        utc.dateFormat = "HH:mm 'UTC'"
        return "\(local.string(from: date)) (\(utc.string(from: date)))"
    }
}
