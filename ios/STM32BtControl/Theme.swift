import SwiftUI

/// HTT brand palette (green circuit theme) — mirrors the Android app colours.
enum Brand {
    static let green      = Color(hex: 0x2E9E4F)
    static let greenDark  = Color(hex: 0x1B7A38)
    static let red        = Color(hex: 0xE53935)
    static let redDark    = Color(hex: 0xB71C1C)
    static let pageBg     = Color(hex: 0xEAF1EC)
    static let cardBg     = Color(hex: 0xFFFFFF)
    static let textDark   = Color(hex: 0x1D2B22)
    static let textMuted  = Color(hex: 0x6B7B71)
    static let okGreen    = Color(hex: 0x1B5E20)
    static let warnOrange = Color(hex: 0xE65100)
    static let errRed     = Color(hex: 0xB71C1C)

    static let tileFill   = Color(hex: 0xF2F8F4)
    static let tileStroke = Color(hex: 0xCFE6D6)
}

/// The kind of status message shown under the connect button.
enum StatusKind {
    case ok, warn, error, info

    var color: Color {
        switch self {
        case .ok:    return Brand.okGreen
        case .warn:  return Brand.warnOrange
        case .error: return Brand.errRed
        case .info:  return Brand.warnOrange
        }
    }
}

extension Color {
    /// Build a Color from a 0xRRGGBB integer literal.
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}
