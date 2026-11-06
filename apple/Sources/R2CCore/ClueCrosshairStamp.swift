import CoreGraphics
import CoreText
import Foundation
import ImageIO

/// Draws the clue crosshair into a capture JPEG off the main thread.
/// ImageIO applies the source orientation first, then CoreGraphics draws into an upright bitmap.
/// A UIKit-style y-flip is omitted: it inverts `makeImage()` pixels on the way to JPEG.
public func stampClueCrosshairJPEG(
    _ data: Data,
    coordinates: String?,
    quality: Double = 0.9,
    maxEdge: Int? = nil,
    defaults: UserDefaults = .standard
) -> Data? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let image = clueImageApplyingOrientation(source: source, maxEdge: maxEdge)
    else { return nil }
    let stamped = stampClueCrosshairCGImage(image, coordinates: coordinates, defaults: defaults)
    let out = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(out, "public.jpeg" as CFString, 1, nil) else {
        return nil
    }
    CGImageDestinationAddImage(
        destination,
        stamped,
        [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
    )
    guard CGImageDestinationFinalize(destination) else { return nil }
    return out as Data
}

public func stampClueCrosshairCGImage(
    _ image: CGImage,
    coordinates: String?,
    defaults: UserDefaults = .standard
) -> CGImage {
    drawClueCrosshair(on: image, coordinates: coordinates, defaults: defaults) ?? image
}

private func clueImageApplyingOrientation(source: CGImageSource, maxEdge: Int?) -> CGImage? {
    var options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
    ]
    if let maxEdge, maxEdge > 0 {
        options[kCGImageSourceThumbnailMaxPixelSize] = maxEdge
    }
    return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
}

private func drawClueCrosshair(on image: CGImage, coordinates: String?, defaults: UserDefaults) -> CGImage? {
    let style = defaults.string(forKey: "crosshair.style") ?? "Simple"
    guard style != "None" else { return image }
    let lineWidth = max(0.1, defaults.object(forKey: "crosshair.widthPx") as? Double ?? 1)
    let percent = min(25, max(2, defaults.object(forKey: "crosshair.sizePercent") as? Double ?? 5))
    let pixelWidth = image.width
    let pixelHeight = image.height
    guard pixelWidth > 0, pixelHeight > 0,
          let context = CGContext(
            data: nil,
            width: pixelWidth,
            height: pixelHeight,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
          )
    else { return nil }
    context.interpolationQuality = .high
    context.draw(image, in: CGRect(x: 0, y: 0, width: pixelWidth, height: pixelHeight))
    let center = CGPoint(x: CGFloat(pixelWidth) / 2, y: CGFloat(pixelHeight) / 2)
    let arm = min(CGFloat(pixelWidth), CGFloat(pixelHeight)) * percent / 200
    let path = CGMutablePath()
    path.move(to: CGPoint(x: center.x - arm, y: center.y))
    path.addLine(to: CGPoint(x: center.x + arm, y: center.y))
    path.move(to: CGPoint(x: center.x, y: center.y - arm))
    path.addLine(to: CGPoint(x: center.x, y: center.y + arm))
    if style == "Bordered" {
        context.addPath(path)
        context.setStrokeColor(clueCrosshairCGColor(defaults.string(forKey: "crosshair.borderColor") ?? "000000"))
        context.setLineWidth(lineWidth + 2)
        context.strokePath()
    }
    context.addPath(path)
    context.setStrokeColor(clueCrosshairCGColor(defaults.string(forKey: "crosshair.mainColor") ?? "FFFFFF"))
    context.setLineWidth(lineWidth)
    context.strokePath()
    if let coordinates, defaults.object(forKey: "crosshair.showCoordinates") as? Bool ?? true {
        let fontSize = max(14, min(CGFloat(pixelWidth), CGFloat(pixelHeight)) * 0.018)
        let font = CTFontCreateWithName("Menlo-Bold" as CFString, fontSize, nil)
        let attributes = [
            kCTFontAttributeName: font,
            kCTForegroundColorAttributeName: CGColor(gray: 1, alpha: 1),
        ] as CFDictionary
        guard let attributed = CFAttributedStringCreate(nil, coordinates as CFString, attributes) else { return context.makeImage() }
        let line = CTLineCreateWithAttributedString(attributed)
        var ascent: CGFloat = 0
        var descent: CGFloat = 0
        let textWidth = CGFloat(CTLineGetTypographicBounds(line, &ascent, &descent, nil))
        let textHeight = ascent + descent
        let originX = center.x - textWidth / 2
        // y increases upward. The label sits below the crosshair, toward the bottom of the photo.
        let textTop = center.y - arm - 8
        let textBottom = textTop - textHeight
        context.setFillColor(CGColor(gray: 0, alpha: 0.75))
        context.fill(CGRect(x: originX - 4, y: textBottom - 3, width: textWidth + 8, height: textHeight + 6))
        context.textPosition = CGPoint(x: originX, y: textBottom + descent)
        CTLineDraw(line, context)
    }
    return context.makeImage()
}

private func clueCrosshairCGColor(_ hex: String) -> CGColor {
    let value = UInt32(hex, radix: 16) ?? 0xFFFFFF
    return CGColor(
        red: CGFloat((value >> 16) & 255) / 255,
        green: CGFloat((value >> 8) & 255) / 255,
        blue: CGFloat(value & 255) / 255,
        alpha: 1
    )
}
