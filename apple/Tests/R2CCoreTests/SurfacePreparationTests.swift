import Foundation
import CryptoKit
import Testing
import CAOL
@testable import R2CCore

private var surfaceFixtureRoot: URL {
    URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("test-fixtures/aol")
}
private func preparationBounds(width: Double) -> OperationalMapBounds {
    let dy=width/2/6371008.8*180/Double.pi,dx=dy/cos(39*Double.pi/180)
    return .init(north:39+dy,south:39-dy,west:-121-dx,east:-121+dx)
}
@Test func surfacePreparationPlansParcelsAndOneMileRadius() throws {
    for acres in [40.0,60.0] {
        let width=sqrt(acres*4046.8564224),p=try OperationalSurfacePreparation.grid(preparationBounds(width:width))
        #expect(p.tiles==1);#expect(Double(p.width)>=width+123.9);#expect(Double(p.width)<=ceil(width+124)+1)
    }
    #expect(try OperationalSurfacePreparation.grid(preparationBounds(width:2*1609.344)).tiles==16)
    #expect(throws:(any Error).self) { try OperationalSurfacePreparation.grid(preparationBounds(width:5000)) }
}
@Test func surfacePreparationUsesOneSurvey() throws {
    let sources=try OperationalSurfacePreparation.sources(pages:[Data(contentsOf:surfaceFixtureRoot.appendingPathComponent("usgs-lpc-catalog.json"))])
    #expect(sources.count==6);#expect(Set(sources.map(\.survey)).count==1)
    #expect(sources.allSatisfy { $0.survey.contains("CA_SierraNevada_2_2022") })
    #expect(throws:(any Error).self) { try OperationalSurfacePreparation.sources(pages:[Data("{\"items\":[]}".utf8)]) }
}
@Test func surfaceNativePreparationSeamsReferencesAndCancellation() async throws {
    for projection in ["albers","utm"] {
        var plan=try OperationalSurfacePreparation.grid(preparationBounds(width:4))
        let files=["west","east"].map { surfaceFixtureRoot.appendingPathComponent("native-\(projection)-\($0).laz") }
        let sources=try OperationalSurfacePreparation.sources(pages:[Data(contentsOf:surfaceFixtureRoot.appendingPathComponent("usgs-lpc-catalog.json"))])
        plan.sources=Array(sources.prefix(2))
        var hashes:[String:String]=[:]
        for (i,file) in files.enumerated() { hashes[plan.sources[i].url]=SHA256.hash(data:try Data(contentsOf:file)).map { String(format:"%02x",$0) }.joined() }
        let directory=FileManager.default.temporaryDirectory.appendingPathComponent("aol-native-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        _ = try await OperationalSurfacePreparation.assemble(plan:plan,files:files,hashes:hashes,directory:directory,progress:{ _ in })
        let p=try OperationalSurfacePackage(data:Data(contentsOf:directory.appendingPathComponent("tile-0-0.aol")))
        let center=OperationalSurfacePackage.Point(latitude:39,longitude:-121)
        let disk=p.disk(center)
        #expect(disk.complete);#expect(disk.peak?.elevation==320);#expect(p.groundAt(center)==300)
        #expect(p.metadata.referenceGroup?.isEmpty == false)
        let clearance=OperationalAOLState.calculate(package:p,position:center,takeoff:center,height:50)
        #expect(abs((clearance.feet ?? 0)-30/0.3048)<0.001)
        if projection=="albers" {
            var wide=try OperationalSurfacePreparation.grid(.init(north:plan.bounds.north,south:plan.bounds.south,west:preparationBounds(width:1000).west,east:preparationBounds(width:1000).east))
            wide.sources=plan.sources
            let tiled=directory.appendingPathComponent("wide");try FileManager.default.createDirectory(at:tiled,withIntermediateDirectories:true)
            _ = try await OperationalSurfacePreparation.assemble(plan:wide,files:files,hashes:hashes,directory:tiled,progress:{ _ in })
            let left=try OperationalSurfacePackage(data:Data(contentsOf:tiled.appendingPathComponent("tile-0-0.aol")))
            let right=try OperationalSurfacePackage(data:Data(contentsOf:tiled.appendingPathComponent("tile-0-1000.aol")))
            let seam=right.coordinate(right.metadata.coreWest!+0.25,0.25)
            let a=left.disk(seam),b=right.disk(seam)
            #expect(a.complete && b.complete);#expect(a.peak==b.peak)
            #expect(right.groundAt(center)==nil)
            let crossTile=OperationalAOLState.calculate(package:right,position:seam,takeoff:center,height:50,compatibleGround:left.groundAt(center))
            #expect(crossTile.feet != nil)
            let route=[left.coordinate(-498,0),left.coordinate(498,0)]
            let x=left.briefing(points:route,corridor:60.96,polygon:false,coreOnly:true)
            let y=right.briefing(points:route,corridor:60.96,polygon:false,coreOnly:true)
            #expect(x.missing+y.missing==0);#expect(x.checked+y.checked>100000)
        }
        let task=Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await OperationalSurfacePreparation.assemble(plan:plan,files:files,hashes:hashes,directory:directory,progress:{ _ in })
        }
        do { _ = try await task.value;Issue.record("Cancelled preparation succeeded") } catch is CancellationError {} catch { Issue.record("Unexpected cancellation error: \(error)") }
    }
    let grid=try #require(aol_grid_create(39,-121,-10,-10,20,20));defer { aol_grid_free(grid) }
    #expect(aol_grid_open(grid,surfaceFixtureRoot.appendingPathComponent("native-unknown-reference.laz").path)<0)
    #expect(String(cString:aol_grid_error(grid)).contains("NAVD88"))
}

private actor CatalogResponses {
    var statuses: [Int]
    var calls = 0
    init(_ statuses: [Int]) { self.statuses = statuses }
    func fetch(_ request: URLRequest) throws -> (Data, URLResponse) {
        calls += 1
        #expect(request.timeoutInterval == 25)
        #expect(request.cachePolicy == .reloadIgnoringLocalCacheData)
        #expect(request.value(forHTTPHeaderField: "Accept") == "application/json")
        let status = statuses.removeFirst()
        return (Data("{\"items\":[]}".utf8), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Retry-After":"0"])!)
    }
}

@Test func surfaceCatalogRetriesTransientHTTPAndReportsPermanentFailure() async throws {
    let request = URLRequest(url: URL(string: "https://tnmaccess.nationalmap.gov/api/v1/products")!)
    let recovery = CatalogResponses([503, 429, 200])
    let data = try await OperationalSurfaceCatalog.data(for: request, transport: { try await recovery.fetch($0) }, sleep: { _ in })
    #expect(!data.isEmpty)
    #expect(await recovery.calls == 3)
    let denied = CatalogResponses([403])
    do {
        _ = try await OperationalSurfaceCatalog.data(for: request, transport: { try await denied.fetch($0) }, sleep: { _ in })
        Issue.record("Expected an HTTP error")
    } catch { #expect(error.localizedDescription.contains("HTTP 403")) }
    #expect(await denied.calls == 1)
    let unavailable = CatalogResponses([503,503,503,503])
    do {
        _ = try await OperationalSurfaceCatalog.data(for: request, transport: { try await unavailable.fetch($0) }, sleep: { _ in })
        Issue.record("Expected bounded retry exhaustion")
    } catch { #expect(error.localizedDescription.contains("HTTP 503")) }
    #expect(await unavailable.calls == 4)
    let cancelled = CatalogResponses([503,200])
    do {
        _ = try await OperationalSurfaceCatalog.data(for: request, transport: { try await cancelled.fetch($0) }, sleep: { _ in throw CancellationError() })
        Issue.record("Expected cancellation")
    } catch { #expect(error is CancellationError) }
    #expect(await cancelled.calls == 1)
}

@Test func preparedAOLSetTransferValidationAndAge() throws {
    let root=surfaceFixtureRoot.appendingPathComponent("prepared-set")
    let data=try Data(contentsOf:root.appendingPathComponent("index.json"))
    try OperationalPreparedSurfaceSet.validate(data) { try Data(contentsOf:root.appendingPathComponent($0)) }
    let stamp:Int64=1700000000000
    #expect(OperationalPreparedSurfaceSet.fresh(prepared:stamp,now:stamp+999,maxAge:1000))
    #expect(!OperationalPreparedSurfaceSet.fresh(prepared:stamp,now:stamp+1000,maxAge:1000))
    #expect(!OperationalPreparedSurfaceSet.fresh(prepared:stamp,now:stamp-1,maxAge:1000))
    #expect(!OperationalPreparedSurfaceSet.fresh(prepared:0,now:stamp,maxAge:1000))
    #expect(throws:(any Error).self) { try OperationalPreparedSurfaceSet.validate(data) { _ in Data([1,2,3]) } }
    var wrong=try JSONSerialization.jsonObject(with:data) as! [String:Any];wrong["width"]=162
    #expect(throws:(any Error).self) { try OperationalPreparedSurfaceSet.validate(JSONSerialization.data(withJSONObject:wrong)) { try Data(contentsOf:root.appendingPathComponent($0)) } }
    let archive=try OperationalZipArchive.encode([.init(path:"aol/1700000000000-abc/index.json",data:data),.init(path:"aol/1700000000000-abc/tile-0-0.aol",data:Data(contentsOf:root.appendingPathComponent("tile-0-0.aol")))])
    let lookup=Dictionary(uniqueKeysWithValues:try OperationalZipArchive.decode(archive).map { ($0.path,$0.data) })
    try OperationalPreparedSurfaceSet.validate(lookup["aol/1700000000000-abc/index.json"]!) { lookup["aol/1700000000000-abc/\($0)"]! }
}

@Test func preparedAOLCoverageRequiresMargin() {
    #expect(OperationalPreparedSurfaceSet.contains(width:524,height:524,west:-200,south:-200,east:200,north:200))
    #expect(OperationalPreparedSurfaceSet.contains(width:524,height:524,west:-100,south:-100,east:100,north:100))
    #expect(!OperationalPreparedSurfaceSet.contains(width:524,height:524,west:-200,south:-200,east:201,north:200))
    #expect(!OperationalPreparedSurfaceSet.contains(width:400,height:400,west:-200,south:-200,east:200,north:200))
}
