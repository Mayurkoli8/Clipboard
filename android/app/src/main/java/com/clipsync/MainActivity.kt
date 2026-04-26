package com.clipsync

import android.Manifest
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

        // Pre-fill defaults from Config
        binding.editRoomId.setText(Config.ROOM_ID)
        binding.editLocalServer.setText(Config.LOCAL_SERVER)
        binding.editCloudServer.setText(Config.CLOUD_SERVER)

        binding.btnToggle.setOnClickListener {
            if (serviceRunning) stopSyncService() else startSyncService()
        }
    }

    private fun startSyncService() {
        val roomId      = binding.editRoomId.text.toString().trim()
        val localServer = binding.editLocalServer.text.toString().trim()
        val cloudServer = binding.editCloudServer.text.toString().trim()

        if (roomId.isEmpty()) {
            Toast.makeText(this, "Enter a Room ID", Toast.LENGTH_SHORT).show()
            return
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
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
