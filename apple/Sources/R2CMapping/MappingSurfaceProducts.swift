import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import R2CCore

public enum MappingSurfaceProducts {
    static func json(_ object: [String:Any], _ url: URL) throws {try JSONSerialization.data(withJSONObject:object,options:[.sortedKeys]).write(to:url,options:.atomic)}
    static func png(_ pixels: [UInt32], _ url: URL) throws {
        var bytes=[UInt8]();bytes.reserveCapacity(pixels.count*4)
        for color in pixels {bytes += [UInt8((color>>16)&255),UInt8((color>>8)&255),UInt8(color&255),UInt8(color>>24)]}
        guard let provider=CGDataProvider(data:Data(bytes) as CFData),let image=CGImage(width:256,height:256,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:1024,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.premultipliedLast.rawValue),provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent) else {throw MappingMission.Failure("Cannot encode map tile.")}
        try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
        guard let destination=CGImageDestinationCreateWithURL(url as CFURL,UTType.png.identifier as CFString,1,nil) else {throw MappingMission.Failure("Cannot save map tile.")}
        CGImageDestinationAddImage(destination,image,nil);guard CGImageDestinationFinalize(destination) else {throw MappingMission.Failure("Could not finish map tile.")}
    }
    public static func export(_ g: MappingGeometryResult, rasters: URL, output: URL, bounds: MappingProcessingPolicy.Bounds,
        polygon: [[Double]], zoom: Int, progress: (String,Double)throws->Void) throws {
        try progress("Refining local surface heights",0.68)
        let g=try MappingNativeEngine.refine(g)
        try MappingNativeEngine.prepareProjection(g,rasters:rasters,progress:progress)
        let fm=FileManager.default,surface=output.appendingPathComponent("surface")
        try fm.createDirectory(at:surface,withIntermediateDirectories:true)
        let clipped=bounds.expanded(MappingProcessingPolicy.outputOverlapMeters/g.groundScale)
        func inside(_ x: Double,_ y: Double) -> Bool {
            guard x>=clipped.west,x<=clipped.east,y>=clipped.south,y<=clipped.north else{return false}
            var yes=false,j=polygon.count-1;var distance=Double.infinity
            for i in polygon.indices {let a=polygon[i],b=polygon[j]
                if (a[1]>y) != (b[1]>y),x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0]{yes.toggle()}
                let dx=b[0]-a[0],dy=b[1]-a[1],den=dx*dx+dy*dy,t=den>0 ? min(1,max(0,((x-a[0])*dx+(y-a[1])*dy)/den)):0
                distance=min(distance,hypot(x-a[0]-t*dx,y-a[1]-t*dy));j=i
            }
            return yes || distance*g.groundScale<=5
        }
        var colors=[UInt32](repeating:0,count:g.width*g.height)
        let bx=(g.width+255)/256,by=(g.height+255)/256
        for yy in 0..<by {for xx in 0..<bx {
            try progress("Coloring 3D surface · block \(yy*bx+xx+1)/\(bx*by)",0.69+0.035*Double(yy*bx+xx)/Double(bx*by))
            let left=g.x+Double(xx*256)*g.cell,top=g.y+Double(g.height-1-yy*256)*g.cell
            if (left+256*g.cell)/g.groundScale+g.originX<clipped.west || left/g.groundScale+g.originX>clipped.east || top/g.groundScale+g.originY<clipped.south || (top-256*g.cell)/g.groundScale+g.originY>clipped.north {continue}
            let pixels=try MappingNativeEngine.render(g,rasters:rasters,x:left-g.cell/2,y:top+g.cell/2,step:g.cell)
            for row in 0..<min(256,g.height-yy*256){for col in 0..<min(256,g.width-xx*256){
                if inside((left+Double(col)*g.cell)/g.groundScale+g.originX,(top-Double(row)*g.cell)/g.groundScale+g.originY){colors[(g.height-1-yy*256-row)*g.width+xx*256+col]=pixels[row*256+col]}
            }}
        }}
        try mesh(g,colors:colors,polygon:polygon,output:surface.appendingPathComponent("surface.glb"),progress:progress)
        let metadata:[String:Any]=["reconstructionMode":g.approximate ? "approximate-plane":"measured-surface","measuredStereoCells":g.measuredCells,"knownProjectionCells":g.heights.filter{$0.isFinite}.count,"accuracy":"not independently measured","type":"r2c_visible_surface","version":1,"width":g.width,"height":g.height,"gridX":g.x,"gridY":g.y,"cellMeters":g.cell,"meshSpacingMeters":0.4,"originMercatorX":g.originX,"originMercatorY":g.originY,"mercatorToGroundScale":g.groundScale,"originDjiAltitude":g.originAltitude,"byteOrder":"little-endian","noData":"NaN","refinement":"local-median-v1","projection":"ray-coverage-photo-seams-v5"]
        try json(metadata,surface.appendingPathComponent("surface.json"))
        var heights=Data();heights.reserveCapacity(g.heights.count*4);for z in g.heights{heights.appendLE(z.bitPattern)};try heights.write(to:surface.appendingPathComponent("surface.f32"),options:.atomic)
        try json(["camerasEnu":g.cameras,"intrinsicsPixels":g.fullIntrinsics,"width":g.rasterWidth,"height":g.rasterHeight,"originalSha256":g.used.map{g.digests[$0]}],surface.appendingPathComponent("cameras.json"))
        let unit=MappingOriginal.world/(256*pow(2,Double(zoom))),half=MappingOriginal.world/2
        let west=max(clipped.west,g.originX+g.x/g.groundScale),east=min(clipped.east,g.originX+(g.x+Double(g.width-1)*g.cell)/g.groundScale)
        let south=max(clipped.south,g.originY+g.y/g.groundScale),north=min(clipped.north,g.originY+(g.y+Double(g.height-1)*g.cell)/g.groundScale)
        guard east>west,north>south else {throw MappingMission.Failure("Region has no supported surface inside the assignment.")}
        let minX=Int(floor((west+half)/(256*unit))),maxX=Int(floor((east+half)/(256*unit))),minY=Int(floor((half-north)/(256*unit))),maxY=Int(floor((half-south)/(256*unit)))
        let count=(maxX-minX+1)*(maxY-minY+1);guard count<=16000 else{throw MappingMission.Failure("Too many tiles in one processing region.")}
        var index=0
        for ty in minY...maxY {for tx in minX...maxX {
            try progress("Rendering 2D tiles · \(index+1)/\(count)",0.73+0.25*Double(index)/Double(count));index+=1
            let mapX=Double(tx*256)*unit-half,mapY=half-Double(ty*256)*unit
            var pixels=try MappingNativeEngine.render(g,rasters:rasters,x:(mapX-g.originX)*g.groundScale,y:(mapY-g.originY)*g.groundScale,step:unit*g.groundScale)
            for row in 0..<256{for col in 0..<256{if !inside(mapX+(Double(col)+0.5)*unit,mapY-(Double(row)+0.5)*unit){pixels[row*256+col]=0}}}
            if pixels.contains(where:{$0>>24>0}) {try png(pixels,output.appendingPathComponent("\(zoom)/\(tx)/\(ty).png"))}
        }}
    }
    private static func mesh(_ g: MappingGeometryResult, colors: [UInt32], polygon: [[Double]], output: URL, progress: (String,Double)throws->Void) throws {
        let stride=max(1,Int(ceil(0.4/g.cell-1e-9))),cols=(g.width-1)/stride+1,rows=(g.height-1)/stride+1
        var ids=[Int](repeating:-1,count:cols*rows),positions=Data(),normals=Data(),rgba=Data(),indices=Data(),count=0
        var lo=[Float](repeating:.infinity,count:3),hi=[Float](repeating:-.infinity,count:3)
        for row in 0..<rows {try progress("Building 0.4 m mesh · row \(row+1)/\(rows)",0.725)
            for col in 0..<cols {let source=row*stride*g.width+col*stride,z=g.heights[source],color=colors[source];if !z.isFinite || color==0 {continue}
                ids[row*cols+col]=count;count+=1
                let point=[Float(g.x+Double(col*stride)*g.cell),z,Float(-g.y-Double(row*stride)*g.cell)]
                for axis in 0..<3{positions.appendLE(point[axis].bitPattern);lo[axis]=min(lo[axis],point[axis]);hi[axis]=max(hi[axis],point[axis])}
                func at(_ x: Int,_ y: Int) -> Double {let value=g.heights[min(g.height-1,max(0,y))*g.width+min(g.width-1,max(0,x))];return Double(value.isFinite ? value:z)}
                let xx=col*stride,yy=row*stride,nx = -(at(xx+stride,yy)-at(xx-stride,yy))/(2*Double(stride)*g.cell),nz=(at(xx,yy+stride)-at(xx,yy-stride))/(2*Double(stride)*g.cell),length=sqrt(nx*nx+nz*nz+1)
                for normal in [nx/length,1/length,nz/length]{normals.appendLE(Float(normal).bitPattern)}
                rgba.append(contentsOf:[UInt8((color>>16)&255),UInt8((color>>8)&255),UInt8(color&255),255])
            }
        }
        guard count>=500 else{throw MappingMission.Failure("Too few colored surface vertices in this region.")}
        for id in try MappingNativeEngine.faces(g,stride:stride,ids:ids){indices.appendLE(UInt32(id))}
        guard !indices.isEmpty else{throw MappingMission.Failure("No supported 3D faces in this region.")}
        let blocks=[positions,normals,rgba,indices];var offset=0,views:[[String:Any]]=[]
        for (i,block) in blocks.enumerated(){views.append(["buffer":0,"byteOffset":offset,"byteLength":block.count,"target":i==3 ? 34963:34962]);offset+=block.count}
        let accessors:[[String:Any]]=[["bufferView":0,"componentType":5126,"count":count,"type":"VEC3","min":lo,"max":hi],["bufferView":1,"componentType":5126,"count":count,"type":"VEC3"],["bufferView":2,"componentType":5121,"normalized":true,"count":count,"type":"VEC4"],["bufferView":3,"componentType":5125,"count":indices.count/4,"type":"SCALAR"]]
        let document:[String:Any]=["asset":["version":"2.0","generator":"R2C local RTK stereo surface"],"scene":0,"scenes":[["nodes":[0]]],"nodes":[["mesh":0]],"meshes":[["primitives":[["attributes":["POSITION":0,"NORMAL":1,"COLOR_0":2],"indices":3,"mode":4]]]],"accessors":accessors,"bufferViews":views,"buffers":[["byteLength":offset]],"extras":["reconstructionMode":g.approximate ? "approximate-plane":"measured-surface","originDjiAltitude":g.originAltitude,"meshSpacingMeters":0.4,"originMercatorX":g.originX,"originMercatorY":g.originY,"mercatorToGroundScale":g.groundScale,"assignmentBoundary":polygon.map{[$0[0]/6378137*180/Double.pi,(2*atan(exp($0[1]/6378137))-Double.pi/2)*180/Double.pi]}]]
        var header=try JSONSerialization.data(withJSONObject:document,options:[.sortedKeys]);while header.count%4 != 0{header.append(32)}
        var glb=Data();glb.appendLE(0x46546c67);glb.appendLE(2);glb.appendLE(UInt32(12+8+header.count+8+offset));glb.appendLE(UInt32(header.count));glb.appendLE(0x4e4f534a);glb.append(header);glb.appendLE(UInt32(offset));glb.appendLE(0x004e4942);for block in blocks{glb.append(block)}
        try glb.write(to:output,options:.atomic)
    }
}
private extension Data {
    mutating func appendLE(_ value: UInt32){var v=value.littleEndian;Swift.withUnsafeBytes(of:&v){append(contentsOf:$0)}}
}
