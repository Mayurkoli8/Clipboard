package com.clipsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardService : Service() {

    companion object {
        private const val TAG            = "ClipSync"
        private const val CHANNEL_ID     = "clipsync_channel"
        private const val NOTIF_ID       = 1
        const val ACTION_STOP            = "com.clipsync.STOP"
        const val EXTRA_ROOM_ID          = "room_id"
        const val EXTRA_LOCAL_SERVER     = "local_server"
        const val EXTRA_CLOUD_SERVER     = "cloud_server"

        val DEVICE_ID: String = UUID.randomUUID().toString()
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var roomId      = Config.ROOM_ID
    private var localServer = Config.LOCAL_SERVER
    private var cloudServer = Config.CLOUD_SERVER

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning   = AtomicBoolean(false)

    private var ws: WebSocket?     = null
    private var isConnected        = false
    private var lastSentHash       = ""
    private var lastReceivedHash   = ""

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var okHttpClient: OkHttpClient

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isConnected) return@OnPrimaryClipChangedListener
        scope.launch { handleClipboardChange() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        okHttpClient = OkHttpClient.Builder()
            .pingInterval(Config.PING_INTERVAL, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        intent?.let {
            roomId      = it.getStringExtra(EXTRA_ROOM_ID)      ?: Config.ROOM_ID
            localServer = it.getStringExtra(EXTRA_LOCAL_SERVER)  ?: Config.LOCAL_SERVER
            cloudServer = it.getStringExtra(EXTRA_CLOUD_SERVER)  ?: Config.CLOUD_SERVER
        }

        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        isRunning.set(true)

        clipboardManager.addPrimaryClipChangedListener(clipListener)
        scope.launch { connectWithFallback() }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        ws?.close(1000, "Service stopped")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── WebSocket Connection ───────────────────────────────────────────────────

    private suspend fun connectWithFallback() {
        val servers = listOf(localServer, cloudServer)
        var idx = 0

        while (isRunning.get()) {
            val url = servers[idx % servers.size]
            Log.d(TAG, "Trying $url")
            updateNotification("Trying $url …")

            val connected = tryConnect(url)
            if (!connected) {
                idx++
                Log.d(TAG, "Failed. Retry in ${Config.RETRY_DELAY_MS / 1000}s")
                delay(Config.RETRY_DELAY_MS)
            } else {
                // run_forever equivalent – wait until disconnected
                while (isConnected && isRunning.get()) {
                    delay(500)
                }
                idx++ // try other server next time
                delay(2_000)
            }
        }
    }

    private suspend fun tryConnect(url: String): Boolean {
        val latch = CompletableDeferred<Boolean>()

        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws          = webSocket
                isConnected = true
                latch.complete(true)

                val join = JSONObject().apply {
                    put("type",      "join")
                    put("room_id",   roomId)
                    put("device_id", DEVICE_ID)
                }.toString()
                webSocket.send(join)
                Log.i(TAG, "Connected to $url  room=$roomId")
                updateNotification("Connected  ·  room: $roomId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleIncoming(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.w(TAG, "Closed: $code $reason")
                updateNotification("Disconnected – reconnecting…")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                if (!latch.isCompleted) latch.complete(false)
                Log.w(TAG, "Failure: ${t.message}")
                updateNotification("Disconnected – reconnecting…")
            }
        }

        okHttpClient.newWebSocket(request, listener)
        return try { withTimeout(12_000) { latch.await() } }
        catch (e: TimeoutCancellationException) { false }
    }

    // ── Clipboard Handling ────────────────────────────────────────────────────

    private fun handleClipboardChange() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return

        val item = clip.getItemAt(0)

        // Try text
        val text = item.coerceToText(this)?.toString() ?: ""
        if (text.isNotEmpty()) {
            val h = text.hashCode().toString()
            if (h == lastSentHash || h == lastReceivedHash) return
            lastSentHash = h
            sendClipboard("text", text)
            return
        }

        // Try image URI → Bitmap → base64
        item.uri?.let { uri ->
            try {
                val bmp = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                val b64 = bitmapToBase64(bmp)
                val h   = b64.hashCode().toString()
                if (h == lastSentHash || h == lastReceivedHash) return
                lastSentHash = h
                sendClipboard("image", b64)
            } catch (e: Exception) {
                Log.e(TAG, "Image from URI failed: ${e.message}")
            }
        }
    }

    private fun sendClipboard(dataType: String, content: String) {
        if (content.length > Config.MAX_IMAGE_BYTES && dataType == "image") {
            Log.w(TAG, "Image too large, skipping")
            return
        }
        val payload = JSONObject().apply {
            put("type",      "clipboard")
            put("device_id", DEVICE_ID)
            put("room_id",   roomId)
            put("data_type", dataType)
            put("content",   content)
            put("timestamp", System.currentTimeMillis())
        }.toString()
        ws?.send(payload)
        Log.i(TAG, "SEND $dataType  len=${content.length}")
    }

    private fun handleIncoming(raw: String) {
        try {
            val msg = JSONObject(raw)
            val type = msg.optString("type")

            if (type == "joined") {
                Log.i(TAG, "Room joined  peers=${msg.optInt("peers")}")
                return
            }

            if (type != "clipboard") return
            if (msg.optString("device_id") == DEVICE_ID) return  // ignore self

            val dataType = msg.optString("data_type", "text")
            val content  = msg.optString("content", "")
            val h        = content.hashCode().toString()

            lastReceivedHash = h
            lastSentHash     = h

            when (dataType) {
                "text"  -> setTextClipboard(content)
                "image" -> setImageClipboard(content)
            }

            Log.i(TAG, "RECV $dataType  len=${content.length}")

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    // ── Clipboard Writers ─────────────────────────────────────────────────────

    private fun setTextClipboard(text: String) {
        val clip = ClipData.newPlainText("clipsync", text)
        clipboardManager.setPrimaryClip(clip)
    }

    private fun setImageClipboard(b64: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp   = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return

            // Save to cache, then put URI on clipboard
            val file = java.io.File(cacheDir, "clipsync_recv.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri  = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            val clip = ClipData.newUri(contentResolver, "clipsync-image", uri)
            clipboardManager.setPrimaryClip(clip)

        } catch (e: Exception) {
            Log.e(TAG, "Set image clipboard failed: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bitmapToBase64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ClipSync",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "ClipSync background sync" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, ClipboardService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipSync")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }
}
