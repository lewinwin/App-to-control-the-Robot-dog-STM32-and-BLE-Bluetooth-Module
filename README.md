# HTT Robot Control

Android app for controlling an **STM32-based robot dog** over **Bluetooth Low Energy**
(JDY-23 module, `FFE0`/`FFE1` serial passthrough).

Built by **High Tech Technology Limited (HTT)**.

---

## Features

- Scans and connects to the JDY-23 BLE module — no pairing or PIN required
- **D-pad** movement controls: Forward, Backward, Left, Right, Stop
- **Action** tiles: Stand, Sway, Handshake, Sit, Sleep
- Sends the robot's 2-byte ASCII commands over BLE
- On-screen connection status and per-command send result
- GATT service inspector dialog for debugging the module
- Clean, branded UI (Android only, min SDK 21 / Android 5.0+)

---

## Protocol

Each command is a **2-byte ASCII string** written to the `FFE1` characteristic of the
JDY-23. The module forwards those bytes over its UART TX pin to the STM32
(read on `USART3`). The 16-bit UUIDs used:

| Role             | UUID   |
|------------------|--------|
| Service          | `FFE0` |
| Characteristic   | `FFE1` (write + notify) |

### Command table

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

> `HEX 66` and the character `f` are the same byte (`0x66`) — the app sends the
> characters, which equal the hex values in the table. See `command_table.png`.

---

## Build

No Gradle or Android Studio required — the app builds directly with the Android SDK
command-line tools (`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`):

```bash
bash build.sh
```

Output: **`STM32-BT-Control.apk`**

### Requirements

- JDK (for `javac` / `keytool`)
- Android SDK with `build-tools` and a platform (`android.jar`)
- `build.sh` points at the SDK via `$LOCALAPPDATA/Android/Sdk` — adjust for your setup

---

## Install & use

1. Copy `STM32-BT-Control.apk` to an Android phone and install it
   (allow "install from unknown sources").
2. Turn on Bluetooth (and Location on Android 11 or below, which BLE scanning needs).
3. Power the robot so the JDY-23 is advertising.
4. Open the app → **Scan & Connect** → grant permissions → pick the module →
   wait for **"Connected – ready"**.
5. Drive it with the D-pad and action buttons.

> Make sure the JDY-23 baud rate matches the STM32 `USART3_Init(...)` value
> (JDY-23 default is 9600).

---

## Project layout

```
STM32BtControl/
├── AndroidManifest.xml
├── build.sh                 # command-line build pipeline
├── command_table.png        # the 10-command reference
├── STM32-BT-Control.apk     # prebuilt, signed app
├── res/
│   ├── drawable/            # htt_logo, bg_header, ic_launcher
│   └── values/strings.xml
└── src/com/stm32/btcontrol/MainActivity.java
```

---

© High Tech Technology Limited (HTT)
