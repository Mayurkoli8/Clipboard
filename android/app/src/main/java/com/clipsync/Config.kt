package com.clipsync

object Config {
    // ── EDIT THESE ──────────────────────────────────────────────────────────
    const val ROOM_ID      = "my-room-123"
    const val LOCAL_SERVER = "ws://192.168.1.100:3000"   // LAN IP of your laptop
    const val CLOUD_SERVER = "wss://your-app.onrender.com" // deployed cloud URL
    // ────────────────────────────────────────────────────────────────────────

    const val RETRY_DELAY_MS  = 5_000L
    const val PING_INTERVAL   = 20L   // seconds
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024   // 2 MB image limit
}
