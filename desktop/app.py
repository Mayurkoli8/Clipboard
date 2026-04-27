#!/usr/bin/env python3
"""
ClipSync Desktop App — standalone GUI with system tray
Double-click to run. No terminal needed.
"""

import tkinter as tk
from tkinter import ttk, scrolledtext
import threading
import queue
import time
import json
import uuid
import base64
import hashlib
import platform
import io
import sys
import os

import pyperclip
import websocket

try:
    import pystray
    from PIL import Image, ImageDraw, ImageFont
    HAS_TRAY = True
except ImportError:
    HAS_TRAY = False

try:
    from PIL import ImageGrab
    HAS_IMAGEGRAB = True
except ImportError:
    HAS_IMAGEGRAB = False

# ─────────────────────────────────────────────
#  DEFAULT CONFIG  (user can edit in the GUI)
# ─────────────────────────────────────────────
DEFAULT_ROOM_ID      = "my-room-123"
DEFAULT_LOCAL_SERVER = "ws://192.168.1.100:3000"
DEFAULT_CLOUD_SERVER = "wss://your-app.onrender.com"
# ─────────────────────────────────────────────

DEVICE_ID     = str(uuid.uuid4())
OS            = platform.system()
POLL_INTERVAL = 0.5
RETRY_DELAY   = 5


def _hash(content: str) -> str:
    return hashlib.md5(content.encode()).hexdigest()


def _get_image_b64():
    try:
        if OS in ("Windows", "Darwin") and HAS_IMAGEGRAB:
            img = ImageGrab.grabclipboard()
            if img is None:
                return None
            buf = io.BytesIO()
            img.save(buf, format="PNG")
            return base64.b64encode(buf.getvalue()).decode()
        elif OS == "Linux":
            import subprocess
            r = subprocess.run(
                ["xclip", "-selection", "clipboard", "-t", "image/png", "-o"],
                capture_output=True, timeout=1
            )
            if r.returncode == 0 and r.stdout:
                return base64.b64encode(r.stdout).decode()
    except Exception:
        pass
    return None


def _set_text(text: str):
    try:
        pyperclip.copy(text)
    except Exception:
        pass


def _set_image(b64: str):
    try:
        from PIL import Image
        data = base64.b64decode(b64)
        img  = Image.open(io.BytesIO(data))
        if OS == "Windows":
            import win32clipboard
            output = io.BytesIO()
            img.convert("RGB").save(output, "BMP")
            bmp_data = output.getvalue()[14:]
            win32clipboard.OpenClipboard()
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardData(win32clipboard.CF_DIB, bmp_data)
            win32clipboard.CloseClipboard()
        elif OS == "Darwin":
            import subprocess, tempfile
            tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
            img.save(tmp.name, "PNG"); tmp.close()
            subprocess.run(["osascript", "-e",
                f'set the clipboard to (read (POSIX file "{tmp.name}") as «class PNGf»)'])
            os.unlink(tmp.name)
        elif OS == "Linux":
            import subprocess, tempfile
            tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
            img.save(tmp.name, "PNG"); tmp.close()
            with open(tmp.name, "rb") as f:
                subprocess.run(["xclip", "-selection", "clipboard", "-t", "image/png"], stdin=f)
            os.unlink(tmp.name)
    except Exception as e:
        pass


# ──────────────────────────────────────────────────────────────────────────────
#  SYNC ENGINE  (runs in background threads, communicates via queue)
# ──────────────────────────────────────────────────────────────────────────────

class SyncEngine:
    def __init__(self, log_queue):
        self.q            = log_queue
        self.room_id      = DEFAULT_ROOM_ID
        self.local_server = DEFAULT_LOCAL_SERVER
        self.cloud_server = DEFAULT_CLOUD_SERVER
        self._ws          = None
        self._running     = False
        self._connected   = False
        self._last_sent_h = ""
        self._last_recv_h = ""
        self._lock        = threading.Lock()

    def start(self, room_id, local_server, cloud_server):
        self.room_id      = room_id
        self.local_server = local_server
        self.cloud_server = cloud_server
        self._running     = True
        threading.Thread(target=self._connect_loop, daemon=True).start()
        threading.Thread(target=self._poll_loop,    daemon=True).start()

    def stop(self):
        self._running = False
        if self._ws:
            try: self._ws.close()
            except: pass
        self._ws        = None
        self._connected = False

    def log(self, msg):
        self.q.put(msg)

    # ── WebSocket ──────────────────────────────────────────────────────────

    def _connect_loop(self):
        servers = [self.local_server, self.cloud_server]
        idx = 0
        while self._running:
            url = servers[idx % len(servers)]
            self.log(f"Connecting → {url}")
            self.q.put(("status", "connecting", url))
            try:
                ws = websocket.WebSocketApp(
                    url,
                    on_open    = self._on_open,
                    on_message = self._on_message,
                    on_error   = self._on_error,
                    on_close   = self._on_close,
                )
                ws.run_forever(ping_interval=20, ping_timeout=10)
            except Exception as e:
                self.log(f"Error: {e}")
            if not self._running:
                break
            idx += 1
            self.log(f"Retry in {RETRY_DELAY}s…")
            self.q.put(("status", "disconnected", ""))
            time.sleep(RETRY_DELAY)

    def _on_open(self, ws):
        with self._lock:
            self._ws        = ws
            self._connected = True
        join = json.dumps({"type": "join", "room_id": self.room_id, "device_id": DEVICE_ID})
        ws.send(join)
        self.log(f"✅ Connected  room={self.room_id}")
        self.q.put(("status", "connected", self.room_id))

    def _on_message(self, ws, raw):
        try:
            msg = json.loads(raw)
            if msg.get("type") == "joined":
                self.log(f"Room joined  peers={msg.get('peers', '?')}")
                return
            if msg.get("type") != "clipboard":
                return
            if msg.get("device_id") == DEVICE_ID:
                return
            dtype   = msg.get("data_type", "text")
            content = msg.get("content", "")
            h       = _hash(content)
            with self._lock:
                self._last_recv_h = h
                self._last_sent_h = h
            if dtype == "text":
                _set_text(content)
                preview = content[:40].replace("\n", " ")
                self.log(f"📥 Received text: \"{preview}{'…' if len(content)>40 else ''}\"")
            elif dtype == "image":
                _set_image(content)
                self.log(f"📥 Received image ({len(content)*3//4//1024} KB)")
        except Exception as e:
            self.log(f"Message error: {e}")

    def _on_error(self, ws, error):
        self.log(f"WS error: {error}")

    def _on_close(self, ws, code, msg):
        with self._lock:
            self._ws        = None
            self._connected = False
        self.log(f"Disconnected (code={code})")
        self.q.put(("status", "disconnected", ""))

    # ── Clipboard Poller ───────────────────────────────────────────────────

    def _poll_loop(self):
        while self._running:
            time.sleep(POLL_INTERVAL)
            with self._lock:
                if not self._connected:
                    continue
            try:
                img = _get_image_b64()
                if img:
                    h = _hash(img)
                    with self._lock:
                        skip = (h == self._last_sent_h or h == self._last_recv_h)
                    if not skip:
                        with self._lock:
                            self._last_sent_h = h
                        self._send("image", img)
                    continue

                text = pyperclip.paste() or ""
                if not text:
                    continue
                h = _hash(text)
                with self._lock:
                    skip = (h == self._last_sent_h or h == self._last_recv_h)
                if not skip:
                    with self._lock:
                        self._last_sent_h = h
                    self._send("text", text)
            except Exception:
                pass

    def _send(self, dtype, content):
        with self._lock:
            ws = self._ws
        if not ws:
            return
        try:
            payload = json.dumps({
                "type":      "clipboard",
                "device_id": DEVICE_ID,
                "room_id":   self.room_id,
                "data_type": dtype,
                "content":   content,
                "timestamp": time.time(),
            })
            ws.send(payload)
            if dtype == "text":
                preview = content[:40].replace("\n", " ")
                self.log(f"📤 Sent text: \"{preview}{'…' if len(content)>40 else ''}\"")
            else:
                self.log(f"📤 Sent image ({len(content)*3//4//1024} KB)")
        except Exception as e:
            self.log(f"Send error: {e}")


# ──────────────────────────────────────────────────────────────────────────────
#  TRAY ICON
# ──────────────────────────────────────────────────────────────────────────────

def _make_tray_icon(running=False):
    size = 64
    img  = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d    = ImageDraw.Draw(img)
    color = "#00C896" if running else "#888888"
    d.ellipse([4, 4, size-4, size-4], fill=color)
    d.rectangle([16, 24, 48, 32], fill="white")
    d.rectangle([16, 38, 40, 46], fill="white")
    return img


# ──────────────────────────────────────────────────────────────────────────────
#  TKINTER  GUI
# ──────────────────────────────────────────────────────────────────────────────

class App:
    BG        = "#1A1A2E"
    CARD      = "#16213E"
    ACCENT    = "#00C896"
    TEXT      = "#FFFFFF"
    SUBTEXT   = "#A0A0B0"
    DANGER    = "#E94560"
    FONT_MAIN = ("Segoe UI", 10)
    FONT_BOLD = ("Segoe UI", 10, "bold")
    FONT_LOG  = ("Consolas", 9)

    def __init__(self):
        self.q      = queue.Queue()
        self.engine = SyncEngine(self.q)
        self.running = False
        self.tray    = None

        self.root = tk.Tk()
        self.root.title("ClipSync")
        self.root.geometry("480x560")
        self.root.resizable(False, False)
        self.root.configure(bg=self.BG)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close_window)

        self._build_ui()
        self.root.after(100, self._poll_queue)

        if HAS_TRAY:
            self._start_tray()

    # ── UI Build ───────────────────────────────────────────────────────────

    def _build_ui(self):
        root = self.root

        # Header
        hdr = tk.Frame(root, bg=self.BG)
        hdr.pack(fill="x", padx=24, pady=(24, 0))
        tk.Label(hdr, text="📋  ClipSync", bg=self.BG, fg=self.TEXT,
                 font=("Segoe UI", 20, "bold")).pack(side="left")

        self.dot = tk.Label(hdr, text="●", bg=self.BG, fg=self.SUBTEXT,
                            font=("Segoe UI", 18))
        self.dot.pack(side="right")

        tk.Label(root, text="Cross-device clipboard sync", bg=self.BG,
                 fg=self.SUBTEXT, font=self.FONT_MAIN).pack(pady=(4, 16))

        # Config card
        card = tk.Frame(root, bg=self.CARD, padx=20, pady=16)
        card.pack(fill="x", padx=24)

        self.var_room   = self._labeled_entry(card, "Room ID",          DEFAULT_ROOM_ID)
        self.var_local  = self._labeled_entry(card, "LAN Server (ws://)", DEFAULT_LOCAL_SERVER)
        self.var_cloud  = self._labeled_entry(card, "Cloud Server (wss://)", DEFAULT_CLOUD_SERVER)

        # Buttons
        btns = tk.Frame(root, bg=self.BG)
        btns.pack(fill="x", padx=24, pady=12)

        self.btn = tk.Button(btns, text="▶  Start Sync",
                             bg=self.ACCENT, fg="#000000",
                             font=self.FONT_BOLD,
                             relief="flat", cursor="hand2",
                             padx=20, pady=10,
                             command=self._toggle)
        self.btn.pack(fill="x")

        # Status bar
        self.status_var = tk.StringVar(value="Stopped")
        tk.Label(root, textvariable=self.status_var,
                 bg=self.BG, fg=self.SUBTEXT,
                 font=self.FONT_MAIN).pack(pady=(0, 8))

        # Log
        tk.Label(root, text="Activity Log", bg=self.BG, fg=self.SUBTEXT,
                 font=self.FONT_MAIN).pack(anchor="w", padx=24)

        self.log_box = scrolledtext.ScrolledText(
            root, height=10, bg="#0D0D1A", fg="#CCCCCC",
            font=self.FONT_LOG, relief="flat",
            insertbackground="white", state="disabled"
        )
        self.log_box.pack(fill="both", expand=True, padx=24, pady=(4, 16))

        # Footer
        tk.Label(root, text=f"Device ID: {DEVICE_ID[:16]}…",
                 bg=self.BG, fg="#444455", font=("Consolas", 8)).pack(pady=(0, 8))

    def _labeled_entry(self, parent, label, default):
        tk.Label(parent, text=label, bg=self.CARD, fg=self.SUBTEXT,
                 font=self.FONT_MAIN).pack(anchor="w", pady=(8, 2))
        var = tk.StringVar(value=default)
        e = tk.Entry(parent, textvariable=var, bg="#0D0D1A", fg=self.TEXT,
                     font=self.FONT_MAIN, relief="flat",
                     insertbackground="white")
        e.pack(fill="x", ipady=6)
        return var

    # ── Toggle ─────────────────────────────────────────────────────────────

    def _toggle(self):
        if self.running:
            self.engine.stop()
            self.running = False
            self.btn.config(text="▶  Start Sync", bg=self.ACCENT, fg="#000000")
            self.status_var.set("Stopped")
            self.dot.config(fg=self.SUBTEXT)
            self._log("■ Sync stopped")
        else:
            room   = self.var_room.get().strip()
            local  = self.var_local.get().strip()
            cloud  = self.var_cloud.get().strip()
            if not room:
                self._log("⚠ Enter a Room ID first")
                return
            self.engine.start(room, local, cloud)
            self.running = True
            self.btn.config(text="■  Stop Sync", bg=self.DANGER, fg=self.TEXT)
            self.status_var.set(f"Connecting…  room: {room}")
            self._log(f"▶ Starting sync  room={room}")

    # ── Queue poller ───────────────────────────────────────────────────────

    def _poll_queue(self):
        try:
            while True:
                item = self.q.get_nowait()
                if isinstance(item, tuple):
                    _, state, extra = item
                    if state == "connected":
                        self.dot.config(fg=self.ACCENT)
                        self.status_var.set(f"Connected  ·  room: {extra}")
                        if self.tray:
                            self.tray.icon = _make_tray_icon(True)
                    elif state == "disconnected":
                        self.dot.config(fg="#E9A820")
                        self.status_var.set("Reconnecting…")
                        if self.tray:
                            self.tray.icon = _make_tray_icon(False)
                    elif state == "connecting":
                        self.dot.config(fg="#E9A820")
                        self.status_var.set(f"Trying {extra}…")
                else:
                    self._log(item)
        except queue.Empty:
            pass
        self.root.after(150, self._poll_queue)

    def _log(self, msg):
        ts = time.strftime("%H:%M:%S")
        self.log_box.config(state="normal")
        self.log_box.insert("end", f"[{ts}] {msg}\n")
        self.log_box.see("end")
        self.log_box.config(state="disabled")

    # ── Tray ───────────────────────────────────────────────────────────────

    def _start_tray(self):
        menu = pystray.Menu(
            pystray.MenuItem("Show ClipSync", self._show_window, default=True),
            pystray.MenuItem("Quit",          self._quit_app),
        )
        icon = _make_tray_icon(False)
        self.tray = pystray.Icon("ClipSync", icon, "ClipSync", menu)
        t = threading.Thread(target=self.tray.run, daemon=True)
        t.start()

    def _show_window(self):
        self.root.after(0, self.root.deiconify)
        self.root.after(0, self.root.lift)

    def _on_close_window(self):
        if HAS_TRAY:
            self.root.withdraw()   # minimize to tray
        else:
            self._quit_app()

    def _quit_app(self):
        self.engine.stop()
        if self.tray:
            self.tray.stop()
        self.root.destroy()

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    App().run()