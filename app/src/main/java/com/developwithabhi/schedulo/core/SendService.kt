package com.developwithabhi.schedulo.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.developwithabhi.schedulo.data.ScheduledMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Opens the WhatsApp chat with the message pre-filled, then taps Send.
 * Processes one message at a time.
 */
class SendService : AccessibilityService() {

    companion object {
        @Volatile var instance: SendService? = null
            private set
        private const val TIMEOUT_MS = 25_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<ScheduledMessage>()
    private var current: ScheduledMessage? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeout = Runnable { current?.let { finish(it, false) } }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; releaseWake(); super.onDestroy() }
    override fun onInterrupt() {}

    fun enqueue(m: ScheduledMessage) {
        queue.addLast(m)
        if (current == null) next()
    }

    private fun next() {
        val m = queue.removeFirstOrNull() ?: run { releaseWake(); return }
        current = m
        wake()
        try {
            startActivity(WhatsApp.chatIntent(m))
        } catch (e: Exception) {
            finish(m, false); return
        }
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val m = current ?: return
        if (event?.packageName?.toString() != m.app) return
        val root = rootInActiveWindow ?: return
        val send = findSend(root, m.app) ?: return
        if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) finish(m, true)
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            .firstOrNull { it.isEnabled }?.let { return it }
        // Fallback if WhatsApp renames the view id
        return root.findAccessibilityNodeInfosByText("Send")
            .firstOrNull { it.isClickable && it.contentDescription?.toString().equals("Send", true) }
    }

    private fun finish(m: ScheduledMessage, ok: Boolean) {
        if (current?.id != m.id) return
        current = null
        handler.removeCallbacks(timeout)
        CoroutineScope(Dispatchers.IO).launch {
            Scheduler.complete(applicationContext, m, ok)
            if (!ok) Notifier.show(
                applicationContext, m.id.toInt(), "Couldn't send to ${m.name}",
                "Tap to open the chat and send it yourself.", WhatsApp.chatIntent(m),
            )
        }
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
            next()
        }, 1500)
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "schedulo:send",
        ).apply { acquire(60_000) }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
