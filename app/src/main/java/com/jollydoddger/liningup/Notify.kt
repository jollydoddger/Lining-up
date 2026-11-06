package com.jollydoddger.liningup

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Every notification the app raises. None of them carry a word, and every one
 * that says anything about either of you hides behind a plain 🌙 on the lock
 * screen when the phone is set to hide sensitive content.
 */
object Notify {
    const val CH_PROMPT = "prompt"
    const val CH_PARTNER = "partner"
    const val CH_MATCH = "match"
    private const val ID_PROMPT = 1
    private const val ID_PARTNER = 2
    private const val ID_MATCH = 3

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROMPT, "🌙", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PARTNER, "💬", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MATCH, "💞", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun discreet(context: Context, channel: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("🌙")
            .build()

    private fun base(context: Context, channel: String, title: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentIntent(open(context))
            .setAutoCancel(true)
            .setColor(0xFFE0457B.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(discreet(context, channel))

    private fun answer(context: Context, request: Int, ok: Boolean, what: Set<Choice>): PendingIntent =
        PendingIntent.getBroadcast(
            context, request,
            Intent(context, AnswerReceiver::class.java)
                .putExtra(AnswerReceiver.EXTRA_OK, ok)
                .putExtra(AnswerReceiver.EXTRA_WHAT, Choice.wire(what)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The 8pm question: ✅ or ❌. */
    fun prompt(context: Context) {
        if (!allowed(context)) return
        val n = base(context, CH_PROMPT, "🌙")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "✅", answer(context, 10, true, emptySet()))
            .addAction(0, "❌", answer(context, 11, false, emptySet()))
            .build()
        NotificationManagerCompat.from(context).notify(ID_PROMPT, n)
    }

    /** After ✅ on the popup: 👅, 🍆 or both, in place of the first question. */
    fun followUp(context: Context) {
        if (!allowed(context)) return
        val n = base(context, CH_PROMPT, "✅")
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, Choice.TONGUE.emoji, answer(context, 12, true, setOf(Choice.TONGUE)))
            .addAction(0, Choice.EGGPLANT.emoji, answer(context, 13, true, setOf(Choice.EGGPLANT)))
            .addAction(0, Choice.TONGUE.emoji + Choice.EGGPLANT.emoji, answer(context, 14, true, Choice.entries.toSet()))
            .build()
        NotificationManagerCompat.from(context).notify(ID_PROMPT, n)
    }

    fun clearPrompt(context: Context) = NotificationManagerCompat.from(context).cancel(ID_PROMPT)

    fun partner(context: Context, s: Status) {
        if (!allowed(context)) return
        // A note is flagged, never quoted: it is read by tapping, in the app.
        val title = listOf(s.avatar, s.emojis, if (s.note.isNotBlank()) "📝" else "").filter { it.isNotBlank() }
        val n = base(context, CH_PARTNER, title.joinToString(" "))
            .apply { if (s.ok != null) setContentText(Days.clock(s.at)) }
            .build()
        NotificationManagerCompat.from(context).notify(ID_PARTNER, n)
    }

    fun match(context: Context) {
        if (!allowed(context)) return
        val n = base(context, CH_MATCH, "💞").setPriority(NotificationCompat.PRIORITY_HIGH).build()
        NotificationManagerCompat.from(context).notify(ID_MATCH, n)
    }
}

/** A tap on one of the popup's emoji. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val ok = intent.getBooleanExtra(EXTRA_OK, false)
        val what = Choice.setOf(intent.getStringExtra(EXTRA_WHAT))
        Store.answer(ok, what)
        if (ok && what.isEmpty()) Notify.followUp(context) else Notify.clearPrompt(context)
        Sync.soon(context)
    }

    companion object {
        const val EXTRA_OK = "ok"
        const val EXTRA_WHAT = "what"
    }
}

object Reminder {
    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 1, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Books the next evening's popup, or cancels it if switched off. */
    fun schedule(context: Context) {
        Store.init(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = intent(context)
        am.cancel(pi)
        if (!Store.reminderOn) return
        val at = Days.nextAt(System.currentTimeMillis(), Store.reminderHour, Store.reminderMinute)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        Reminder.schedule(context)
        // Already answered today: no need to ask.
        if (Store.reminderOn && Store.couple != null && Store.mine() == null) Notify.prompt(context)
        Sync.soon(context)
    }
}

/** Alarms do not survive a reboot or a clock change; book the next one again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminder.schedule(context)
        Sync.schedule(context)
    }
}
