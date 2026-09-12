import Testing
@testable import R2CCore

@Test func unifiedMapBudgetIncludesTerrainAndConcurrentDownloads() {
    let used: Int64 = 300_000_000 + 400_000_000 + 50_000_000 + 10_000_000
    #expect(OperationalMapCacheBudget.fits(used: used, reserved: 100_000_000, incoming: 140_000_000, limit: 1_000_000_000))
    #expect(!OperationalMapCacheBudget.fits(used: used, reserved: 100_000_000, incoming: 140_000_001, limit: 1_000_000_000))
    #expect(!OperationalMapCacheBudget.fits(used: used, reserved: 100_000_000, incoming: 400_000_000, limit: 1_000_000_000))
}

@Test func unifiedMapBudgetRejectsOverflowAndOverLimitState() {
    #expect(!OperationalMapCacheBudget.fits(used: Int64.max - 4, reserved: 3, incoming: 2, limit: .max))
    #expect(!OperationalMapCacheBudget.fits(used: 1100, reserved: 0, incoming: 0, limit: 1000))
}

@Test func terrainAloneCanExceedCombinedOfflineBudget() {
    let capacity = OperationalOfflineCapacity(currentTileCacheBytes: 100_000_000, currentDEMCacheBytes: 400_000_000,
        estimatedTileBytes: 0, estimatedDEMBytes: 1_050_000_000, maximumTileCacheBytes: 1_000_000_000, availableVolumeBytes: 10_000_000_000)
    #expect(capacity.exceedsCacheLimit)
    #expect(capacity.recommendedMaximumBytes == 2_000_000_000)
}

@Test func unifiedMapDefaultKeepsTwentyPercentFreeAndCapsAtTenGB() {
    #expect(OperationalMapCacheBudget.defaultLimit(free: 100_000_000_000) == 10_000_000_000)
    #expect(OperationalMapCacheBudget.defaultLimit(free: 5_000_000_000) == 4_000_000_000)
    #expect(OperationalMapCacheBudget.defaultLimit(free: 0) == 0)
    #expect(OperationalMapCacheBudget.defaultLimit(free: -1) == 0)
    #expect(OperationalMapCacheBudget.defaultLimit(free: .max) == 10_000_000_000)
}
