package com.clipsync

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clipsync.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editRoomId.setText(Config.ROOM_ID)
        binding.editLocalServer.setText(Config.LOCAL_SERVER)
        binding.editCloudServer.setText(Config.CLOUD_SERVER)

        binding.btnToggle.setOnClickListener {
            if (serviceRunning) stopSyncService() else startSyncService()
        }

        // Handle text shared from other apps (Share → ClipSync)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                // Copy to local clipboard + sync
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("clipsync", sharedText))
                // Auto-start service and send
                if (!serviceRunning) startSyncService()
                sendTextViaService(sharedText)
                Toast.makeText(this, "Syncing shared text…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // When user opens app (e.g. after copying on phone), send current clipboard
        if (serviceRunning) {
            val cm   = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
            if (text.isNotEmpty()) {
                sendTextViaService(text)
            }
        }
    }

    private fun sendTextViaService(text: String) {
        val intent = Intent(this, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_SEND_CLIP
            putExtra(ClipboardService.EXTRA_CLIP_CONTENT,  text)
            putExtra(ClipboardService.EXTRA_CLIP_DATATYPE, "text")
        }
        startService(intent)
    }

    private fun startSyncService() {
        val roomId      = binding.editRoomId.text.toString().trim()
        val localServer = binding.editLocalServer.text.toString().trim()
        val cloudServer = binding.editCloudServer.text.toString().trim()

        if (roomId.isEmpty()) {
            Toast.makeText(this, "Enter a Room ID", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
                return
            }
        }

        val intent = Intent(this, ClipboardService::class.java).apply {
            putExtra(ClipboardService.EXTRA_ROOM_ID,      roomId)
            putExtra(ClipboardService.EXTRA_LOCAL_SERVER,  localServer)
            putExtra(ClipboardService.EXTRA_CLOUD_SERVER,  cloudServer)
        }
        startForegroundService(intent)

        serviceRunning = true
        binding.btnToggle.text = "Stop Sync"
        binding.statusText.text = "Running  ·  Room: $roomId"
        Toast.makeText(this, "ClipSync started", Toast.LENGTH_SHORT).show()
    }

    private fun stopSyncService() {
        val intent = Intent(this, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_STOP
        }
        startService(intent)

        serviceRunning = false
        binding.btnToggle.text = "Start Sync"
        binding.statusText.text = "Stopped"
        Toast.makeText(this, "ClipSync stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSyncService()
        }
    }
}