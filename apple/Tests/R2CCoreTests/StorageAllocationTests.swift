import Testing
@testable import R2CCore

struct StorageAllocationTests {
    @Test func unusedAllowancesAreCountedOnce() {
        let value = StorageAllocation(freeBytes: 100_000_000_000, cacheUsed: 3_000_000_000, cacheMaximum: 10_000_000_000, flightUsed: 8_000_000_000, flightMaximum: 20_000_000_000)
        #expect(value.remainingCache == 7_000_000_000)
        #expect(value.remainingFlight == 12_000_000_000)
        #expect(value.freeAfterAllowances == 81_000_000_000)
    }
    @Test func exceededAndUnavailableSpaceStayExplicit() {
        #expect(StorageAllocation(freeBytes: 5, cacheUsed: 2, cacheMaximum: 10, flightUsed: 5, flightMaximum: 7).freeAfterAllowances == -5)
        #expect(StorageAllocation(freeBytes: 5, cacheUsed: 20, cacheMaximum: 10, flightUsed: 9, flightMaximum: 7).freeAfterAllowances == 5)
        #expect(StorageAllocation(freeBytes: nil, cacheUsed: 2, cacheMaximum: 10, flightUsed: 5, flightMaximum: 7).freeAfterAllowances == nil)
    }
}
