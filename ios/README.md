# HTT Robot Control — iOS

iOS app for controlling the **STM32-based robot dog** over **Bluetooth Low Energy**
(JDY-23 module, `FFE0`/`FFE1` serial passthrough).

This is the iOS port of the Android app in [`../STM32BtControl`](../STM32BtControl).
It speaks the **exact same protocol** — the STM32 firmware and the JDY-23 wiring do
not change at all.

Built by **High Tech Technology Limited (HTT)**.

---

## Does it work with the JDY-23?

Yes. The JDY-23 is a standard BLE serial module, and iOS talks to it through Apple's
**Core Bluetooth** framework. The app writes the same 2-byte ASCII commands to the
`FFE1` characteristic of the `FFE0` service, which the module forwards over its UART
TX pin into the STM32 (`USART3`).

One iOS difference worth knowing: iOS never exposes a device's Bluetooth MAC address.
Devices are identified by a per-phone **UUID** instead, which is what the picker shows.
Functionally it makes no difference to driving the robot.

---

## Command table (identical to Android)

| Action        | Bytes | Hex     |
|---------------|-------|---------|
| Stand (立正)   | `ll`  | `6C 6C` |
| Forward (前进) | `ff`  | `66 66` |
| Backward (后退)| `aa`  | `61 61` |
| Turn Left (左转)| `ls` | `6C 73` |
| Turn Right (右转)| `rs`| `72 73` |
| Sway (摇摆)    | `rr`  | `72 72` |
| Handshake (握手)| `lu` | `6C 75` |
| Sit (坐)       | `ru`  | `72 75` |
| Sleep (睡觉)   | `ld`  | `6C 64` |
| Stop (制动)    | `pp`  | `70 70` |

---

## Build & run — **requires a Mac**

Unlike the Android app, an iOS app **cannot be built on Windows**. You need:

- A **Mac** with **Xcode 16 or newer**
- An **iPhone** (BLE does not work in the Simulator — Core Bluetooth needs real hardware)
- An **Apple ID**. A free account lets you install to your own iPhone for 7 days at a
  time; a paid **Apple Developer** account ($99/yr) is needed for longer-lived installs
  or the App Store.

### Steps

1. Copy this `STM32BtControl IOS` folder to a Mac.
2. Open **`STM32BtControl.xcodeproj`** in Xcode.
3. Select the **STM32BtControl** target → **Signing & Capabilities** → pick your
   Team (your Apple ID) and change the **Bundle Identifier** to something unique to
   you (e.g. `com.yourname.stm32btcontrol`).
4. Plug in your iPhone, select it as the run destination, and press **▶ Run**.
5. On the iPhone, trust the developer profile under
   **Settings → General → VPN & Device Management** if prompted.

---

## Use

1. Turn on Bluetooth and power the robot so the JDY-23 is advertising.
2. Open the app → **Scan & Connect** → allow the Bluetooth permission prompt.
3. Pick the module from the list (the one tagged **⭐ serial** / strongest signal is
   the most likely) → wait for **"Connected – ready"**.
4. Drive it with the D-pad and action tiles.

> Make sure the JDY-23 baud rate matches the STM32 `USART3_Init(...)` value
> (JDY-23 default is 9600).

---

## Project layout

```
STM32BtControl IOS/
├── STM32BtControl.xcodeproj/      # Xcode project (open this)
└── STM32BtControl/
    ├── STM32BtControlApp.swift    # @main app entry
    ├── ContentView.swift          # SwiftUI UI: header, D-pad, action tiles
    ├── BLEManager.swift           # Core Bluetooth scan / connect / write
    ├── Theme.swift                # HTT brand palette
    └── Assets.xcassets/           # AppIcon + AccentColor
```

### Notes

- The header shows an **"HTT" text badge** as a placeholder. To use the real logo,
  drag the logo image into `Assets.xcassets`, name it `htt_logo`, and replace the
  badge in `ContentView.swift`'s `header` with `Image("htt_logo")`.
- The Bluetooth permission string lives in the target's build settings
  (`INFOPLIST_KEY_NSBluetoothAlwaysUsageDescription`), so there is no separate
  `Info.plist` file to edit.
- Minimum iOS version: **16.0**.

---

© High Tech Technology Limited (HTT)
