import Foundation
import Testing
@testable import R2CCore

@Test func mappingLocalPreviewZoomRequiresOneShotToken() {
    #expect(!MappingLocalPreviewZoom.shouldZoom(token: nil, lastConsumed: nil, hasUsableBounds: true))
    #expect(!MappingLocalPreviewZoom.shouldConsume(token: nil, lastConsumed: nil))
    let token = UUID()
    #expect(MappingLocalPreviewZoom.shouldZoom(token: token, lastConsumed: nil, hasUsableBounds: true))
    #expect(!MappingLocalPreviewZoom.shouldZoom(token: token, lastConsumed: token, hasUsableBounds: true))
    #expect(!MappingLocalPreviewZoom.shouldZoom(token: token, lastConsumed: nil, hasUsableBounds: false))
}

@Test func deviceGpsRecenterPlansImmediateOrPendingFix() {
    let token = UUID()
    #expect(
        DeviceGpsRecenter.plan(token: token, lastConsumed: nil, hasDeviceFix: true) ==
            .init(centerNow: true, pendingUntilFix: false, consumeRequest: true)
    )
    #expect(
        DeviceGpsRecenter.plan(token: token, lastConsumed: nil, hasDeviceFix: false) ==
            .init(centerNow: false, pendingUntilFix: true, consumeRequest: true)
    )
    #expect(
        DeviceGpsRecenter.plan(token: token, lastConsumed: token, hasDeviceFix: true) ==
            .init(centerNow: false, pendingUntilFix: false, consumeRequest: false)
    )
    #expect(!DeviceGpsRecenter.shouldApplyPending(pending: true, hasDeviceFix: false))
    #expect(DeviceGpsRecenter.shouldApplyPending(pending: true, hasDeviceFix: true))
}
