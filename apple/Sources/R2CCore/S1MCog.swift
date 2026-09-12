import Foundation

/// Lossless subsets of classic, tiled EPSG:6350 S1M GeoTIFFs. No resampling or recompression.
public struct S1MCog: Sendable {
    public struct ByteRange: Sendable { public let offset: Int; public let length: Int }
    public struct Piece: Sendable {
        public let row: Int, col: Int
        public let header: Data
        public let ranges: [ByteRange]
        public var bytes: Int64 { Int64(header.count + ranges.reduce(0) { $0 + $1.length }) }
        public func name(original: String) -> String {
            (original as NSString).deletingPathExtension + "_part_\(row)_\(col).tif"
        }
    }
    private let header: Data, little: Bool, ifd: Int, entries: [Int: Int]
    private let width: Int, height: Int, offsets: [Int], counts: [Int]
    private static func integer(_ data: Data, _ at: Int, _ size: Int, _ little: Bool) throws -> Int {
        guard at >= 0, size <= 8, at <= data.count - size else { throw CocoaError(.fileReadCorruptFile) }
        var value: UInt64 = 0
        for i in 0..<size { value |= UInt64(data[at + i]) << (8 * (little ? i : size - 1 - i)) }
        guard value <= UInt64(Int.max) else { throw CocoaError(.fileReadCorruptFile) }
        return Int(value)
    }
    private func integer(_ at: Int, _ size: Int) throws -> Int { try Self.integer(header, at, size, little) }
    private func address(_ tag: Int) throws -> Int { try Self.address(header, entries, tag, little) }
    private static func address(_ data: Data, _ entries: [Int: Int], _ tag: Int, _ little: Bool) throws -> Int {
        guard let e = entries[tag] else { throw CocoaError(.fileReadCorruptFile) }
        let type = try integer(data, e + 2, 2, little)
        let size: Int
        switch type { case 1, 2: size = 1; case 3: size = 2; case 4: size = 4; case 12: size = 8; default: throw CocoaError(.fileReadCorruptFile) }
        let count = try integer(data, e + 4, 4, little)
        guard count > 0, count <= 65536 / size else { throw CocoaError(.fileReadCorruptFile) }
        let at = count * size <= 4 ? e + 8 : try integer(data, e + 8, 4, little)
        guard at <= data.count - count * size else { throw CocoaError(.fileReadCorruptFile) }
        return at
    }
    private static func integers(_ data: Data, _ entries: [Int: Int], _ tag: Int, _ little: Bool) throws -> [Int] {
        guard let e = entries[tag] else { throw CocoaError(.fileReadCorruptFile) }
        let at = try address(data, entries, tag, little)
        let type = try integer(data, e + 2, 2, little)
        guard type == 3 || type == 4 else { throw CocoaError(.fileReadCorruptFile) }
        let size = type == 3 ? 2 : 4
        return try (0..<integer(data, e + 4, 4, little)).map { try integer(data, at + $0 * size, size, little) }
    }
    private func double(_ at: Int) throws -> Double {
        guard at >= 0, at <= header.count - 8 else { throw CocoaError(.fileReadCorruptFile) }
        var bits: UInt64 = 0
        for i in 0..<8 { bits |= UInt64(header[at + i]) << (8 * (little ? i : 7 - i)) }
        return Double(bitPattern: bits)
    }
    public init(header: Data) throws {
        guard header.count >= 8, (header[0] == 73 && header[1] == 73) || (header[0] == 77 && header[1] == 77) else { throw CocoaError(.fileReadCorruptFile) }
        self.header = header; little = header[0] == 73
        guard try Self.integer(header, 2, 2, little) == 42 else { throw CocoaError(.fileReadCorruptFile) }
        ifd = try Self.integer(header, 4, 4, little)
        let n = try Self.integer(header, ifd, 2, little)
        guard ifd >= 8, (1...100).contains(n), ifd + 2 + n * 12 + 4 <= header.count else { throw CocoaError(.fileReadCorruptFile) }
        var fields: [Int: Int] = [:]
        for i in 0..<n { let e = ifd + 2 + i * 12; fields[try Self.integer(header, e, 2, little)] = e }
        entries = fields
        let scalar: (Int) throws -> Int = { tag in
            let values = try Self.integers(header, fields, tag, header[0] == 73)
            guard values.count == 1 else { throw CocoaError(.fileReadCorruptFile) }
            return values[0]
        }
        width = try scalar(256); height = try scalar(257)
        guard (1...20000).contains(width), (1...20000).contains(height), try scalar(322) == 512, try scalar(323) == 512 else { throw CocoaError(.fileReadCorruptFile) }
        let keys = try Self.integers(header, fields, 34735, little)
        guard keys.count >= 4, stride(from: 4, to: keys.count - 3, by: 4).contains(where: { keys[$0] == 3072 && keys[$0 + 1] == 0 && keys[$0 + 3] == 6350 }), fields[34264] == nil else { throw CocoaError(.fileReadCorruptFile) }
        offsets = try Self.integers(header, fields, 324, little); counts = try Self.integers(header, fields, 325, little)
        guard offsets.count == ((width + 511) / 512) * ((height + 511) / 512), counts.count == offsets.count,
              counts.allSatisfy({ (1...4_000_000).contains($0) }), offsets.allSatisfy({ $0 >= header.count }),
              let scale = fields[33550], let tie = fields[33922],
              try Self.integer(header, scale + 2, 2, little) == 12, try Self.integer(header, tie + 2, 2, little) == 12,
              try Self.integer(header, scale + 4, 4, little) >= 2, try Self.integer(header, tie + 4, 4, little) == 6
        else { throw CocoaError(.fileReadCorruptFile) }
        guard try double(address(33550)) == 1, try double(address(33550) + 8) == 1 else { throw CocoaError(.fileReadCorruptFile) }
    }
    public func pieces(minX: Double, maxX: Double, minY: Double, maxY: Double) throws -> [Piece] {
        guard [minX, maxX, minY, maxY].allSatisfy({ $0.isFinite && abs($0) < 100_000_000 }) else { throw CocoaError(.fileReadCorruptFile) }
        let tie = try address(33922)
        let x = try double(tie + 24) - double(tie), y = try double(tie + 32) + double(tie + 8)
        guard x.isFinite, y.isFinite, abs(x) < 100_000_000, abs(y) < 100_000_000 else { throw CocoaError(.fileReadCorruptFile) }
        let left = max(0, Int(floor(minX - x - 2))), right = min(width - 1, Int(ceil(maxX - x + 2)))
        let top = max(0, Int(floor(y - maxY - 2))), bottom = min(height - 1, Int(ceil(y - minY + 2)))
        if left > right || top > bottom { return [] }
        return try (top / 1024...bottom / 1024).flatMap { row in try (left / 1024...right / 1024).map { col in try piece(row: row, col: col) } }
    }
    private func piece(row: Int, col: Int) throws -> Piece {
        let w = min(1536, width - col * 1024), h = min(1536, height - row * 1024)
        let ids = (0..<(h + 511) / 512).flatMap { r in (0..<(w + 511) / 512).map { c in (row * 2 + r) * ((width + 511) / 512) + col * 2 + c } }
        let ranges = ids.map { ByteRange(offset: offsets[$0], length: counts[$0]) }
        var out = header; out.append(Data(count: ids.count * 8))
        func put(_ at: Int, _ size: Int, _ value: UInt64) { for i in 0..<size { out[at + i] = UInt8(truncatingIfNeeded: value >> (8 * (little ? i : size - 1 - i))) } }
        for (tag, value) in [(256, w), (257, h)] { let e = entries[tag]!; put(e + 2, 2, 4); put(e + 4, 4, 1); put(e + 8, 4, UInt64(value)) }
        let tie = try address(33922)
        put(tie, 8, try (double(tie) - Double(col * 1024)).bitPattern)
        put(tie + 8, 8, try (double(tie + 8) - Double(row * 1024)).bitPattern)
        var position = out.count
        for (group, tag) in [324, 325].enumerated() {
            let e = entries[tag]!, at = header.count + group * ids.count * 4
            put(e + 2, 2, 4); put(e + 4, 4, UInt64(ids.count))
            put(e + 8, 4, UInt64(ids.count == 1 ? (tag == 324 ? position : ranges[0].length) : at))
            for (i, range) in ranges.enumerated() {
                put(at + i * 4, 4, UInt64(tag == 324 ? position : range.length))
                if tag == 324 { position += range.length }
            }
        }
        let kept = entries.filter { $0.key != 330 }.values.sorted().map { out.subdata(in: $0..<$0 + 12) }
        put(ifd, 2, UInt64(kept.count))
        for (i, bytes) in kept.enumerated() { out.replaceSubrange(ifd + 2 + i * 12..<ifd + 2 + (i + 1) * 12, with: bytes) }
        put(ifd + 2 + kept.count * 12, 4, 0)
        return Piece(row: row, col: col, header: out, ranges: ranges)
    }
}
