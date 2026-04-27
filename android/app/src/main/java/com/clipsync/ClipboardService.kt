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
    const val ACTION_CLIP_FROM_ACCESSIBILITY = "com.clipsync.CLIP_FROM_ACCESSIBILITY"
    companion object {
        private const val TAG        = "ClipSync"
        private const val CHANNEL_ID = "clipsync_channel"
        private const val NOTIF_ID   = 1

        const val ACTION_STOP            = "com.clipsync.STOP"
        const val ACTION_SEND_CLIP       = "com.clipsync.SEND_CLIP"
        const val EXTRA_ROOM_ID          = "room_id"
        const val EXTRA_LOCAL_SERVER     = "local_server"
        const val EXTRA_CLOUD_SERVER     = "cloud_server"
        const val EXTRA_CLIP_CONTENT     = "clip_content"
        const val EXTRA_CLIP_DATATYPE    = "clip_datatype"

        val DEVICE_ID: String = UUID.randomUUID().toString()
    }

    

    private var roomId      = Config.ROOM_ID
    private var localServer = Config.LOCAL_SERVER
    private var cloudServer = Config.CLOUD_SERVER

    private val scope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    private var ws: WebSocket?   = null
    private var isConnected      = false
    private var lastSentHash     = ""
    private var lastReceivedHash = ""

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var okHttpClient: OkHttpClient

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isConnected) return@OnPrimaryClipChangedListener
        scope.launch { handleClipboardChange() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        okHttpClient = OkHttpClient.Builder()
            .pingInterval(Config.PING_INTERVAL, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        createNotificationChannel()
        // Receive clipboard from AccessibilityService
val filter = android.content.IntentFilter(ACTION_CLIP_FROM_ACCESSIBILITY)
registerReceiver(accessibilityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SEND_CLIP -> {
                // Called from MainActivity.onResume or share handler
                val content  = intent.getStringExtra(EXTRA_CLIP_CONTENT)  ?: return START_STICKY
                val dataType = intent.getStringExtra(EXTRA_CLIP_DATATYPE) ?: "text"
                val h = content.hashCode().toString()
                if (h != lastSentHash && h != lastReceivedHash) {
                    lastSentHash = h
                    scope.launch { sendClipboard(dataType, content) }
                }
                return START_STICKY
            }

            else -> {
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
        }
    }

    override fun onDestroy() {
        isRunning.set(false)
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        ws?.close(1000, "Service stopped")
        scope.cancel()
        super.onDestroy()
        unregisterReceiver(accessibilityReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── WebSocket Connection ──────────────────────────────────────────────

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
                delay(Config.RETRY_DELAY_MS)
            } else {
                while (isConnected && isRunning.get()) delay(500)
                idx++
                delay(2_000)
            }
        }
    }

    private suspend fun tryConnect(url: String): Boolean {
        val latch = CompletableDeferred<Boolean>()
        val request = Request.Builder().url(url).build()

        okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket; isConnected = true
                latch.complete(true)
                webSocket.send(JSONObject().apply {
                    put("type", "join"); put("room_id", roomId); put("device_id", DEVICE_ID)
                }.toString())
                Log.i(TAG, "Connected  room=$roomId")
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
                updateNotification("Disconnected – reconnecting…")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                if (!latch.isCompleted) latch.complete(false)
            }
        })

        return try { withTimeout(12_000) { latch.await() } }
        catch (e: TimeoutCancellationException) { false }
    }

    // ── Clipboard Send ────────────────────────────────────────────────────

    private fun handleClipboardChange() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)

        val text = item.coerceToText(this)?.toString() ?: ""
        if (text.isNotEmpty()) {
            val h = text.hashCode().toString()
            if (h == lastSentHash || h == lastReceivedHash) return
            lastSentHash = h
            sendClipboard("text", text)
            return
        }

        item.uri?.let { uri ->
            try {
                val bmp = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                val b64 = bitmapToBase64(bmp)
                val h   = b64.hashCode().toString()
                if (h == lastSentHash || h == lastReceivedHash) return
                lastSentHash = h
                sendClipboard("image", b64)
            } catch (e: Exception) { Log.e(TAG, "URI image: ${e.message}") }
        }
    }

    private fun sendClipboard(dataType: String, content: String) {
        if (content.length > Config.MAX_IMAGE_BYTES && dataType == "image") return
        val payload = JSONObject().apply {
            put("type", "clipboard"); put("device_id", DEVICE_ID)
            put("room_id", roomId);   put("data_type", dataType)
            put("content", content);  put("timestamp", System.currentTimeMillis())
        }.toString()
        ws?.send(payload)
        Log.i(TAG, "SEND $dataType len=${content.length}")
    }

    // ── Clipboard Receive ─────────────────────────────────────────────────

    private fun handleIncoming(raw: String) {
        try {
            val msg = JSONObject(raw)
            if (msg.optString("type") == "joined") return
            if (msg.optString("type") != "clipboard") return
            if (msg.optString("device_id") == DEVICE_ID) return

            val dataType = msg.optString("data_type", "text")
            val content  = msg.optString("content", "")
            val h        = content.hashCode().toString()
            lastReceivedHash = h; lastSentHash = h

            when (dataType) {
                "text"  -> setTextClipboard(content)
                "image" -> setImageClipboard(content)
            }
            Log.i(TAG, "RECV $dataType len=${content.length}")
        } catch (e: Exception) { Log.e(TAG, "Parse: ${e.message}") }
    }

    private fun setTextClipboard(text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("clipsync", text))
    }

    private fun setImageClipboard(b64: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp   = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val file  = java.io.File(cacheDir, "clipsync_recv.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri  = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
            clipboardManager.setPrimaryClip(ClipData.newUri(contentResolver, "clipsync-image", uri))
        } catch (e: Exception) { Log.e(TAG, "Set image: ${e.message}") }
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "ClipSync", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "ClipSync background sync" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification {
        val stopPending = PendingIntent.getService(
            this, 0,
            Intent(this, ClipboardService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipSync").setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setOngoing(true).build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(status))
    }

    private val accessibilityReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val content  = intent?.getStringExtra(EXTRA_CLIP_CONTENT)  ?: return
        val dataType = intent.getStringExtra(EXTRA_CLIP_DATATYPE) ?: "text"
        val h = content.hashCode().toString()
        if (h == lastSentHash || h == lastReceivedHash) return
        lastSentHash = h
        scope.launch { sendClipboard(dataType, content) }
    }
}
}
