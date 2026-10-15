package com.nisaba.device

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.nisaba.R
import com.nisaba.domain.conversation.Reminder
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Nisaba's own reminders, for a device with no calendar app (a Boox has none): an alarm wakes [ReminderAlarmReceiver]
 * at the time, which posts a notification with the words. A Boox refuses wake-up alarms to apps it doesn't list, so a
 * plain alarm is set too (it fires while the device is awake), and every reminder is also kept until posted: one that
 * is due when Nisaba next comes to the front is posted then ([catchUp]). Lost on a reboot only if never opened again.
 */
object ReminderDevice {
    private const val CHANNEL = "reminders"
    private const val PREFS = "reminders"
    internal const val EXTRA_TEXT = "text"
    internal const val EXTRA_KEY = "key"

    /** Notifications may be posted (Android 13+ asks the person first). */
    fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Schedules [r]; false when its time can't be read or has passed. */
    fun schedule(context: Context, r: Reminder): Boolean {
        val at = runCatching { LocalDateTime.parse(r.at) }.getOrNull() ?: return false
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (millis <= System.currentTimeMillis()) return false
        val key = r.at + "|" + r.text
        prefs(context).edit().putLong(key, millis).apply()
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (type in listOf(AlarmManager.RTC_WAKEUP, AlarmManager.RTC)) {
            val intent = Intent(context, ReminderAlarmReceiver::class.java).putExtra(EXTRA_TEXT, r.text).putExtra(EXTRA_KEY, key)
            val pending = PendingIntent.getBroadcast(
                context, key.hashCode() * 2 + type, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching { alarms.setAndAllowWhileIdle(type, millis, pending) }
        }
        return true
    }

    /** Posts every kept reminder that is due (Nisaba came to the front; an alarm may have been refused). */
    fun catchUp(context: Context) {
        val now = System.currentTimeMillis()
        for ((key, at) in prefs(context).all) {
            if (at is Long && at <= now) post(context, key.substringAfter('|'), key)
        }
    }

    internal fun post(context: Context, text: String, key: String?) {
        if (key != null) {
            val kept = prefs(context)
            if (!kept.contains(key)) return // already posted (by the other alarm, or on catching up)
            kept.edit().remove(key).apply()
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val n = android.app.Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_mark).setContentTitle("Nisaba")
            .setContentText(text).setAutoCancel(true).setContentIntent(open).build()
        if (canNotify(context)) nm.notify(text.hashCode(), n)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The alarm for a reminder went off: post it. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(ReminderDevice.EXTRA_TEXT) ?: return
        ReminderDevice.post(context, text, intent.getStringExtra(ReminderDevice.EXTRA_KEY))
    }
}
