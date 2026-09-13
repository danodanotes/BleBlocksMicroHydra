"""ble_send — send a Cardputer file over BLE.

Copy to /apps/ble_send.py
Quit: ESC ` G0

On start choose target:
  1 / ENT  → Phone   (peripheral, phone connects)
  2        → Cardputer (central, we connect to ble_recv)

Keys (file list): w/s or ;/. move   ENT send   r refresh
"""
import bluetooth
import machine
import os
import time
from micropython import const
from lib.display import Display
from lib.userinput import UserInput
from lib.hydra.config import Config

# IRQ
_IRQ_CENTRAL_CONNECT = const(1)
_IRQ_CENTRAL_DISCONNECT = const(2)
_IRQ_GATTS_WRITE = const(3)
_IRQ_SCAN_RESULT = const(5)
_IRQ_SCAN_DONE = const(6)
_IRQ_PERIPHERAL_CONNECT = const(7)
_IRQ_PERIPHERAL_DISCONNECT = const(8)
_IRQ_GATTC_SERVICE_RESULT = const(9)
_IRQ_GATTC_SERVICE_DONE = const(10)
_IRQ_GATTC_CHARACTERISTIC_RESULT = const(11)
_IRQ_GATTC_CHARACTERISTIC_DONE = const(12)
_IRQ_GATTC_WRITE_DONE = const(17)

_FLAG_READ = const(0x0002)
_FLAG_WRITE_NO_RESP = const(0x0004)
_FLAG_WRITE = const(0x0008)
_FLAG_NOTIFY = const(0x0010)

_NUS = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_NUS_TX = (
    bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
    _FLAG_READ | _FLAG_NOTIFY,
)
_NUS_RX = (
    bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
    _FLAG_WRITE | _FLAG_WRITE_NO_RESP,
)
_NUS_SVC = (_NUS, (_NUS_TX, _NUS_RX))
_UART_RX_UUID = bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_TX_UUID = bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

BLE_NAME = "Cardputer-ADV"
CHUNK = 400
NOTIFY = 20
GAP_MS = 400
WRITE_CHUNK = 180  # safe for gattc write

display = Display()
kb = UserInput()
cfg = Config()
W = 240
BG = cfg.palette[2]
FG = cfg.palette[8]
DIM = cfg.palette[6]
ACC = cfg.palette[4]

# Each entry: (display_name, real_path, send_filename)
files = []
sel = 0


def clip(s, n=36):
    s = str(s)
    if len(s) <= n:
        return s
    return s[: n - 1] + "~"


def adv_payload(name):
    raw = name.encode()[:18]
    return bytes((2, 1, 6, len(raw) + 1, 9)) + raw


def _decode_name(adv_data):
    """Minimal AD parser for Complete/Short Local Name (0x09 / 0x08)."""
    i = 0
    data = bytes(adv_data)
    while i + 1 < len(data):
        length = data[i]
        if length == 0:
            break
        if i + 1 + length > len(data):
            break
        typ = data[i + 1]
        if typ in (0x08, 0x09) and length >= 2:
            try:
                return data[i + 2 : i + 1 + length].decode("utf-8", "ignore")
            except Exception:
                return None
        i += 1 + length
    return None


def is_dir(path):
    try:
        return bool(os.stat(path)[0] & 0x4000)
    except OSError:
        return False


def list_files():
    """Top-level files + one level into app folders.
    Folder apps (with __init__.py) appear as the folder name.
    """
    out = []
    seen = set()
    roots = ("/apps", "/sd/apps")
    for folder in roots:
        try:
            names = os.listdir(folder)
        except OSError:
            continue
        for n in sorted(names):
            if n.startswith("."):
                continue
            path = folder + "/" + n
            if is_dir(path):
                init = path + "/__init__.py"
                try:
                    os.stat(init)
                    # App folder → show folder name, send __init__ as name.py
                    key = n.lower()
                    if key not in seen:
                        seen.add(key)
                        out.append((n, init, n + ".py"))
                except OSError:
                    # Other files inside the folder
                    try:
                        for sub in sorted(os.listdir(path)):
                            if sub.startswith(".") or sub == "icon.raw":
                                continue
                            sp = path + "/" + sub
                            if is_dir(sp):
                                continue
                            low = sub.lower()
                            if low.endswith(
                                (".py", ".txt", ".csv", ".json", ".graf", ".md")
                            ):
                                disp = n + "/" + sub
                                key = disp.lower()
                                if key not in seen:
                                    seen.add(key)
                                    out.append((disp, sp, sub))
                    except OSError:
                        pass
            else:
                low = n.lower()
                if low.endswith((".py", ".txt", ".csv", ".json", ".graf", ".md")):
                    key = n.lower()
                    if key not in seen:
                        seen.add(key)
                        out.append((n, path, n))
    # Loose files on SD root
    try:
        for n in sorted(os.listdir("/sd")):
            if n.startswith(".") or n == "apps":
                continue
            path = "/sd/" + n
            if is_dir(path):
                continue
            low = n.lower()
            if low.endswith((".py", ".txt", ".csv", ".json", ".graf", ".md")):
                key = "sd:" + n.lower()
                if key not in seen:
                    seen.add(key)
                    out.append(("sd/" + n, path, n))
    except OSError:
        pass
    return out


def file_type(fname):
    base = fname.replace("\\", "/").split("/")[-1]
    if "." in base:
        return base.rsplit(".", 1)[1].lower()
    return "txt"


# Protocol markers built in pieces so the contiguous text never appears
# in this source file (otherwise transferring this .py would self-cut).
_P = "##"
ID_BLOCKS = _P + "BLOCKS"
ID_TYPE = _P + "TYPE"
ID_FILE = _P + "FILE"
ID_ID = _P + "ID"
ID_CONT = _P + "CONTINUATION"
ID_MORE = _P + "MORE"
ID_END = _P + "END"


def split_blocks(text, send_name):
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if text and not text.endswith("\n"):
        text += "\n"
    chunks = []
    i = 0
    while i < len(text):
        n = min(CHUNK, len(text) - i)
        if i + n < len(text):
            cut = text.rfind("\n", i, i + n)
            if cut > i:
                n = cut - i + 1
            else:
                nxt = text.find("\n", i + n)
                if nxt >= 0:
                    n = nxt - i + 1
                else:
                    n = len(text) - i
        chunks.append(text[i : i + n])
        i += n
    if not chunks:
        chunks = [text]
    N = len(chunks)
    base = send_name.replace("\\", "/").split("/")[-1]
    typ = file_type(send_name)
    blocks = []
    k = 0
    while k < N:
        if k == 0:
            head = "%s %d\n%s %s\n%s %s\n%s 1\n" % (
                ID_BLOCKS, N, ID_TYPE, typ, ID_FILE, base, ID_ID
            )
        else:
            head = "%s\n%s %d\n" % (ID_CONT, ID_ID, k + 1)
        tail = ID_MORE + "\n" if k < N - 1 else ""
        blocks.append(head + chunks[k] + tail)
        k += 1
    return blocks


# ---------- Peripheral (Phone) ----------
class BlePeripheral:
    def __init__(self, name=BLE_NAME):
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        try:
            self.ble.config(gap_name=name)
        except Exception:
            pass
        self.ble.irq(self._irq)
        handles = self.ble.gatts_register_services((_NUS_SVC,))
        self._tx, self._rx = handles[0]
        self.conn = None
        self.connected = False
        self._adv = adv_payload(name)
        self.advertise()

    def _irq(self, event, data):
        if event == _IRQ_CENTRAL_CONNECT:
            self.conn = data[0]
            self.connected = True
        elif event == _IRQ_CENTRAL_DISCONNECT:
            self.conn = None
            self.connected = False
            self.advertise()
        elif event == _IRQ_GATTS_WRITE:
            try:
                self.ble.gatts_read(self._rx)
            except Exception:
                pass

    def advertise(self):
        try:
            self.ble.gap_advertise(100000, adv_data=self._adv)
        except Exception:
            try:
                self.ble.gap_advertise(None)
                self.ble.gap_advertise(100000, adv_data=self._adv)
            except Exception:
                pass

    def say(self, msg):
        if self.conn is None:
            return False
        if isinstance(msg, str):
            msg = msg.encode()
        i = 0
        while i < len(msg):
            try:
                self.ble.gatts_notify(self.conn, self._tx, msg[i : i + NOTIFY])
            except Exception:
                return False
            i += NOTIFY
            time.sleep_ms(30)
        return True

    def stop(self):
        try:
            self.ble.active(False)
        except Exception:
            pass


# ---------- Central (Cardputer → Cardputer) ----------
class BleCentral:
    def __init__(self):
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        self.ble.irq(self._irq)
        self._reset()

    def _reset(self):
        self._addr_type = None
        self._addr = None
        self._name = None
        self._conn = None
        self._rx_handle = None
        self._tx_handle = None
        self._svc_start = None
        self._svc_end = None
        self._scan_done = False
        self._found = False
        self.connected = False
        self._ready = False

    def _irq(self, event, data):
        if event == _IRQ_SCAN_RESULT:
            addr_type, addr, adv_type, rssi, adv_data = data
            if adv_type not in (0x00, 0x01):
                return
            name = _decode_name(adv_data)
            if name and ("Cardputer" in name or "cardputer" in name.lower()):
                self._addr_type = addr_type
                self._addr = bytes(addr)
                self._name = name
                self._found = True
                try:
                    self.ble.gap_scan(None)
                except Exception:
                    pass
        elif event == _IRQ_SCAN_DONE:
            self._scan_done = True
        elif event == _IRQ_PERIPHERAL_CONNECT:
            conn, addr_type, addr = data
            if self._addr and addr == self._addr:
                self._conn = conn
                self.connected = True
                try:
                    self.ble.gattc_discover_services(self._conn)
                except Exception:
                    pass
        elif event == _IRQ_PERIPHERAL_DISCONNECT:
            conn, _, _ = data
            if conn == self._conn:
                self._reset()
        elif event == _IRQ_GATTC_SERVICE_RESULT:
            conn, start, end, uuid = data
            if conn == self._conn and uuid == _NUS:
                self._svc_start = start
                self._svc_end = end
        elif event == _IRQ_GATTC_SERVICE_DONE:
            if self._svc_start is not None and self._svc_end is not None:
                try:
                    self.ble.gattc_discover_characteristics(
                        self._conn, self._svc_start, self._svc_end
                    )
                except Exception:
                    pass
        elif event == _IRQ_GATTC_CHARACTERISTIC_RESULT:
            conn, def_h, value_h, props, uuid = data
            if conn == self._conn:
                if uuid == _UART_RX_UUID:
                    self._rx_handle = value_h
                elif uuid == _UART_TX_UUID:
                    self._tx_handle = value_h
        elif event == _IRQ_GATTC_CHARACTERISTIC_DONE:
            if self._rx_handle is not None:
                self._ready = True
                self.connected = True

    def scan(self, timeout_ms=5000):
        self._reset()
        self._scan_done = False
        self._found = False
        try:
            self.ble.gap_scan(timeout_ms, 30000, 30000)
        except Exception:
            return False
        t0 = time.ticks_ms()
        while not self._scan_done:
            if time.ticks_diff(time.ticks_ms(), t0) > timeout_ms + 500:
                break
            time.sleep_ms(50)
        return self._found

    def connect(self, timeout_ms=8000):
        if not self._addr:
            return False
        try:
            self.ble.gap_connect(self._addr_type, self._addr)
        except Exception:
            return False
        t0 = time.ticks_ms()
        while not self._ready:
            if time.ticks_diff(time.ticks_ms(), t0) > timeout_ms:
                return False
            time.sleep_ms(50)
        return self._ready

    def say(self, msg):
        if not self._ready or self._conn is None or self._rx_handle is None:
            return False
        if isinstance(msg, str):
            msg = msg.encode()
        i = 0
        while i < len(msg):
            piece = msg[i : i + WRITE_CHUNK]
            try:
                self.ble.gattc_write(self._conn, self._rx_handle, piece, 0)
            except Exception:
                return False
            i += WRITE_CHUNK
            time.sleep_ms(40)
        return True

    def stop(self):
        try:
            if self._conn is not None:
                self.ble.gap_disconnect(self._conn)
        except Exception:
            pass
        try:
            self.ble.active(False)
        except Exception:
            pass
        self._reset()


def draw_mode_select(sel_mode):
    display.fill(BG)
    display.rect(0, 0, W, 16, ACC, fill=True)
    display.text("BLE SEND", 6, 2, BG)
    display.text("ESC", 210, 2, BG)
    display.text("Send to:", 8, 28, FG)
    items = ("1  Phone (mobile app)", "2  Cardputer (ble_recv)")
    y = 50
    for i, t in enumerate(items):
        mark = ">" if i == sel_mode else " "
        col = ACC if i == sel_mode else FG
        display.text(mark + " " + t, 6, y, col)
        y += 16
    display.text("w/s move  ENT choose", 6, 110, DIM)
    display.show()


def draw(status, detail, mode_label=""):
    display.fill(BG)
    display.rect(0, 0, W, 16, ACC, fill=True)
    display.text("BLE SEND", 6, 2, BG)
    display.text("ESC", 210, 2, BG)
    display.text(clip(status, 38), 4, 20, FG)
    y = 34
    if not files:
        display.text("no files found", 4, y, DIM)
    else:
        start = 0
        if sel > 3:
            start = sel - 3
        i = start
        while i < len(files) and y < 110:
            mark = ">" if i == sel else " "
            name = files[i][0]
            display.text(clip(mark + name, 38), 4, y, ACC if i == sel else FG)
            y += 12
            i += 1
    display.text("w/s ;/. ENT send  r list", 4, 110, DIM)
    display.text(clip(detail, 38), 4, 122, ACC)
    display.show()


# ----- mode select -----
mode = None  # "phone" | "cardputer"
sel_mode = 0
draw_mode_select(sel_mode)
while mode is None:
    keys = kb.get_new_keys()
    for k in keys:
        if k in ("ESC", "`", "G0"):
            machine.reset()
        if k in (";", ",", "UP", "w", "W", "LEFT", "-", "1"):
            sel_mode = 0
        if k in (".", "/", "DOWN", "s", "S", "RIGHT", "=", "2"):
            sel_mode = 1
        if k in ("ENT", "\n", "SPC"):
            mode = "phone" if sel_mode == 0 else "cardputer"
    draw_mode_select(sel_mode)
    time.sleep_ms(40)

# ----- init BLE for chosen mode -----
uart = None
status = "starting BT..."
detail = ""
try:
    if mode == "phone":
        uart = BlePeripheral()
        status = "visible: " + BLE_NAME
        detail = "open phone app -> Recibir"
    else:
        uart = BleCentral()
        status = "scan Cardputer..."
        detail = "other must run ble_recv"
        draw(status, detail)
        found = uart.scan(6000)
        if not found:
            status = "no Cardputer found"
            detail = "r to rescan  ESC quit"
        else:
            status = "found: " + clip(uart._name or "?", 20)
            detail = "connecting..."
            draw(status, detail)
            if uart.connect(10000):
                status = "connected to peer"
                detail = "ENT sends file"
            else:
                status = "connect failed"
                detail = "r rescan  ESC quit"
except Exception as e:
    status = "BT error"
    detail = clip(e)

files = list_files()
sel = 0
sending = False
if not detail:
    detail = "%d files" % len(files)
draw(status, detail)

while True:
    keys = kb.get_new_keys()
    for k in keys:
        if k in ("ESC", "`", "G0"):
            if uart:
                uart.stop()
            machine.reset()
        if sending:
            continue
        if k in ("r", "R"):
            if mode == "cardputer" and uart and not getattr(uart, "connected", False):
                status = "scan Cardputer..."
                detail = "searching..."
                draw(status, detail)
                found = uart.scan(6000)
                if found and uart.connect(10000):
                    status = "connected to peer"
                    detail = "ENT sends"
                else:
                    status = "no peer / failed"
                    detail = "r again"
            files = list_files()
            if sel >= len(files):
                sel = 0
            detail = "list %d" % len(files)
        if k in (";", ",", "UP", "w", "W", "LEFT", "-") and files:
            sel = (sel - 1) % len(files)
            detail = files[sel][0]
        if k in (".", "/", "DOWN", "s", "S", "RIGHT", "=") and files:
            sel = (sel + 1) % len(files)
            detail = files[sel][0]
        if k in ("ENT", "\n", "SPC") and files:
            ready = False
            if mode == "phone":
                ready = uart and uart.connected
                if not ready:
                    detail = "wait for phone"
            else:
                ready = uart and uart.connected and getattr(uart, "_ready", False)
                if not ready:
                    detail = "not connected (r)"
            if ready:
                sending = True
                disp, path, send_name = files[sel]
                try:
                    f = open(path, "r")
                    body = f.read()
                    f.close()
                    blocks = split_blocks(body, send_name)
                    ok = True
                    i = 0
                    while i < len(blocks):
                        detail = "blk %d/%d" % (i + 1, len(blocks))
                        draw(status, detail)
                        if not uart.say(blocks[i]):
                            ok = False
                            break
                        time.sleep_ms(GAP_MS)
                        i += 1
                    if ok:
                        detail = "sent " + disp
                    else:
                        detail = "send failed"
                except Exception as e:
                    detail = clip(e)
                sending = False
    if mode == "phone" and uart:
        if uart.connected:
            if status.startswith("visible"):
                status = "connected: ENT sends"
        else:
            if status.startswith("connected"):
                status = "visible: " + BLE_NAME
    draw(status, detail)
    time.sleep_ms(40)
