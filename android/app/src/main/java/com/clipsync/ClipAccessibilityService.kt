package com.clipsync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class ClipAccessibilityService : AccessibilityService() {

    private lateinit var clipboardManager: ClipboardManager
    private var lastHash = ""

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // AccessibilityService context bypasses Android 10+ clipboard restriction
        val clip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
        if (text.isEmpty()) return@OnPrimaryClipChangedListener

        val h = text.hashCode().toString()
        if (h == lastHash) return@OnPrimaryClipChangedListener
        lastHash = h

        // Broadcast to ClipboardService which holds the WebSocket
        val intent = Intent(ClipboardService.ACTION_CLIP_FROM_ACCESSIBILITY).apply {
            setPackage(packageName)
            putExtra(ClipboardService.EXTRA_CLIP_CONTENT,  text)
            putExtra(ClipboardService.EXTRA_CLIP_DATATYPE, "text")
        }
        sendBroadcast(intent)
    }

    override fun onServiceConnected() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::clipboardManager.isInitialized)
            clipboardManager.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }
}