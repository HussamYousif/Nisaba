package com.nisaba.notebook

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.nisaba.domain.conversation.Reminder
import java.time.LocalDateTime
import java.time.ZoneId

/** Hands a reminder to the calendar app, with the event filled in (false: there is none). */
object CalendarClient {
    private const val EVENT_MILLIS = 15 * 60 * 1000L

    /** False when no app on the device takes it (the caller says so and keeps the words). */
    fun add(context: Context, r: Reminder): Boolean {
        val at = runCatching { LocalDateTime.parse(r.at) }.getOrNull() ?: return false
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val event = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis + EVENT_MILLIS)
            .putExtra(CalendarContract.Events.TITLE, r.text)
        return start(context, event)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
