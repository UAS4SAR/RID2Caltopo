import Foundation
import Testing
@testable import R2CCore

@Test func visibleMapsReconcileMissingDeletionsWithinTwoMinutes() {
    let last = Date(timeIntervalSince1970: 1000)
    func due(_ age: Double, foreground: Bool = true) -> Bool {
        CaltopoArtifactSyncPolicy.fullReconciliationDue(now: last.addingTimeInterval(age),
            lastFullSync: last, foreground: foreground)
    }
    #expect(!due(119.999))
    #expect(due(120))
    #expect(!due(120, foreground: false))
    #expect(due(900, foreground: false))
    #expect(due(-1))
    #expect(CaltopoArtifactSyncPolicy.fullReconciliationDue(now: last, lastFullSync: nil, foreground: true))
}
