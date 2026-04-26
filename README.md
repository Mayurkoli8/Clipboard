# ClipSync — Cross-Device Clipboard Sync

Copy on your phone → paste on your laptop. Copy on your laptop → paste on your phone.
Works on **LAN** (same WiFi) with automatic **Cloud fallback** (different networks).

---

## Project Structure

```
clipsync/
├── server/          Node.js WebSocket server (LAN + Cloud)
├── desktop/         Python client (Windows / macOS / Linux)
└── android/         Android Studio project (Kotlin)
```

---

## Step 1 — Configuration (do this first)

### Find your laptop's LAN IP

| OS      | Command                          |
|---------|----------------------------------|
| Windows | `ipconfig` → "IPv4 Address"     |
| macOS   | `ifconfig en0 \| grep inet`      |
| Linux   | `ip addr show` or `hostname -I` |

Example: `192.168.1.42`

### Set the same Room ID and URLs in all three clients:

| File                               | Variable        | Example value                       |
|------------------------------------|-----------------|-------------------------------------|
| `desktop/client.py`                | `ROOM_ID`       | `my-room-123`                       |
| `desktop/client.py`                | `LOCAL_SERVER`  | `ws://192.168.1.42:3000`            |
| `desktop/client.py`                | `CLOUD_SERVER`  | `wss://your-app.onrender.com`       |
| `android/.../Config.kt`            | `ROOM_ID`       | `my-room-123`                       |
| `android/.../Config.kt`            | `LOCAL_SERVER`  | `ws://192.168.1.42:3000`            |
| `android/.../Config.kt`            | `CLOUD_SERVER`  | `wss://your-app.onrender.com`       |

---

## Step 2 — Run the Server Locally (LAN mode)

```bash
cd server
npm install
node server.js
# Server starts on http://0.0.0.0:3000
# Check: http://localhost:3000/health
```

---

## Step 3 — Deploy to Cloud (Render.com — free tier)

1. Push the `server/` folder to a GitHub repo
2. Go to https://render.com → New → Web Service
3. Connect your repo
4. Settings:
   - **Build Command:** `npm install`
   - **Start Command:** `node server.js`
   - **Environment:** Node
5. Click Deploy → copy the URL (e.g. `https://clipsync-xxxx.onrender.com`)
6. Your WebSocket URL = `wss://clipsync-xxxx.onrender.com`

### Deploy to Railway (alternative)

```bash
# Install Railway CLI
npm install -g @railway/cli
cd server
railway login
railway init
railway up
# Copy the generated URL
```

---

## Step 4 — Run the Desktop Client

```bash
cd desktop

# Install dependencies
pip install -r requirements.txt

# On Linux: also install xclip for image support
# sudo apt install xclip

# Edit client.py: set ROOM_ID, LOCAL_SERVER, CLOUD_SERVER

python client.py
```

### Platform notes

| Platform | Image clipboard support                                    |
|----------|------------------------------------------------------------|
| Windows  | ✅ Full (PIL ImageGrab + win32clipboard)                   |
| macOS    | ✅ Full (PIL ImageGrab + osascript)                        |
| Linux    | ✅ Text always; images need `sudo apt install xclip`       |

Windows extra dependency (for image write):
```
pip install pywin32
```

---

## Step 5 — Build & Run the Android App

### Prerequisites
- Android Studio Hedgehog or newer
- Android device or emulator with API 26+

### Steps

```
1. Open Android Studio
2. File → Open → select the `android/` folder
3. Wait for Gradle sync to finish
4. Edit app/src/main/java/com/clipsync/Config.kt:
      ROOM_ID       = "my-room-123"       ← same as desktop
      LOCAL_SERVER  = "ws://192.168.1.42:3000"
      CLOUD_SERVER  = "wss://your-app.onrender.com"
5. Run on device (▶)
6. Enter Room ID → tap "Start Sync"
```

### Android clipboard note
Android 10+ restricts clipboard access to foreground apps.
The service uses a foreground notification to stay alive.
For **receiving** clipboard updates from the server, the app writes
directly to the clipboard — this always works.
For **sending** clipboard changes from Android, you must have the
ClipSync app open or in the foreground.

---

## Testing

### Test 1 — Phone → Laptop
1. Open any app on Android (e.g. browser)
2. Long-press text → Copy
3. Bring ClipSync app to foreground (triggers clipboard read)
4. Within ~1 second, paste on laptop — should have the text

### Test 2 — Laptop → Phone
1. Copy any text on laptop
2. Within 500ms, desktop client sends it to server
3. On Android, open any text field → long-press → Paste

### Test 3 — Image (Windows/macOS → Android)
1. Take a screenshot (clipboard image)
2. Desktop client detects it, sends as base64 PNG
3. Android receives it as a file URI in clipboard

### Test 4 — LAN → Cloud fallback
1. Stop the local server (`Ctrl+C`)
2. Desktop client auto-switches to cloud URL in ~5 seconds
3. Both devices continue syncing via cloud

---

## Architecture

```
Android App                  Node.js Server              Python Desktop
    │                             │                             │
    │──── ws://LAN:3000 ─────────▶│                             │
    │     (join room "xyz")        │◀──── ws://LAN:3000 ────────│
    │                             │      (join room "xyz")       │
    │                             │                             │
    │  Copy "Hello" on Android    │                             │
    │──── {type:clipboard} ──────▶│──── broadcast ─────────────▶│
    │                             │                             │ sets clipboard
    │                             │                             │
    │     Copy "World" on PC      │                             │
    │◀─── broadcast ──────────────│◀─── {type:clipboard} ───────│
    │ sets clipboard              │                             │
```

### Loop Prevention
Each message carries `device_id` (UUID generated at startup).
On receive, if `device_id == my own id` → discard.
On receive, store hash → skip sending if clipboard change matches last received.

---

## Server API

| Endpoint         | Description                          |
|------------------|--------------------------------------|
| `GET /health`    | Server status + room/client counts   |
| `GET /rooms`     | Active rooms and peer counts         |
| `WS /`           | WebSocket endpoint                   |

### WebSocket Messages

**Join a room:**
```json
{ "type": "join", "room_id": "my-room-123", "device_id": "uuid" }
```

**Send clipboard:**
```json
{
  "type": "clipboard",
  "device_id": "uuid",
  "room_id": "my-room-123",
  "data_type": "text",
  "content": "Hello World",
  "timestamp": 1700000000.0
}
```

**Image payload** — same shape, `data_type: "image"`, `content` is base64-encoded PNG.

---

## Troubleshooting

| Problem                          | Fix                                              |
|----------------------------------|--------------------------------------------------|
| Android can't connect to LAN     | Confirm phone + laptop on same WiFi; check firewall on port 3000 |
| Desktop clipboard not detected   | Linux: `sudo apt install xclip`; macOS: grant Accessibility permission |
| Cloud not working                | Check wss:// prefix (not ws://); verify Render URL |
| Image not syncing on Android     | Android has limited programmatic clipboard image support; text always works |
| `pyperclip` error on Linux       | `sudo apt install xsel` or `xclip`               |
