import Foundation
import ImageIO
import CoreGraphics
import CryptoKit
import R2CCore
import R2CMappingNative

public struct MappingOriginal: Codable, Sendable {
    public let digest, name, capturedAt: String
    public let width, height: Int
    public let latitude, longitude, heightMeters, absoluteAltitude, yaw, pitch, roll: Double
    public let sigmaEast, sigmaNorth, sigmaHeight: Double
    public let laser: [Double]?
    public var positioningMode:String?=nil
    public static let world = 40075016.68557849
    public var east: Double { longitude*Self.world/360 }
    public var north: Double { log(tan(.pi/4+latitude * .pi/360))*Self.world/(2 * .pi) }
    public var rotation: [Double] {
        let y=yaw * .pi/180,p=pitch * .pi/180,r=roll * .pi/180
        let cy=cos(y),sy=sin(y),cp=cos(p),sp=sin(p),cr=cos(r),sr=sin(r)
        return [sy*sp*sr+cy*cr,cy*sp*sr-sy*cr,-cp*sr,sy*sp*cr-cy*sr,cy*sp*cr+sy*sr,-cp*cr,sy*cp,cy*cp,sp]
    }
    public static func read(_ url: URL, digest: String, name: String, cameraProfileID: String = "m4td.visible.wide") throws -> Self {
        guard let profile=MappingResolution.profiles.first(where:{$0.id==cameraProfileID && $0.mode == .visible}) else {throw MappingMission.Failure("Unsupported original camera profile.")}
        guard let source=CGImageSourceCreateWithURL(url as CFURL,nil), let properties=CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [String:Any],
              let width=properties[kCGImagePropertyPixelWidth as String] as? Int, let height=properties[kCGImagePropertyPixelHeight as String] as? Int,
              width==Int(profile.sensor.widthPixels), height==Int(profile.sensor.heightPixels) else { throw MappingMission.Failure("Original dimensions differ from the planned 12 MP camera. Confirm capture settings in Pilot 2.") }
        let input=try FileHandle(forReadingFrom:url);defer{try? input.close()}
        let head=try input.read(upToCount:2*1024*1024) ?? Data()
        let bytes=[UInt8](head)
        guard bytes.count>=2,bytes[0]==0xff,bytes[1]==0xd8 else{throw MappingMission.Failure("A selected original is not JPEG.")}
        var cursor=2,segments:[String]=[]
        while cursor+1<bytes.count {
            guard bytes[cursor]==0xff else{throw MappingMission.Failure("Invalid original JPEG header.")};cursor+=1
            while cursor<bytes.count && bytes[cursor]==0xff{cursor+=1}
            guard cursor<bytes.count else{break};let marker=bytes[cursor];cursor+=1
            if marker==0xda || marker==0xd9{break}
            if marker==0xd8 || (0xd0...0xd7).contains(marker) || marker==1{continue}
            guard cursor+2<=bytes.count else{break};let length=Int(bytes[cursor])*256+Int(bytes[cursor+1]);cursor+=2
            guard length>=2,cursor+length-2<=bytes.count else{break}
            if marker==0xe1{segments.append(String(decoding:bytes[cursor..<(cursor+length-2)],as:UTF8.self))};cursor+=length-2
        }
        let text=segments.joined(separator:"\n")
        let cameraPattern="(?:[\\w-]+:)?ImageSource\\s*=\\s*['\"](WideCamera|ZoomCamera|MediumTeleCamera|TeleCamera)['\"]"
        guard text.range(of:cameraPattern,options:.regularExpression) != nil else{throw MappingMission.Failure("This original is not identified as a visible camera.")}
        let cameraExif=properties[kCGImagePropertyExifDictionary as String] as? [String:Any]
        let actualFocal=(cameraExif?[kCGImagePropertyExifFocalLenIn35mmFilm as String] as? NSNumber)?.doubleValue
        let sensor=text.contains("WideCamera") ? "visible-wide":"visible-zoom"
        guard profile.matchesOriginal(sensor:sensor,focal35mm:actualFocal) else{throw MappingMission.Failure("Original camera or focal length does not match the mission. Keep different lenses separate.")}
        func attr(_ name: String) -> String? {
            let pattern="(?:drone-dji|exif|tiff):"+NSRegularExpression.escapedPattern(for:name)+"\\s*=\\s*['\"]([^'\"]*)['\"]"
            guard let regex=try? NSRegularExpression(pattern:pattern),let match=regex.firstMatch(in:text,range:NSRange(text.startIndex...,in:text)),let range=Range(match.range(at:1),in:text) else{return nil}
            return String(text[range])
        }
        func number(_ name: String) throws -> Double {
            guard let value=attr(name).flatMap(Double.init),value.isFinite else {throw MappingMission.Failure("Original is missing \(name) exposure metadata.")};return value
        }

        let latitude=try number("GpsLatitude"), longitude=try (attr("GpsLongitude")==nil ? number("GpsLongtitude"):number("GpsLongitude"))
        let heightMeters=try number("RelativeAltitude"),absoluteAltitude=try number("AbsoluteAltitude"),pitch=try number("GimbalPitchDegree")
        let uncertainties=[attr("RtkStdLon"),attr("RtkStdLat"),attr("RtkStdHgt")].map{$0.flatMap(Double.init)}
        let fixed=attr("RtkFlag")=="50" && uncertainties.allSatisfy{value in value.map{$0.isFinite && $0>0 && $0<1} ?? false}
        let east=try Self.positionSigma(uncertainties[0],fixed:fixed,vertical:false),north=try Self.positionSigma(uncertainties[1],fixed:fixed,vertical:false),vertical=try Self.positionSigma(uncertainties[2],fixed:fixed,vertical:true)
        guard (-85...85).contains(latitude),(-180...180).contains(longitude),(5...500).contains(heightMeters),(-93 ... -42).contains(pitch),
              [east,north,vertical].allSatisfy({$0>0 && $0<=10}) else {throw MappingMission.Failure("Unsupported exposure pose or RTK uncertainty.")}
        let exif=properties[kCGImagePropertyExifDictionary as String] as? [String:Any]
        var laser: [Double]?
        if attr("LRFStatus")=="Normal",let lat=try? number("LRFTargetLat"),let lon=try? number("LRFTargetLon"),let alt=try? number("LRFTargetAbsAlt"),let distance=try? number("LRFTargetDistance") {laser=[lat,lon,alt,distance]}
        return Self(digest:digest,name:name,capturedAt:exif?[kCGImagePropertyExifDateTimeOriginal as String] as? String ?? attr("DateTimeOriginal") ?? "",width:width,height:height,
            latitude:latitude,longitude:longitude,heightMeters:heightMeters,absoluteAltitude:absoluteAltitude,yaw:try number("GimbalYawDegree"),pitch:pitch,roll:try number("GimbalRollDegree"),sigmaEast:max(0.02,east),sigmaNorth:max(0.02,north),sigmaHeight:max(0.03,vertical),laser:laser,positioningMode:fixed ? "fixed-rtk":"gnss-assisted")
    }
    static func positionSigma(_ recorded:Double?,fixed:Bool,vertical:Bool)throws->Double {
        if let recorded,recorded.isFinite,recorded>10{throw MappingMission.Failure("Exposure position uncertainty exceeds the supported 10 m range.")}
        let floor=fixed ? (vertical ? 0.03:0.02):(vertical ? 2.0:1.0)
        return max(recorded.flatMap{$0.isFinite && $0>0 ? $0:nil} ?? 0,floor)
    }
    /// ImageIO reads one original at a time; only its requested raster lives in memory.
    public static func raster(_ original: URL, output: URL, sample: Bool) throws -> (Int,Int) {
        guard let source=CGImageSourceCreateWithURL(original as CFURL,[kCGImageSourceShouldCache:false] as CFDictionary) else {throw MappingMission.Failure("Cannot decode original.")}
        guard let image=CGImageSourceCreateImageAtIndex(source,0,[kCGImageSourceShouldCacheImmediately:true] as CFDictionary) else {throw MappingMission.Failure("Cannot decode original pixels.")}
        let width=image.width,height=image.height
        guard width>0,height>0,width<=12000,height<=12000 else {throw MappingMission.Failure("Unsupported original size.")}
        let color=CGColorSpace(name:CGColorSpace.sRGB)!
        let bitmap=CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        var data=Data(count:width*height*4)
        try data.withUnsafeMutableBytes { bytes in
            guard let context=CGContext(data:bytes.baseAddress,width:width,height:height,bitsPerComponent:8,bytesPerRow:width*4,space:color,bitmapInfo:bitmap) else {throw MappingMission.Failure("Cannot allocate bounded original raster.")}
            context.draw(image,in:CGRect(x:0,y:0,width:width,height:height))
        }
        if sample {
            var factor=1
            while width/factor>1400 || height/factor>1400 {factor *= 2}
            guard width%factor==0,height%factor==0 else {throw MappingMission.Failure("Unsupported original sampling dimensions.")}
            var gray=Data(count:(width/factor)*(height/factor)),error=[CChar](repeating:0,count:512)
            let count=gray.count
            let success=data.withUnsafeBytes { rgba in gray.withUnsafeMutableBytes { out in
                R2CMappingSampleRGBA(rgba.bindMemory(to:UInt8.self).baseAddress,Int32(width),Int32(height),Int32(factor),out.bindMemory(to:UInt8.self).baseAddress,count,&error,error.count)
            }}
            guard success==1 else {throw MappingMission.Failure(String(cString:error))}
            try gray.write(to:output,options:.atomic);return (width/factor,height/factor)
        }
        try data.write(to:output,options:.atomic);return (width,height)
    }
}

public enum MappingFileIO {
    public static func relative(_ file:URL,to root:URL) throws -> String {
        let base=root.resolvingSymlinksInPath().standardizedFileURL.path,child=file.resolvingSymlinksInPath().standardizedFileURL.path
        guard child.hasPrefix(base+"/") else{throw MappingMission.Failure("Mapping file is outside its owning directory.")}
        return String(child.dropFirst(base.count+1))
    }
    public static func sha(_ url: URL, progress: (Int64)throws->Void = {_ in}) throws -> String {
        let file=try FileHandle(forReadingFrom:url);defer{try? file.close()};var hash=SHA256(),done:Int64=0
        while let block=try file.read(upToCount:256*1024),!block.isEmpty {hash.update(data:block);done+=Int64(block.count);try progress(done)}
        return hash.finalize().map{String(format:"%02x",$0)}.joined()
    }
    public static func copy(_ source: URL, to target: URL, progress: (Int64)throws->Void) throws {
        let input=try FileHandle(forReadingFrom:source);defer{try? input.close()}
        FileManager.default.createFile(atPath:target.path,contents:nil)
        let output=try FileHandle(forWritingTo:target);defer{try? output.close()};var done:Int64=0
        while let block=try input.read(upToCount:256*1024),!block.isEmpty {try output.write(contentsOf:block);done+=Int64(block.count);try progress(done)}
        try output.synchronize()
    }
    public static func json<T: Encodable>(_ value: T, to url: URL) throws {try JSONEncoder().encode(value).write(to:url,options:.atomic)}
}
