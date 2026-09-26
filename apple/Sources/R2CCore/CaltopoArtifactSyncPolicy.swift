public enum CaltopoArtifactSyncPolicy {
    /// Once initialized, automatic polls remain incremental until an operator requests a full reload.
    public static func fullRefreshRequired(lastSuccessfulCursorMilliseconds: Int64, manualReload: Bool) -> Bool {
        lastSuccessfulCursorMilliseconds == 0 || manualReload
    }
}
