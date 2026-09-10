import Foundation
import CoreImage
import Vision
import Testing
@testable import R2CCore

@Test func trackerQRSVGParsesRectangularModules() throws {
    let data = Data(
        ##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 12 12"><path d="M1,1H2V2H1zM4,5H6V7H4z" fill="#000000"/></svg>"##.utf8
    )

    let document = try TrackerQRSVG.parse(data)

    #expect(document.width == 12)
    #expect(document.height == 12)
    #expect(document.rectangles == [
        TrackerQRSVGRectangle(x: 1, y: 1, width: 1, height: 1),
        TrackerQRSVGRectangle(x: 4, y: 5, width: 2, height: 2),
    ])
}

@Test func trackerQRSVGRejectsGeneralSVGPaths() {
    let data = Data(
        #"<svg viewBox="0 0 10 10"><path d="M1,1L9,9z"/></svg>"#.utf8
    )

    #expect(throws: TrackerQRSVGError.unsupportedPath) {
        try TrackerQRSVG.parse(data)
    }
}

@Test func trackerQRSVGRasterizesForVisionQRCodeRecognition() throws {
    let payload =
        "r2cenroll://open?url=https%3A%2F%2Fr2c-tracker.com%2Fncssar%2Fenroll%3Ftoken%3Dsvg-token"
    let filter = CIFilter(name: "CIQRCodeGenerator")!
    filter.setValue(Data(payload.utf8), forKey: "inputMessage")
    filter.setValue("M", forKey: "inputCorrectionLevel")
    let qrImage = try #require(filter.outputImage)
    let extent = qrImage.extent.integral
    let width = Int(extent.width)
    let height = Int(extent.height)
    var pixels = [UInt8](repeating: 255, count: width * height * 4)
    CIContext(options: [.useSoftwareRenderer: true]).render(
        qrImage,
        toBitmap: &pixels,
        rowBytes: width * 4,
        bounds: extent,
        format: .RGBA8,
        colorSpace: CGColorSpaceCreateDeviceRGB()
    )
    var path = ""
    for y in 0..<height {
        for x in 0..<width where pixels[(y * width + x) * 4] < 128 {
            path += "M\(x),\(y)H\(x + 1)V\(y + 1)H\(x)z"
        }
    }
    let svg = Data(
        """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 \(width) \(height)">
        <path d="\(path)" fill="#000000"/>
        </svg>
        """.utf8
    )

    let request = VNDetectBarcodesRequest()
    request.symbologies = [.qr]
    try VNImageRequestHandler(cgImage: TrackerQRSVG.rasterize(svg)).perform([request])

    #expect(request.results?.compactMap(\.payloadStringValue).first == payload)
}
