#!/usr/bin/env python3
"""
ClipSync Desktop Client
Syncs clipboard (text + image) over WebSocket.
Auto-falls back from LAN to cloud if local server unreachable.
"""

import time
import json
import uuid
import base64
import hashlib
import threading
import sys
import io
import platform

import pyperclip
import websocket

# ─────────────────────────────────────────────
#  CONFIG  (edit these three lines)
# ─────────────────────────────────────────────
ROOM_ID       = "my-room-123"
LOCAL_SERVER  = "ws://192.168.1.100:3000"   # LAN IP of the machine running server
CLOUD_SERVER  = "wss://your-app.onrender.com"  # your deployed cloud URL
# ─────────────────────────────────────────────

POLL_INTERVAL   = 0.5   # seconds between clipboard checks
RETRY_DELAY     = 5     # seconds before retrying connection
DEVICE_ID       = str(uuid.uuid4())
OS              = platform.system()

# Shared state (guarded by _lock)
_lock                 = threading.Lock()
_last_sent_hash       = None   # hash we just sent → skip echo
_last_received_hash   = None   # hash we just received → skip re-send
_ws                   = None   # current websocket connection


# ──────────────────────────────────────────────────────────────────────────────
#  HASH HELPERS
# ──────────────────────────────────────────────────────────────────────────────

def _hash(content: str) -> str:
    return hashlib.md5(content.encode()).hexdigest()


# ──────────────────────────────────────────────────────────────────────────────
#  CLIPBOARD  READ
# ──────────────────────────────────────────────────────────────────────────────

def _get_image_b64():
    """Return base64-encoded PNG of clipboard image, or None."""
    try:
        if OS in ("Windows", "Darwin"):
            from PIL import ImageGrab
            img = ImageGrab.grabclipboard()
            if img is None:
                return None
            buf = io.BytesIO()
            img.save(buf, format="PNG")
            return base64.b64encode(buf.getvalue()).decode()
        elif OS == "Linux":
            # xclip must be installed
            import subprocess
            result = subprocess.run(
                ["xclip", "-selection", "clipboard", "-t", "image/png", "-o"],
                capture_output=True, timeout=1
            )
            if result.returncode == 0 and result.stdout:
                return base64.b64encode(result.stdout).decode()
    except Exception:
        pass
    return None


def _get_text():
    try:
        return pyperclip.paste() or ""
    except Exception:
        return ""


# ──────────────────────────────────────────────────────────────────────────────
#  CLIPBOARD  WRITE
# ──────────────────────────────────────────────────────────────────────────────

def _set_text(text: str):
    try:
        pyperclip.copy(text)
    except Exception as e:
        print(f"[SET TEXT ERR] {e}")


def _set_image(b64: str):
    try:
        from PIL import Image
        data = base64.b64decode(b64)
        img  = Image.open(io.BytesIO(data))

        if OS == "Windows":
            import win32clipboard
            from io import BytesIO
            output = BytesIO()
            img.convert("RGB").save(output, "BMP")
            bmp_data = output.getvalue()[14:]
            win32clipboard.OpenClipboard()
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardData(win32clipboard.CF_DIB, bmp_data)
            win32clipboard.CloseClipboard()

        elif OS == "Darwin":
            import subprocess, tempfile, os
            tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
            img.save(tmp.name, "PNG")
            tmp.close()
            script = (
                f'set the clipboard to '
                f'(read (POSIX file "{tmp.name}") as «class PNGf»)'
            )
            subprocess.run(["osascript", "-e", script], check=False)
            os.unlink(tmp.name)

        elif OS == "Linux":
            import subprocess, tempfile, os
            tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
            img.save(tmp.name, "PNG")
            tmp.close()
            with open(tmp.name, "rb") as f:
                subprocess.run(
                    ["xclip", "-selection", "clipboard", "-t", "image/png"],
                    stdin=f, check=False
                )
            os.unlink(tmp.name)

    except Exception as e:
        print(f"[SET IMAGE ERR] {e}")


# ──────────────────────────────────────────────────────────────────────────────
#  WEBSOCKET  SEND
# ──────────────────────────────────────────────────────────────────────────────

def _send(data_type: str, content: str):
    global _ws
    with _lock:
        ws = _ws
    if ws is None:
        return
    try:
        payload = json.dumps({
            "type":      "clipboard",
            "device_id": DEVICE_ID,
            "room_id":   ROOM_ID,
            "data_type": data_type,
            "content":   content,
            "timestamp": time.time(),
        })
        ws.send(payload)
        label = f"{len(content)} chars" if data_type == "text" \
                else f"{len(content)*3//4//1024} KB"
        print(f"[SEND] {data_type}  {label}")
    except Exception as e:
        print(f"[SEND ERR] {e}")


# ──────────────────────────────────────────────────────────────────────────────
#  CLIPBOARD  POLLER  (runs in background thread)
# ──────────────────────────────────────────────────────────────────────────────

def _poll_loop():
    global _last_sent_hash, _last_received_hash
    while True:
        time.sleep(POLL_INTERVAL)
        try:
            # Try image first (takes priority)
            img_b64 = _get_image_b64()
            if img_b64:
                h = _hash(img_b64)
                with _lock:
                    skip = (h == _last_sent_hash or h == _last_received_hash)
                if not skip:
                    with _lock:
                        _last_sent_hash = h
                    _send("image", img_b64)
                continue

            # Plain text
            text = _get_text()
            if not text:
                continue
            h = _hash(text)
            with _lock:
                skip = (h == _last_sent_hash or h == _last_received_hash)
            if not skip:
                with _lock:
                    _last_sent_hash = h
                _send("text", text)

        except Exception as e:
            print(f"[POLL ERR] {e}")


# ──────────────────────────────────────────────────────────────────────────────
#  WEBSOCKET  CALLBACKS
# ──────────────────────────────────────────────────────────────────────────────

def _on_open(ws):
    global _ws
    with _lock:
        _ws = ws
    join = json.dumps({"type": "join", "room_id": ROOM_ID, "device_id": DEVICE_ID})
    ws.send(join)
    print(f"[WS] Connected  →  room={ROOM_ID}")


def _on_message(_ws, raw):
    global _last_received_hash, _last_sent_hash
    try:
        msg = json.loads(raw)

        if msg.get("type") == "joined":
            print(f"[WS] Room joined  peers={msg.get('peers', '?')}")
            return

        if msg.get("type") != "clipboard":
            return

        # Drop our own echoes
        if msg.get("device_id") == DEVICE_ID:
            return

        data_type = msg.get("data_type", "text")
        content   = msg.get("content", "")
        h         = _hash(content)

        with _lock:
            _last_received_hash = h
            _last_sent_hash     = h   # prevent re-broadcast

        if data_type == "text":
            _set_text(content)
            print(f"[RECV] text  {len(content)} chars")
        elif data_type == "image":
            _set_image(content)
            print(f"[RECV] image  {len(content)*3//4//1024} KB")

    except Exception as e:
        print(f"[MSG ERR] {e}")


def _on_error(_ws, error):
    print(f"[WS ERR] {error}")


def _on_close(_wsc, code, msg):
    global _ws
    with _lock:
        _ws = None
    print(f"[WS] Closed  code={code}")


# ──────────────────────────────────────────────────────────────────────────────
#  CONNECTION  MANAGER  (LAN → Cloud fallback)
# ──────────────────────────────────────────────────────────────────────────────

def _connect_loop():
    servers = [LOCAL_SERVER, CLOUD_SERVER]
    idx = 0
    while True:
        url = servers[idx % len(servers)]
        print(f"[WS] Trying {url} …")
        try:
            ws = websocket.WebSocketApp(
                url,
                on_open    = _on_open,
                on_message = _on_message,
                on_error   = _on_error,
                on_close   = _on_close,
            )
            ws.run_forever(ping_interval=20, ping_timeout=10)
        except Exception as e:
            print(f"[WS] Connection failed: {e}")

        # Try the other server next
        idx += 1
        print(f"[WS] Retry in {RETRY_DELAY}s …")
        time.sleep(RETRY_DELAY)


# ──────────────────────────────────────────────────────────────────────────────
#  ENTRY  POINT
# ──────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=" * 50)
    print("  ClipSync Desktop Client")
    print(f"  OS        : {OS}")
    print(f"  Device ID : {DEVICE_ID}")
    print(f"  Room      : {ROOM_ID}")
    print(f"  LAN       : {LOCAL_SERVER}")
    print(f"  Cloud     : {CLOUD_SERVER}")
    print("=" * 50)

    # Start clipboard poller
    t = threading.Thread(target=_poll_loop, daemon=True, name="ClipboardPoller")
    t.start()

    # Block on WebSocket connection (with fallback)
    _connect_loop()
