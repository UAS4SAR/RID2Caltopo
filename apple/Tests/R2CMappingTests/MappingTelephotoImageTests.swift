import XCTest
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import R2CCore
@testable import R2CMapping

final class MappingTelephotoImageTests:XCTestCase {
    func testTelephotoOriginalMetadataAndWrongLensRejection() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        defer{try? FileManager.default.removeItem(at:root)}
        for (id,focal,width,height) in [("m4td.visible.medium-tele",70,4032,3024),("m4td.visible.tele",168,4096,3072)] {
            try autoreleasepool {
                let context=try XCTUnwrap(CGContext(data:nil,width:width,height:height,bitsPerComponent:8,bytesPerRow:width,space:CGColorSpaceCreateDeviceGray(),bitmapInfo:CGImageAlphaInfo.none.rawValue))
                let image=try XCTUnwrap(context.makeImage()),encoded=NSMutableData()
                let destination=try XCTUnwrap(CGImageDestinationCreateWithData(encoded,UTType.jpeg.identifier as CFString,1,nil))
                CGImageDestinationAddImage(destination,image,[kCGImagePropertyExifDictionary:[kCGImagePropertyExifFocalLenIn35mmFilm:focal]] as CFDictionary)
                XCTAssertTrue(CGImageDestinationFinalize(destination))
                let metadata="http://ns.adobe.com/xap/1.0/\0<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:drone-dji=\"http://www.dji.com/drone-dji/1.0/\" drone-dji:ImageSource=\"ZoomCamera\" drone-dji:GpsLatitude=\"39\" drone-dji:GpsLongitude=\"-121\" drone-dji:RelativeAltitude=\"60\" drone-dji:AbsoluteAltitude=\"100\" drone-dji:GimbalYawDegree=\"0\" drone-dji:GimbalPitchDegree=\"-90\" drone-dji:GimbalRollDegree=\"0\"/></rdf:RDF></x:xmpmeta>"
                let segment=Data(metadata.utf8),length=segment.count+2
                var jpeg=Data([0xff,0xd8,0xff,0xe1,UInt8(length>>8),UInt8(length&255)])
                jpeg.append(segment);jpeg.append((encoded as Data).dropFirst(2))
                let url=root.appendingPathComponent(id+".jpg");try jpeg.write(to:url)
                let photo=try MappingOriginal.read(url,digest:"fixture",name:"tele.jpg",cameraProfileID:id)
                XCTAssertEqual(photo.width,width);XCTAssertEqual(photo.height,height)
                XCTAssertThrowsError(try MappingOriginal.read(url,digest:"fixture",name:"tele.jpg",cameraProfileID:"m4td.visible.wide"))
                let other=id.hasSuffix("medium-tele") ? "m4td.visible.tele":"m4td.visible.medium-tele"
                XCTAssertThrowsError(try MappingOriginal.read(url,digest:"fixture",name:"tele.jpg",cameraProfileID:other))
                XCTAssertEqual(try Data(contentsOf:url),jpeg)
            }
        }
    }
}
