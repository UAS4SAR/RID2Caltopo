import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import R2CCore

struct ClueCrosshairStampTests {
    @Test func stampedClueJpegKeepsAnAsymmetricCaptureUpright() throws {
        let defaults = isolatedCrosshairDefaults(showCoordinates: false)
        let source = try syntheticJPEG(width: 80, height: 120, topLeftGreen: true, orientation: nil)
        let full = try #require(stampClueCrosshairJPEG(source, coordinates: nil, quality: 0.95, defaults: defaults))
        let preview = try #require(stampClueCrosshairJPEG(source, coordinates: nil, quality: 0.95, maxEdge: 60, defaults: defaults))
        try expectUprightMarker(full, label: "full")
        try expectUprightMarker(preview, label: "preview")
    }

    @Test func stampedClueJpegBakesSourceOrientationOnSaveAndPreview() throws {
        let defaults = isolatedCrosshairDefaults(showCoordinates: false)
        let source = try syntheticJPEG(
            width: 80,
            height: 120,
            topLeftGreen: false,
            orientation: Int(CGImagePropertyOrientation.down.rawValue)
        )
        let full = try #require(stampClueCrosshairJPEG(source, coordinates: nil, quality: 0.95, defaults: defaults))
        let preview = try #require(stampClueCrosshairJPEG(source, coordinates: nil, quality: 0.95, maxEdge: 60, defaults: defaults))
        try expectRotated180(full, label: "full")
        try expectRotated180(preview, label: "preview")
    }

    @Test func coordinateLabelSitsBelowTheCrosshairAndReadsUpright() throws {
        let defaults = isolatedCrosshairDefaults(showCoordinates: true, mainColor: "000000")
        let source = try flatJPEG(width: 240, height: 240, red: 128, green: 128, blue: 128)
        let stamped = try #require(stampClueCrosshairJPEG(
            source,
            coordinates: "L",
            quality: 1,
            defaults: defaults
        ))
        let image = try decoded(stamped)
        var whites: [(x: Int, y: Int)] = []
        for y in 0..<image.height {
            for x in 0..<image.width {
                let pixel = image.pixel(x, y)
                if pixel.0 > 210 && pixel.1 > 210 && pixel.2 > 210 {
                    whites.append((x, y))
                }
            }
        }
        #expect(whites.count > 15)
        let minY = whites.map(\.y).min() ?? 0
        let maxY = whites.map(\.y).max() ?? 0
        let minX = whites.map(\.x).min() ?? 0
        let maxX = whites.map(\.x).max() ?? 0
        let midY = whites.reduce(0) { $0 + $1.y } / whites.count
        #expect(midY > image.height / 2)
        let leftEdge = minX + max(1, (maxX - minX) / 3)
        let rightEdge = maxX - max(1, (maxX - minX) / 3)
        let topEdge = minY + max(1, (maxY - minY) / 3)
        let bottomEdge = maxY - max(1, (maxY - minY) / 3)
        let left = whites.filter { $0.x <= leftEdge }.count
        let right = whites.filter { $0.x >= rightEdge }.count
        let upper = whites.filter { $0.y <= topEdge }.count
        let lower = whites.filter { $0.y >= bottomEdge }.count
        #expect(left > right, "stem should stay on the left, left=\(left) right=\(right)")
        #expect(lower > upper, "foot should stay at the bottom, upper=\(upper) lower=\(lower)")
    }

    private func isolatedCrosshairDefaults(showCoordinates: Bool, mainColor: String = "FFFFFF") -> UserDefaults {
        let name = "ClueCrosshairStampTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        defaults.set("Simple", forKey: "crosshair.style")
        defaults.set(4.0, forKey: "crosshair.widthPx")
        defaults.set(5.0, forKey: "crosshair.sizePercent")
        defaults.set(showCoordinates, forKey: "crosshair.showCoordinates")
        defaults.set(mainColor, forKey: "crosshair.mainColor")
        return defaults
    }

    private func expectUprightMarker(_ jpeg: Data, label: String) throws {
        let image = try decoded(jpeg)
        let topLeft = image.pixel(2, 2)
        let topRight = image.pixel(image.width - 3, 2)
        let bottom = image.pixel(image.width / 2, image.height - 3)
        #expect(topLeft.1 > 180 && topLeft.0 < 80, "\(label) top-left \(topLeft)")
        #expect(topRight.0 > 180 && topRight.2 < 80, "\(label) top-right \(topRight)")
        #expect(bottom.2 > 180 && bottom.0 < 60, "\(label) bottom \(bottom)")
        var peakGreen = 0
        for y in (image.height / 2 - 3)...(image.height / 2 + 3) {
            for x in (image.width / 2 - 3)...(image.width / 2 + 3) {
                peakGreen = max(peakGreen, Int(image.pixel(x, y).1))
            }
        }
        #expect(peakGreen > 200, "\(label) crosshair missing at center")
    }

    private func expectRotated180(_ jpeg: Data, label: String) throws {
        let image = try decoded(jpeg)
        let top = image.pixel(image.width / 2, 2)
        let bottom = image.pixel(image.width / 2, image.height - 3)
        #expect(top.2 > 180 && top.0 < 80, "\(label) oriented top \(top)")
        #expect(bottom.0 > 180 && bottom.2 < 80, "\(label) oriented bottom \(bottom)")
    }

    private func syntheticJPEG(width: Int, height: Int, topLeftGreen: Bool, orientation: Int?) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let topBand = height / 3
        let greenWidth = width * 3 / 8
        for y in 0..<height {
            for x in 0..<width {
                let index = (y * width + x) * 4
                let red: UInt8
                let green: UInt8
                let blue: UInt8
                if y < topBand {
                    if topLeftGreen && x < greenWidth {
                        red = 0; green = 255; blue = 0
                    } else {
                        red = 255; green = 0; blue = 0
                    }
                } else if y >= height - topBand {
                    red = 0; green = 0; blue = 255
                } else {
                    red = 128; green = 128; blue = 128
                }
                bytes[index] = red
                bytes[index + 1] = green
                bytes[index + 2] = blue
                bytes[index + 3] = 255
            }
        }
        return try encodeJPEG(bytes: bytes, width: width, height: height, orientation: orientation)
    }

    private func flatJPEG(width: Int, height: Int, red: UInt8, green: UInt8, blue: UInt8) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        for index in stride(from: 0, to: bytes.count, by: 4) {
            bytes[index] = red
            bytes[index + 1] = green
            bytes[index + 2] = blue
            bytes[index + 3] = 255
        }
        return try encodeJPEG(bytes: bytes, width: width, height: height, orientation: nil)
    }

    private func encodeJPEG(bytes: [UInt8], width: Int, height: Int, orientation: Int?) throws -> Data {
        let provider = try #require(CGDataProvider(data: Data(bytes) as CFData))
        let image = try #require(CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
        ))
        let out = NSMutableData()
        let destination = try #require(CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil))
        var properties: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: 0.95]
        if let orientation {
            properties[kCGImagePropertyOrientation] = orientation
        }
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        #expect(CGImageDestinationFinalize(destination))
        return out as Data
    }

    private func decoded(_ data: Data) throws -> RGBAImage {
        let source = try #require(CGImageSourceCreateWithData(data as CFData, nil))
        let image = try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        let width = image.width
        let height = image.height
        let bytesPerRow = width * 4
        var bytes = [UInt8](repeating: 0, count: bytesPerRow * height)
        bytes.withUnsafeMutableBytes { raw in
            let context = CGContext(
                data: raw.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
            context?.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return RGBAImage(width: width, height: height, bytes: bytes)
    }
}

private struct RGBAImage {
    let width: Int
    let height: Int
    let bytes: [UInt8]

    func pixel(_ x: Int, _ y: Int) -> (UInt8, UInt8, UInt8) {
        let index = (y * width + x) * 4
        return (bytes[index], bytes[index + 1], bytes[index + 2])
    }
}
