package app.lectura.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lectura.LecturaApp
import app.lectura.MainActivity
import app.lectura.R
import app.lectura.data.DailyStats
import app.lectura.data.isActive
import app.lectura.data.today
import app.lectura.ui.streak
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Ежедневное напоминание: раз в сутки в выбранное время проверяем, были ли сегодня занятия,
 * и если нет — показываем уведомление. Расписание держит WorkManager (переживает перезагрузку).
 */
object Reminder {
    private const val WORK = "daily-reminder"
    const val CHANNEL = "reminder"
    private const val NOTIFICATION_ID = 2

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Напоминание о занятиях", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * Поставить (или переставить) напоминание по настройкам.
     * [reset] = true — время поменяли, расписание надо пересчитать; иначе существующее не трогаем.
     */
    fun schedule(ctx: Context, reset: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        val prefs = LecturaApp.instance.prefs
        if (!prefs.reminderEnabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val delay = delayUntil(prefs.reminderMinutes, LocalDateTime.now())
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK,
            if (reset) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Сколько ждать до ближайшего [minutes] (минут от полуночи); если время сегодня прошло — до завтра. */
    fun delayUntil(minutes: Int, now: LocalDateTime): Duration {
        var at = now.toLocalDate().atTime(minutes / 60, minutes % 60)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return Duration.between(now, at)
    }

    fun label(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)

    /** Текст уведомления; null — сегодня уже занимались, напоминать не нужно. */
    fun message(today: DailyStats?, due: Int, streak: Int): Pair<String, String>? {
        if (today?.isActive() == true) return null
        val title = if (streak > 0) "Серия 🔥 $streak — не прерывайте её" else "¡Hola! Пора позаниматься"
        val text = buildString {
            append("План на сегодня — около 20 минут")
            if (due > 0) append(" · к повторению: $due")
        }
        return title to text
    }

    fun canNotify(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    internal fun show(ctx: Context, title: String, text: String) {
        if (!canNotify(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w("Reminder", "no notification permission", e)
        }
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = LecturaApp.instance
        if (!app.prefs.reminderEnabled) return Result.success()
        val db = app.db
        val stats = db.stats().allOnce()
        val due = db.words().dueCountNow(System.currentTimeMillis())
        Reminder.message(stats.firstOrNull { it.day == today() }, due, streak(stats))?.let { (title, text) ->
            Reminder.show(applicationContext, title, text)
        }
        return Result.success()
    }
}
