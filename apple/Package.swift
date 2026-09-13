// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "RID2CaltopoApple",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "R2CCore", targets: ["R2CCore"]),
        .library(name: "R2CAppleRadios", targets: ["R2CAppleRadios"]),
    ],
    targets: [
        .target(name: "CAOL", exclude: ["README.md", "LAZPERF-LICENSE", "GEOGRAPHICLIB-LICENSE"], publicHeadersPath: "include", cxxSettings: [.define("LAZPERF_VENDORED"), .headerSearchPath("geographiclib/include")]),
        .target(name: "R2CCore", dependencies: ["CAOL"]),
        .target(name: "R2CAppleRadios", dependencies: ["R2CCore"]),
        .testTarget(name: "R2CCoreTests", dependencies: ["R2CCore"]),
    ],
    cxxLanguageStandard: .cxx17
)
