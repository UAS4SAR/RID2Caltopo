import Foundation
import CoreGraphics

public struct TrackerQRSVGRectangle: Sendable, Equatable {
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double
}

public struct TrackerQRSVGDocument: Sendable, Equatable {
    public let minX: Double
    public let minY: Double
    public let width: Double
    public let height: Double
    public let rectangles: [TrackerQRSVGRectangle]
}

public enum TrackerQRSVGError: LocalizedError, Sendable, Equatable {
    case fileTooLarge
    case invalidDocument
    case unsupportedPath

    public var errorDescription: String? {
        switch self {
        case .fileTooLarge:
            "The SVG QR image is too large."
        case .invalidDocument:
            "The SVG QR image is invalid."
        case .unsupportedPath:
            "The SVG is not a supported tracker QR image."
        }
    }
}

/// Parses the deliberately small SVG subset emitted by Python qrcode's
/// `SvgPathImage`: an SVG viewBox plus absolute M/H/V/H/Z rectangular modules.
/// It does not execute or render general SVG content.
public enum TrackerQRSVG {
    public static let maximumByteCount = 2 * 1024 * 1024

    private static let number = #"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"#

    public static func parse(_ data: Data) throws -> TrackerQRSVGDocument {
        guard data.count <= maximumByteCount else { throw TrackerQRSVGError.fileTooLarge }
        guard let svg = String(data: data, encoding: .utf8) else {
            throw TrackerQRSVGError.invalidDocument
        }

        let viewBoxPattern =
            #"\bviewBox\s*=\s*["']\s*("# + number + #")[,\s]+("# + number
            + #")[,\s]+("# + number + #")[,\s]+("# + number + #")\s*["']"#
        guard let viewBox = try firstCaptureGroups(viewBoxPattern, in: svg, count: 4),
              let minX = Double(viewBox[0]),
              let minY = Double(viewBox[1]),
              let width = Double(viewBox[2]),
              let height = Double(viewBox[3]),
              minX.isFinite, minY.isFinite, width.isFinite, height.isFinite,
              width > 0, height > 0
        else {
            throw TrackerQRSVGError.invalidDocument
        }

        let pathPattern = #"<path\b[^>]*\bd\s*=\s*["']([^"']+)["'][^>]*>"#
        let paths = try allCaptureGroups(pathPattern, in: svg, count: 1).compactMap { $0.first }
        guard !paths.isEmpty else { throw TrackerQRSVGError.invalidDocument }

        let rectanglePattern =
            #"M\s*("# + number + #")\s*[,\s]\s*("# + number + #")\s*H\s*("#
            + number + #")\s*V\s*("# + number + #")\s*H\s*("# + number + #")\s*[zZ]"#
        let rectangleRegex = try NSRegularExpression(pattern: rectanglePattern)
        var rectangles: [TrackerQRSVGRectangle] = []

        for path in paths {
            let pathRange = NSRange(path.startIndex..<path.endIndex, in: path)
            let matches = rectangleRegex.matches(in: path, range: pathRange)
            var consumedThrough = path.startIndex
            for match in matches {
                guard let fullRange = Range(match.range, in: path),
                      path[consumedThrough..<fullRange.lowerBound]
                        .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                else {
                    throw TrackerQRSVGError.unsupportedPath
                }
                consumedThrough = fullRange.upperBound
                let values = try (1...5).map { index -> Double in
                    guard let range = Range(match.range(at: index), in: path),
                          let value = Double(path[range]), value.isFinite
                    else { throw TrackerQRSVGError.invalidDocument }
                    return value
                }
                let x1 = values[0]
                let y1 = values[1]
                let x2 = values[2]
                let y2 = values[3]
                guard abs(values[4] - x1) <= 0.000_001 else {
                    throw TrackerQRSVGError.unsupportedPath
                }
                let left = min(x1, x2)
                let top = min(y1, y2)
                let rectangleWidth = abs(x2 - x1)
                let rectangleHeight = abs(y2 - y1)
                guard rectangleWidth > 0, rectangleHeight > 0,
                      left >= minX, top >= minY,
                      left + rectangleWidth <= minX + width + 0.000_001,
                      top + rectangleHeight <= minY + height + 0.000_001
                else {
                    throw TrackerQRSVGError.invalidDocument
                }
                rectangles.append(
                    TrackerQRSVGRectangle(
                        x: left,
                        y: top,
                        width: rectangleWidth,
                        height: rectangleHeight
                    )
                )
            }
            guard path[consumedThrough...]
                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                throw TrackerQRSVGError.unsupportedPath
            }
        }

        guard !rectangles.isEmpty else { throw TrackerQRSVGError.invalidDocument }
        return TrackerQRSVGDocument(
            minX: minX,
            minY: minY,
            width: width,
            height: height,
            rectangles: rectangles
        )
    }

    public static func rasterize(
        _ data: Data,
        maximumDimension: Int = 2048,
        pixelsPerModule: Double = 12
    ) throws -> CGImage {
        let document = try parse(data)
        guard maximumDimension > 0, pixelsPerModule > 0,
              let moduleSize = document.rectangles.map({ min($0.width, $0.height) }).min(),
              moduleSize > 0
        else {
            throw TrackerQRSVGError.invalidDocument
        }
        let preferredScale = pixelsPerModule / moduleSize
        let maximumScale = Double(maximumDimension) / max(document.width, document.height)
        let scale = min(preferredScale, maximumScale)
        let pixelWidth = max(1, Int(ceil(document.width * scale)))
        let pixelHeight = max(1, Int(ceil(document.height * scale)))
        guard let context = CGContext(
            data: nil,
            width: pixelWidth,
            height: pixelHeight,
            bitsPerComponent: 8,
            bytesPerRow: pixelWidth,
            space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else {
            throw TrackerQRSVGError.invalidDocument
        }
        context.setShouldAntialias(false)
        context.setFillColor(gray: 1, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: pixelWidth, height: pixelHeight))
        context.setFillColor(gray: 0, alpha: 1)
        for rectangle in document.rectangles {
            let left = floor((rectangle.x - document.minX) * scale)
            let right = ceil((rectangle.x + rectangle.width - document.minX) * scale)
            let top = floor((rectangle.y - document.minY) * scale)
            let bottom = ceil((rectangle.y + rectangle.height - document.minY) * scale)
            context.fill(
                CGRect(
                    x: left,
                    y: Double(pixelHeight) - bottom,
                    width: right - left,
                    height: bottom - top
                )
            )
        }
        guard let image = context.makeImage() else {
            throw TrackerQRSVGError.invalidDocument
        }
        return image
    }

    private static func firstCaptureGroups(
        _ pattern: String,
        in value: String,
        count: Int
    ) throws -> [String]? {
        try allCaptureGroups(pattern, in: value, count: count).first
    }

    private static func allCaptureGroups(
        _ pattern: String,
        in value: String,
        count: Int
    ) throws -> [[String]] {
        let regex = try NSRegularExpression(
            pattern: pattern,
            options: [.caseInsensitive, .dotMatchesLineSeparators]
        )
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return regex.matches(in: value, range: range).compactMap { match in
            guard match.numberOfRanges == count + 1 else { return nil }
            return (1...count).compactMap { index in
                Range(match.range(at: index), in: value).map { String(value[$0]) }
            }
        }.filter { $0.count == count }
    }
}
