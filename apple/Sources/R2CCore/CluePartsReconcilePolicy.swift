import Foundation

/// After marker + media + link are confirmed, published clues stay reconciled until Retry
/// or an ambiguous demotion. Auto re-verify runs at most once per app start / map connect
/// for clues still pending confirmation — never for already-reconciled rows.
public enum CluePartsReconcilePolicy: Sendable {
    public static func needsAutoReverify(uploadState: OperationalClueUploadState, partsReconciled: Bool) -> Bool {
        guard uploadState == .published else { return false }
        return !partsReconciled
    }

    public static func clearReconciledOnRetry(partsReconciled: Bool) -> Bool { partsReconciled }
}
