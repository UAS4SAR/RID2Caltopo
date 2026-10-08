import Testing
import Foundation
@testable import R2CCore

@Test func downloadAreaSummaryEquatorialRectangle() {
    let area = OperationalDownloadAreaSummary(bounds: .init(north: 0.5, south: -0.5, west: -0.5, east: 0.5))
    #expect(abs(area.widthMiles - 69.0934) < 0.001)
    #expect(abs(area.heightMiles - 69.0934) < 0.001)
    #expect(abs(area.squareMiles - 4773.84) < 0.1)
    #expect(area.latitude == 0 && area.longitude == 0)
}
@Test func downloadAreaSummaryHighLatitudeAndZeroArea() {
    let area = OperationalDownloadAreaSummary(bounds: .init(north: 60.5, south: 59.5, west: -121, east: -120))
    #expect(abs(area.widthMiles - 34.5467) < 0.001)
    #expect(area.longitude == -120.5)
    #expect(OperationalDownloadAreaSummary(bounds: .init(north: 40, south: 40, west: -120, east: -120)).squareMiles == 0)
}
