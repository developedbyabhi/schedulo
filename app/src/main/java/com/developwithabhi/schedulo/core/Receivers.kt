package com.developwithabhi.schedulo.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.developwithabhi.schedulo.MainActivity
import com.developwithabhi.schedulo.data.AppDb
import com.developwithabhi.schedulo.data.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val m = AppDb.get(ctx).dao().get(id) ?: return@launch
                if (m.status != Status.PENDING) return@launch

                if (m.askBeforeSend) {
                    Notifier.show(ctx, id.toInt(), "Send to ${m.name}?", "${m.text}\n\nTap to open the chat and send.", WhatsApp.chatIntent(m))
                    Scheduler.complete(ctx, m, true)
                    return@launch
                }

                val service = SendService.instance
                if (service == null) {
                    Notifier.show(
                        ctx, id.toInt(), "Not sent to ${m.name}",
                        "Auto-send is off. Open Schedulo and enable it in Accessibility.",
                        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    Scheduler.complete(ctx, m, false)
                } else {
                    withContext(Dispatchers.Main) { service.enqueue(m) }
                }
            } finally {
                result.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                AppDb.get(ctx).dao().pending().forEach { m ->
                    // Missed while phone was off → send shortly after boot
                    Scheduler.schedule(ctx, m.copy(timeMillis = maxOf(m.timeMillis, now + 30_000)))
                }
            } finally {
                result.finish()
            }
        }
    }
}
