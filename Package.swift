// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CSearchRevamped",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "CSearchRevamped",
            path: "Sources/CSearchRevamped"
        )
    ]
)
