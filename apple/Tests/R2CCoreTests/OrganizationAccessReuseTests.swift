import Testing
import Foundation
@testable import R2CCore

// Mirrors Android OrganizationAccessPolicyTest: only a session ended by a device lock may be
// re-unlocked by the device unlock; fresh or never-unlocked sessions always prompt in the app.
@Test func biometricUnlockReuseOnlyAfterDeviceLockEndedAGrantedSession() {
    #expect(OrganizationAccessPolicy.biometricUnlockReuseSeconds(accessRevokedByDeviceLock: true) == 300)
    #expect(OrganizationAccessPolicy.biometricUnlockReuseSeconds(accessRevokedByDeviceLock: false) == 0)
    // Never longer than iOS allows (LATouchIDAuthenticationMaximumAllowableReuseDuration = 300 s).
    #expect(OrganizationAccessPolicy.maximumBiometricUnlockReuseSeconds == 300)
}
