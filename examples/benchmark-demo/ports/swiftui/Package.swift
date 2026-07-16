// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BenchmarkDemo",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "BenchmarkDemo", path: "Sources/BenchmarkDemo")
    ]
)
