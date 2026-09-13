[Uploading README.md…]()
# Cardputer BLE Blocks

**Bluetooth Low Energy file transfer between M5Stack Cardputer ADV (MicroHydra) and Android.**

Complete triad:

| Component | Description |
|-----------|-------------|
| **CardputerBleBlocks** (Android) | App v1.3 — send and receive files over BLE |
| **ble_send** | MicroHydra app — send files from the Cardputer |
| **ble_recv** | MicroHydra app — receive files on the Cardputer |

Compatible with **MicroHydra v2.6-preview** (`CARDPUTER_ADV` firmware).

**Android APK:** [Download BleBlocksMicroHydra.apk](https://github.com/danodanotes/BleBlocksMicroHydra/raw/main/BleBlocksMicroHydra.apk)

---

## Features

- Block protocol with markers `##BLOCKS` / `##TYPE` / `##FILE` / `##ID` / `##MORE`
- **Bidirectional** transfer:
  - Phone → Cardputer
  - Cardputer → Phone
  - Cardputer → Cardputer
- Configurable block size (recommended 400–800 characters)
- Receiver **strips all protocol lines** before saving
- File is only saved when the **last block** arrives (no `##MORE`)
- Adaptive icon (BLE waves + blocks)
- MicroHydra app-folder support (`__init__.py` is sent as `name.py`)

---

## Requirements

### Hardware
- M5Stack **Cardputer ADV** (TCA8418 keyboard)
- Android phone with Bluetooth LE (API 24+)

### Software
- Firmware: **MicroHydra v2.6-preview** for CARDPUTER_ADV  
  (`MicroHydra_CARDPUTER_ADV_v2.6-preview.bin`)
- Android Studio or `./gradlew` to build the app

---

## Quick install

### 1. Cardputer apps

**Recommended format (with icon):** copy the full folders:

```
/apps/ble_send/__init__.py
/apps/ble_send/icon.raw
/apps/ble_recv/__init__.py
/apps/ble_recv/icon.raw
```

Also valid (no menu icon):

```
/apps/ble_send.py
/apps/ble_recv.py
```

You can use `/sd/apps/` if you have an SD card.

**Quit the apps:** `ESC`, `` ` `` or `G0`.

### 2. Android app

**Download the APK** (v1.3):

➡️ [**BleBlocksMicroHydra.apk**](https://github.com/danodanotes/BleBlocksMicroHydra/raw/main/BleBlocksMicroHydra.apk)

Install on the phone (enable **Install unknown apps** if Android asks).

Also available in this repo under `releases/CardputerBleBlocks-v1.3.apk`.

To build from source:

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Current version: **1.3** (`versionCode 4`).

---

## Usage

### A) Send from Cardputer to phone

1. On the Cardputer run **ble_send**.
2. Choose **1 / ENT → Phone** (peripheral mode).
3. On the phone open **Cardputer BLE Blocks**, scan and connect to `Cardputer-ADV`.
4. On the Cardputer select the file and press **ENT**.
5. The phone receives the blocks, assembles them and saves the file.

### B) Send from phone to Cardputer

1. On the Cardputer run **ble_recv** (advertises as `Cardputer-ADV`).
2. On the phone:
   - Scan and connect.
   - Paste content or pick a file.
   - Adjust block size if needed (default ~400–500).
   - Press **Send**.
3. The Cardputer shows progress (`blk n/N`) and at the end asks for a name or saves automatically.

### C) Cardputer → Cardputer

1. On the receiver: **ble_recv**.
2. On the sender: **ble_send** → option **2 → Cardputer** (central mode).
3. The sender scans, connects to the receiver and sends.

---

## Protocol (on the wire)

Plain text over the **Nordic UART Service (NUS)**:

| UUID | Role |
|------|------|
| `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | NUS service |
| `6E400002-...` | RX (write) |
| `6E400003-...` | TX (notify) |

### Transfer format

**First block:**
```
##BLOCKS N
##TYPE py
##FILE my_app.py
##ID 1
...first chunk content...
##MORE
```

**Middle blocks:**
```
##CONTINUATION
##ID k
...content...
##MORE
```

**Last block:**
```
##CONTINUATION
##ID N
...final content...
```
*(no `##MORE`)*

### Important rules

1. **The receiver removes ALL lines starting with `##`** before saving.
2. The file is only considered complete when a block **without** `##MORE` arrives.
3. Markers are built in pieces in the Python source (`_P + "MORE"`) so transferring `ble_*.py` does not self-cut.
4. Recommended block size: **400–800 characters**. Larger values may cause RAM issues or timeouts on the Cardputer.
5. The sender waits a short GAP (~400 ms) between blocks. The receiver closes a block on the `##MORE` marker or on **~2.8 s idle**.

---

## Component details

### ble_send (Cardputer)

- On start chooses target:
  - **1 / ENT** → Phone (peripheral — phone connects)
  - **2** → Cardputer (central — connects to a `ble_recv`)
- Lists files from:
  - `/apps` and `/sd/apps` (one level)
  - App folders → shows folder name and sends `__init__.py` as `name.py`
  - Loose files on SD root
- Controls: `w/s` or `;/.` move, `ENT` send, `r` refresh.
- Internal chunk size: 400 characters.

### ble_recv (Cardputer)

- Advertises as `Cardputer-ADV`.
- Receives blocks, accumulates in `/apps/.xfer.tmp`.
- On completion:
  - Offers to edit the filename.
  - Saves to `/apps` or `/sd/apps` (avoids overwrite with `_2`, `_3`… suffixes).
- On-screen status: blocks received, bytes, saved name.
- Block-close idle: **2800 ms**.

### CardputerBleBlocks (Android v1.3)

- BLE scan for devices whose name contains “Cardputer”.
- Send text or files (file picker / share intent).
- Receive with persistent assembler.
- Strips **all** `##*` lines on receive.
- Only saves when the last block is complete.
- Configurable block size in the UI.
- Adaptive icon (BLE waves + blocks).

---

## Build the Android app

```bash
cd android   # or CardputerBleBlocks
./gradlew assembleDebug
```

Debug APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

Requirements:
- JDK 17
- Android SDK 34
- `minSdk 24` / `targetSdk 34`

Permissions used:
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+)
- `BLUETOOTH` + `ACCESS_FINE_LOCATION` (older versions)
- BLE required (`android.hardware.bluetooth_le`)

---

## Repository layout

```
├── README.md                 ← this file
├── docs/
│   ├── PROTOCOL.md           ← detailed protocol specification
│   └── USAGE.md              ← extended usage guide
├── firmware/
│   ├── ble_send.py           ← flat version
│   ├── ble_recv.py
│   ├── ble_send/             ← folder + icon version (recommended)
│   │   ├── __init__.py
│   │   └── icon.raw
│   └── ble_recv/
│       ├── __init__.py
│       └── icon.raw
├── releases/
│   └── CardputerBleBlocks-v1.3.apk   ← prebuilt APK
└── android/                  ← full Android Studio project
    ├── app/
    ├── build.gradle
    └── ...
```

### Publishing to the official MicroHydra-Apps catalog

A ready-to-submit Pull Request package is in `MicroHydra-Apps-PR/`  
(see `HOW_TO_PUBLISH.md` for a beginner-friendly step-by-step guide).

Target repo: https://github.com/echo-lalia/MicroHydra-Apps

---

## Known limitations

- Practical max file size: ~300–400 KB (depends on free RAM on the Cardputer).
- Very large blocks (> ~1500 characters) may fail due to memory or timeouts.
- Cardputer must run MicroHydra with Bluetooth support (ADV firmware).
- No encryption or authentication; link is standard Nordic UART.
- When transferring `ble_send` / `ble_recv` themselves, markers are split so the source does not self-cut.

---

## Version history

| Version | Changes |
|---------|---------|
| **1.3** | Strip **all** `##` lines on receive. Persistent assembler. Save only when complete. Adaptive icon (BLE waves + blocks). |
| 1.2     | Protocol with `##BLOCKS` / `##MORE`. Cardputer↔Cardputer support. |
| 1.0–1.1 | First stable releases. |

---

## License

Released for personal and educational use with M5Stack Cardputer / MicroHydra.  
Feel free to adapt and improve.

---

## Credits

- MicroHydra — firmware and app environment for Cardputer
- Nordic UART Service (NUS) — BLE profile used
- Developed and tested on **Cardputer ADV** + Android

---

*Documentation prepared for GitHub — September 2026*
