# Cardputer BLE Blocks Protocol

File-transfer protocol specification used by:

- Android app **CardputerBleBlocks** v1.3
- `ble_send.py`
- `ble_recv.py`

---

## Transport layer

Uses the **Nordic UART Service (NUS)** over Bluetooth Low Energy.

| Element | UUID |
|---------|------|
| Service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX characteristic (client write) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX characteristic (notify to client) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |

- Cardputer advertising name: **`Cardputer-ADV`**
- Practical MTU / write size: ~20-byte notify chunks; GATT writes up to ~180 bytes in central mode.

---

## Application message format

All content is **UTF-8 text** (normalized to `\n`).

A file is split into **N blocks**. Each block is an independent text message.

### First block (k = 1)

```
##BLOCKS N
##TYPE <ext>
##FILE <safe_name>
##ID 1
<chunk 1 content>
##MORE
```

### Middle blocks (1 < k < N)

```
##CONTINUATION
##ID k
<chunk k content>
##MORE
```

### Last block (k = N)

```
##CONTINUATION
##ID N
<final chunk content>
```

**No `##MORE`.**

---

## Recognized markers

| Marker | Meaning |
|--------|---------|
| `##BLOCKS N` | Total number of blocks in the transfer |
| `##TYPE ext` | Extension / type (py, txt, json, graf…) |
| `##FILE name` | Suggested filename (sanitized) |
| `##ID k` | Current block number (1-based) |
| `##CONTINUATION` | Not the first block |
| `##MORE` | More blocks follow |
| `##END` | Optional / legacy (ignored) |
| `##FMT` / `##NAME` | Legacy aliases for TYPE / FILE |

Any line that **starts with `##`** is a protocol line and is **never** written to the final file.

---

## Assembly rules (receiver)

1. Accumulate each block body **after** stripping protocol lines.
2. Detect end of block by:
   - presence of the `##MORE` line, or
   - idle timeout (Cardputer: **2800 ms**, Android: **~2200 ms**).
3. Only when a block **without** `##MORE` arrives is the file considered **complete**.
4. At that point the accumulated body is saved under the indicated name (or a generated one).
5. On the Cardputer a temporary file `/apps/.xfer.tmp` is used, then moved to the final path.

### Filename sanitization

Only these characters are allowed:

```
a-z A-Z 0-9 . _ -
```

Practical max length: 48 characters.  
If the name is empty, a name like `recv_XX.ext` is generated.

---

## Recommended sizes

| Parameter | Recommended value | Notes |
|-----------|-------------------|-------|
| Block size (content) | 400–800 characters | Default in `ble_send`: 400 |
| Gap between blocks (sender) | ~400 ms | Avoids overloading the receiver |
| Close idle (ble_recv) | 2800 ms | |
| Close idle (Android) | 2200 ms | Must be > Cardputer GAP |
| GATT write (central) | ≤ 180 bytes | `WRITE_CHUNK` |

Oversized blocks may cause:

- MicroPython memory failures
- Timeouts or data loss
- BLE disconnections

---

## Safe marker construction in Python

So that `ble_send.py` and `ble_recv.py` can transfer themselves without self-destruction, markers are built in pieces:

```python
_P = "##"
ID_BLOCKS = _P + "BLOCKS"
ID_TYPE   = _P + "TYPE"
ID_FILE   = _P + "FILE"
ID_ID     = _P + "ID"
ID_CONT   = _P + "CONTINUATION"
ID_MORE   = _P + "MORE"
ID_END    = _P + "END"
```

The contiguous text `##MORE` therefore **never appears** in the source.

---

## Optional control messages

The Cardputer may send status notifications on TX:

- `OK ...`
- `ERR ...`

The Android app interprets them for progress / error display.

---

## Full example (2 blocks)

**Block 1:**
```
##BLOCKS 2
##TYPE py
##FILE hello.py
##ID 1
print("hello")
##MORE
```

**Block 2:**
```
##CONTINUATION
##ID 2
print("world")
```

**Resulting saved file:**
```
print("hello")
print("world")
```

---

## Compatibility

| Sender              | Receiver            | Supported |
|---------------------|---------------------|-----------|
| Android             | ble_recv            | Yes       |
| ble_send (Phone)    | Android             | Yes       |
| ble_send (Cardputer)| ble_recv            | Yes       |
| Android             | Android             | No (not implemented) |

---

*Protocol specification — Cardputer BLE Blocks v1.3*
