// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "ResProxyCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "ResProxyCore", targets: ["ResProxyCore"])],
    targets: [
        .target(name: "ResProxyCore"),
        .testTarget(name: "ResProxyCoreTests", dependencies: ["ResProxyCore"]),
    ]
)
