import SwiftUI

/// A single motion / action command: icon, English label, Chinese label, bytes.
private struct Command: Identifiable {
    let id = UUID()
    let icon: String
    let label: String
    let zh: String
    let bytes: String
}

// D-pad movement — matches DPAD[] in the Android app.
private let forward  = Command(icon: "▲", label: "Forward",  zh: "前进", bytes: "ff")
private let left     = Command(icon: "◀", label: "Left",     zh: "左转", bytes: "ls")
private let stop     = Command(icon: "■", label: "Stop",     zh: "制动", bytes: "pp")
private let right    = Command(icon: "▶", label: "Right",    zh: "右转", bytes: "rs")
private let backward = Command(icon: "▼", label: "Backward", zh: "后退", bytes: "aa")

// Action tiles — matches ACTIONS[] in the Android app.
private let actions: [Command] = [
    Command(icon: "🐕", label: "Stand",     zh: "立正", bytes: "ll"),
    Command(icon: "🎵", label: "Sway",      zh: "摇摆", bytes: "rr"),
    Command(icon: "🤝", label: "Handshake", zh: "握手", bytes: "lu"),
    Command(icon: "🦴", label: "Sit",       zh: "坐",   bytes: "ru"),
    Command(icon: "💤", label: "Sleep",     zh: "睡觉", bytes: "ld"),
]

struct ContentView: View {
    @StateObject private var ble = BLEManager()

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                header
                VStack(spacing: 16) {
                    connectCard
                    movementCard
                    actionsCard
                }
                .padding(14)
                .padding(.bottom, 24)
            }
        }
        .background(Brand.pageBg.ignoresSafeArea())
        .sheet(isPresented: $ble.showPicker) { devicePicker }
    }

    // MARK: - Header

    private var header: some View {
        ZStack(alignment: .leading) {
            LinearGradient(colors: [Color(hex: 0x0E3A1E), Color(hex: 0x103A20)],
                           startPoint: .leading, endPoint: .trailing)
            HStack(spacing: 14) {
                // HTT logo badge — drop `htt_logo` into Assets and swap this out.
                Text("HTT")
                    .font(.system(size: 18, weight: .heavy))
                    .foregroundColor(Brand.green)
                    .frame(width: 58, height: 58)
                    .background(Circle().fill(.white))

                VStack(alignment: .leading, spacing: 2) {
                    Text("Robot Control")
                        .font(.system(size: 23, weight: .bold))
                        .foregroundColor(.white)
                    Text("High Tech Technology Limited")
                        .font(.system(size: 12))
                        .foregroundColor(Color(hex: 0xB9F5CC))
                    Text("STM32 · JDY-23 Bluetooth LE")
                        .font(.system(size: 11))
                        .foregroundColor(.white.opacity(0.8))
                }
                Spacer()
            }
            .padding(16)
        }
        .frame(height: 150)
    }

    // MARK: - Connect card

    private var connectCard: some View {
        Card {
            Button(action: ble.startScan) {
                Text(ble.isScanning ? "Scanning…" : "Scan & Connect")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Brand.green))
            }
            .disabled(ble.isScanning)

            Text(ble.statusText)
                .font(.system(size: 14))
                .foregroundColor(ble.statusKind.color)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 12)
        }
    }

    // MARK: - Movement card

    private var movementCard: some View {
        Card {
            SectionLabel("MOVEMENT")
            VStack(spacing: 10) {
                HStack(spacing: 10) { spacer; dpadButton(forward); spacer }
                HStack(spacing: 10) { dpadButton(left); dpadButton(stop); dpadButton(right) }
                HStack(spacing: 10) { spacer; dpadButton(backward); spacer }
            }
        }
    }

    private var spacer: some View { Color.clear.frame(maxWidth: .infinity, minHeight: 66) }

    private func dpadButton(_ cmd: Command) -> some View {
        let isStop = cmd.label == "Stop"
        return Button {
            ble.send(cmd.bytes, label: cmd.label)
        } label: {
            VStack(spacing: 2) {
                Text(cmd.icon).font(.system(size: 18, weight: .bold))
                Text(cmd.label).font(.system(size: 15, weight: .bold))
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity, minHeight: 66)
            .background(RoundedRectangle(cornerRadius: 16)
                .fill(isStop ? Brand.red : Brand.green))
        }
        .disabled(!ble.isConnected)
        .opacity(ble.isConnected ? 1 : 0.5)
    }

    // MARK: - Actions card

    private var actionsCard: some View {
        Card {
            SectionLabel("ACTIONS")
            let columns = [GridItem(.flexible(), spacing: 10),
                           GridItem(.flexible(), spacing: 10)]
            LazyVGrid(columns: columns, spacing: 10) {
                ForEach(actions) { actionTile($0) }
            }
        }
    }

    private func actionTile(_ cmd: Command) -> some View {
        Button {
            ble.send(cmd.bytes, label: cmd.label)
        } label: {
            VStack(spacing: 2) {
                Text(cmd.icon).font(.system(size: 26))
                Text(cmd.label)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(Brand.greenDark)
                Text(cmd.zh)
                    .font(.system(size: 12))
                    .foregroundColor(Brand.textMuted)
            }
            .frame(maxWidth: .infinity, minHeight: 92)
            .background(
                RoundedRectangle(cornerRadius: 18)
                    .fill(Brand.tileFill)
                    .overlay(RoundedRectangle(cornerRadius: 18)
                        .stroke(Brand.tileStroke, lineWidth: 1))
            )
        }
        .disabled(!ble.isConnected)
        .opacity(ble.isConnected ? 1 : 0.5)
    }

    // MARK: - Device picker

    private var devicePicker: some View {
        NavigationView {
            List(ble.discovered) { dev in
                Button {
                    ble.connect(dev)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(dev.name)
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(Brand.textDark)
                                if dev.hasSerialService {
                                    Text("⭐ serial").font(.system(size: 12))
                                        .foregroundColor(Brand.green)
                                }
                            }
                            Text(dev.id.uuidString)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(Brand.textMuted)
                        }
                        Spacer()
                        Text("\(dev.rssi) dBm")
                            .font(.system(size: 13))
                            .foregroundColor(Brand.textMuted)
                    }
                }
            }
            .navigationTitle("Choose your JDY-23")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { ble.showPicker = false }
                }
            }
        }
    }
}

// MARK: - Reusable pieces

/// A white rounded card matching the Android `card()` helper.
private struct Card<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 20).fill(Brand.cardBg))
            .shadow(color: .black.opacity(0.08), radius: 4, y: 2)
    }
}

private struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .bold))
            .kerning(1.4)
            .foregroundColor(Brand.textMuted)
            .padding(.bottom, 10)
    }
}

#Preview {
    ContentView()
}
