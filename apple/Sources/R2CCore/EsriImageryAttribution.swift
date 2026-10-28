import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct EsriImageryViewport: Equatable, Sendable {
    public let south: Double
    public let west: Double
    public let north: Double
    public let east: Double
    public let zoom: Double
    public init(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        self.south = south; self.west = west; self.north = north; self.east = east; self.zoom = zoom
    }
    var valid: Bool {
        [south, west, north, east, zoom].allSatisfy(\.isFinite) && south <= north &&
        south >= -90 && north <= 90 && west >= -180 && west <= 180 && east >= -180 && east <= 180
    }
}

public struct EsriImageryAttribution: Decodable, Sendable {
    public static let fallbackSources = "Esri, Vantor, Earthstar Geographics, and the GIS User Community"
    public static let metadataURL = URL(string: "https://static.arcgis.com/attribution/World_Imagery")!
    public static let sourceURL = URL(string: "https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9")!
    public static let esriURL = URL(string: "https://www.esri.com/")!
    struct Contributor: Decodable, Sendable {
        let attribution: String
        let coverageAreas: [Coverage]
    }
    struct Coverage: Decodable, Sendable {
        let zoomMin: Double
        let zoomMax: Double
        let score: Double
        let bbox: [Double] // ArcGIS order: south, west, north, east.
        func intersects(_ view: EsriImageryViewport) -> Bool {
            guard bbox.count == 4, bbox.allSatisfy(\.isFinite), bbox[0] <= bbox[2],
                  bbox[0] <= view.north, bbox[2] >= view.south,
                  zoomMin <= ceil(view.zoom), zoomMax >= floor(view.zoom) else { return false }
            let viewParts: [(Double, Double)] = view.west <= view.east ? [(view.west, view.east)] : [(view.west, 180), (-180, view.east)]
            let areaParts: [(Double, Double)] = bbox[1] <= bbox[3] ? [(bbox[1], bbox[3])] : [(bbox[1], 180), (-180, bbox[3])]
            return viewParts.contains { v in areaParts.contains { a in a.0 <= v.1 && a.1 >= v.0 } }
        }
    }
    let contributors: [Contributor]
    public init(data: Data) throws {
        self = try JSONDecoder().decode(Self.self, from: data)
        guard !contributors.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
    }
    /// Called on a worker/actor: coverage filtering grows with the attribution catalog.
    public func sources(in view: EsriImageryViewport) -> String {
        guard view.valid else { return Self.fallbackSources }
        let matches = contributors.compactMap { contributor -> (String, Double)? in
            guard let score = contributor.coverageAreas.filter({ $0.intersects(view) }).map(\.score).max() else { return nil }
            return (contributor.attribution.trimmingCharacters(in: .whitespacesAndNewlines), score)
        }.sorted { $0.1 == $1.1 ? $0.0 < $1.0 : $0.1 > $1.1 }
        var seen = Set(["Esri", "Vantor", "Earthstar Geographics", "and the GIS User Community", "GIS User Community"])
        let extra = matches.compactMap { name, _ in !name.isEmpty && seen.insert(name).inserted ? name : nil }
        return ([Self.fallbackSources] + extra).joined(separator: "; ")
    }
}

/// Metadata requests contain no viewport or device coordinates. No retained disk cache.
public actor EsriImageryAttributionProvider {
    public static let shared = EsriImageryAttributionProvider()
    private var bundled: EsriImageryAttribution?
    private var remote: Task<EsriImageryAttribution?, Never>?
    public func sources(in viewport: EsriImageryViewport, refresh: Bool = false) async -> String {
        if bundled == nil, let url = Bundle.module.url(forResource: "world-imagery-attribution", withExtension: "json"),
           let data = try? Data(contentsOf: url) { bundled = try? EsriImageryAttribution(data: data) }
        if refresh && remote == nil {
            remote = Task {
                let configuration = URLSessionConfiguration.ephemeral
                configuration.timeoutIntervalForRequest = 12
                configuration.timeoutIntervalForResource = 18
                let session = URLSession(configuration: configuration)
                defer { session.invalidateAndCancel() }
                do {
                    let (data, response) = try await session.data(from: EsriImageryAttribution.metadataURL)
                    guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 4_000_000 else { return nil }
                    return try EsriImageryAttribution(data: data)
                } catch { return nil }
            }
        }
        let catalog = refresh ? (await remote?.value ?? bundled) : bundled
        return catalog?.sources(in: viewport) ?? EsriImageryAttribution.fallbackSources
    }
}
