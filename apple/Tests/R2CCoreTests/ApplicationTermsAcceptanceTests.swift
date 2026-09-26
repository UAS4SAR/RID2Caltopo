import Foundation
import Testing
@testable import R2CCore

@Test func applicationTermsPersistAndRequireAcceptanceForChanges() throws {
    let suite = "TermsTest-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    #expect(ApplicationTermsAcceptance.load(from: defaults) == nil)
    let accepted = ApplicationTermsAcceptance(acceptedAt: Date(timeIntervalSince1970: 1_790_000_000))
    try accepted.save(to: defaults)
    let restored = try #require(ApplicationTermsAcceptance.load(from: UserDefaults(suiteName: suite)!))
    #expect(restored == accepted)
    #expect(restored.isCurrent())
    #expect(!restored.isCurrent(version: "future"))
    #expect(!restored.isCurrent(text: ApplicationLaunchDisclaimer.text + " Changed."))
    #expect(restored.logMessage("TermsAccepted").contains("acceptedAt="))
    #expect(restored.logMessage("TermsAcceptanceRestored").hasPrefix("TermsAcceptanceRestored "))
    // Reading the record or terms is not a new acceptance.
    #expect(ApplicationTermsAcceptance.load(from: defaults)?.acceptedAt == accepted.acceptedAt)
    defaults.set(Data("damaged".utf8), forKey: "applicationTerms.acceptance")
    #expect(ApplicationTermsAcceptance.load(from: defaults) == nil)
}

@Test func applicationTermsRejectInvalidTimestampAndUseSha256() {
    #expect(!ApplicationTermsAcceptance(acceptedAt: Date(timeIntervalSince1970: 0)).isCurrent())
    #expect(ApplicationTermsAcceptance(text: "abc").fingerprint == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
}
