import Foundation

public enum CaltopoArtifactSyncPolicy {
    /// Delta feeds may omit deletions; reconcile visible maps within two minutes.
    public static func fullReconciliationDue(now: Date, lastFullSync: Date?, foreground: Bool) -> Bool {
        guard let lastFullSync else { return true }
        let age = now.timeIntervalSince(lastFullSync)
        return age < 0 || age >= (foreground ? 120 : 15 * 60)
    }
}
