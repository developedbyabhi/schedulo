package com.developwithabhi.schedulo.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.developwithabhi.schedulo.data.AppDb
import com.developwithabhi.schedulo.data.Repeat
import com.developwithabhi.schedulo.data.ScheduledMessage
import com.developwithabhi.schedulo.data.Status
import java.util.Calendar

object WhatsApp {
    const val PERSONAL = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    fun chatIntent(m: ScheduledMessage): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${m.phone}&text=${Uri.encode(m.text)}"))
            .setPackage(m.app)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 10-digit Indian numbers get 91 prefixed. */
    fun normalize(raw: String): String {
        val d = raw.filter(Char::isDigit).removePrefix("00")
        return when {
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91${d.drop(1)}"
            else -> d
        }
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }
}

object Perms {
    fun accessibilityOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("${ctx.packageName}/") }
    }

    fun exactAlarmOk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

object Scheduler {
    fun schedule(ctx: Context, m: ScheduledMessage) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, m.id)
        if (Perms.exactAlarmOk(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, m.timeMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, m.timeMillis, pi)
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
    }

    private fun pending(ctx: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(),
        Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun nextTime(m: ScheduledMessage): Long? {
        val field = when (m.repeat) {
            Repeat.DAILY -> Calendar.DAY_OF_YEAR
            Repeat.WEEKLY -> Calendar.WEEK_OF_YEAR
            Repeat.MONTHLY -> Calendar.MONTH
            else -> return null
        }
        val c = Calendar.getInstance().apply { timeInMillis = m.timeMillis }
        while (c.timeInMillis <= System.currentTimeMillis()) c.add(field, 1)
        return c.timeInMillis
    }

    /** Marks result; repeating messages get rescheduled after a successful send. */
    suspend fun complete(ctx: Context, m: ScheduledMessage, ok: Boolean) {
        val dao = AppDb.get(ctx).dao()
        val next = if (ok) nextTime(m) else null
        if (next != null) {
            val updated = m.copy(timeMillis = next, status = Status.PENDING)
            dao.update(updated)
            schedule(ctx, updated)
        } else {
            dao.update(m.copy(status = if (ok) Status.SENT else Status.FAILED))
        }
    }
}

object Notifier {
    private const val CHANNEL = "schedulo"

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Scheduled messages", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, title: String, text: String, intent: Intent? = null) {
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        intent?.let {
            b.setContentIntent(PendingIntent.getActivity(ctx, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }
}
