# Usage guide — Cardputer BLE Blocks

## Preparation

1. Flash the Cardputer ADV with **MicroHydra v2.6-preview** (CARDPUTER_ADV firmware).
2. Copy `ble_send` and `ble_recv` folders (or the flat `.py` files) to `/apps/` (or `/sd/apps/`).
3. Install the **Cardputer BLE Blocks** v1.3 APK on the phone.

---

## Use cases

### 1. Send a file from Cardputer to phone

```
Cardputer                          Phone
─────────                          ─────
ble_send  →  choose "1 Phone"       
advertises "Cardputer-ADV"  ←────  scan and connect
select file + ENT           ─────→ receive blocks
                                   save file
```

Detailed steps:

1. Open **ble_send** on the Cardputer.
2. Press **1** or **ENT** (Phone / peripheral mode).
3. On the phone: open the app → **Scan** → select `Cardputer-ADV` → Connect.
4. On the Cardputer: move with `w/s` or `;/.`, press **ENT** on the desired file.
5. The phone shows progress and finally saves the file (usually Downloads or the folder chosen by the system).

### 2. Send a file from phone to Cardputer

```
Phone                              Cardputer
─────                              ─────────
scan and connect           ─────→  ble_recv (advertising)
pick text/file + Send      ─────→  receive blocks
                                   ask name / save
```

1. Open **ble_recv** on the Cardputer (“BLE RECV” screen).
2. On the phone connect to `Cardputer-ADV`.
3. Paste code, type text or pick a file.
4. Adjust block size if you want (400–800 is safe).
5. Press **Send**.
6. On the Cardputer you will see `blk 1/N`, `blk 2/N`… When finished you can edit the name or accept the suggestion.

### 3. Cardputer ↔ Cardputer transfer

1. On the **receiver**: run **ble_recv**.
2. On the **sender**: run **ble_send** → choose **2** (Cardputer / central).
3. The sender scans, automatically connects to a device advertising a name containing “Cardputer” and sends the selected file.

---

## Cardputer controls

### ble_send

| Key         | Action                |
|-------------|-----------------------|
| `1` / `ENT` | Phone mode (start)    |
| `2`         | Cardputer mode        |
| `w` / `s` or `;` / `.` | Move selection |
| `ENT`       | Send file             |
| `r`         | Refresh list          |
| `ESC` / `` ` `` / `G0` | Quit       |

### ble_recv

| Key         | Action                |
|-------------|-----------------------|
| `ENT`       | Confirm name          |
| `ESC`       | Automatic name        |
| `BSPC`      | Delete character      |
| `ESC` / `` ` `` / `G0` | Quit       |

---

## Practical tips

- **Large files**: use 400-character blocks. If it fails, reduce block size.
- **MicroHydra apps**: when sending an app folder, `ble_send` shows the folder name and transmits `__init__.py` as `name.py`.
- **Clean restart**: if a transfer stalls, quit the app (`ESC`) and open it again. The temporary file on the Cardputer is cleaned up.
- **Filename**: the Cardputer sanitizes odd characters. Only letters, digits, dot, hyphen and underscore are kept.
- **SD**: if a card is present, `ble_recv` also tries to save under `/sd/apps`.

---

## Troubleshooting

| Problem | Possible cause / fix |
|---------|----------------------|
| `Cardputer-ADV` does not appear | Is it running ble_recv or ble_send Phone mode? Is phone Bluetooth on? |
| Disconnects mid-transfer | Reduce block size. Move devices closer. |
| Incomplete file | Receiver did not get the last block (no `##MORE`). Resend. |
| “error” on Cardputer screen | Check the detail message. May be invalid name or full disk. |
| Android app does not save | Check storage / notification permissions. |
| Transferring ble_*.py cuts itself | Normal on old versions. In v1.3 markers are split. |

---

## Where files are saved

### On the Cardputer (ble_recv)
- Preferred: `/apps/<name>`
- Alternative: `/sd/apps/<name>`
- Temporary during receive: `/apps/.xfer.tmp`

### On the phone
- System-dependent (usually Downloads or the folder chosen when sharing/saving).

---

*Usage guide — Cardputer BLE Blocks v1.3*
