// Renders the app icon (Android's launcher icon: white "verified user" shield on #1E6B45) to a 1024 px PNG.
//   swift ios/scripts/make-icon.swift ios/App/Assets.xcassets/AppIcon.appiconset/icon-1024.png
import AppKit
import CoreGraphics

let size = 1024.0
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "icon-1024.png"
let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: Int(size), height: Int(size), bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.setFillColor(CGColor(srgbRed: 0x1E / 255.0, green: 0x6B / 255.0, blue: 0x45 / 255.0, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))

// The Material 24-unit glyph, absolute coordinates (y down). Android launchers crop the adaptive icon to its inner
// ~72/108, so the glyph shows at about 60% of the icon; iOS shows the whole square, so it is drawn at 60% here.
let scale = size * 0.6 / 24
let off = size * 0.2
func p(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: off + x * scale, y: size - (off + y * scale)) }
let path = CGMutablePath()
path.move(to: p(12, 1)); path.addLine(to: p(3, 5)); path.addLine(to: p(3, 11))
path.addCurve(to: p(12, 23), control1: p(3, 16.55), control2: p(6.84, 21.74))
path.addCurve(to: p(21, 11), control1: p(17.16, 21.74), control2: p(21, 16.55))
path.addLine(to: p(21, 5)); path.closeSubpath()
path.move(to: p(10, 17)); path.addLine(to: p(6, 13)); path.addLine(to: p(7.41, 11.59)); path.addLine(to: p(10, 14.17))
path.addLine(to: p(16.59, 7.58)); path.addLine(to: p(18, 9)); path.closeSubpath()
ctx.addPath(path)
ctx.setFillColor(CGColor(gray: 1, alpha: 1))
ctx.fillPath(using: .evenOdd)

let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
