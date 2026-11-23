package app.lectura.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.lectura.LecturaApp
import app.lectura.MainActivity
import app.lectura.R
import kotlinx.coroutines.launch

/**
 * Держит аудиоурок живым при выключенном экране: уведомление с кнопками, кнопки гарнитуры (MediaSession)
 * и частичная блокировка сна — иначе паузы между фразами растягиваются, пока процессор спит.
 */
class LessonService : LifecycleService() {
    private val lessons get() = LecturaApp.instance.lessons
    private lateinit var session: MediaSession
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "lectura-lesson").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = lessons.resume()
                override fun onPause() = lessons.pause()
                override fun onSkipToNext() = lessons.next()
                override fun onSkipToPrevious() = lessons.prev()
                override fun onStop() = lessons.stop()
            })
            isActive = true
        }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lectura:lesson")
            .apply { setReferenceCounted(false) }
        lifecycleScope.launch { lessons.state.collect { update(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, build(lessons.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_TOGGLE -> lessons.toggle()
            ACTION_NEXT -> lessons.next()
            ACTION_PREV -> lessons.prev()
            ACTION_STOP -> lessons.stop()
        }
        return START_NOT_STICKY
    }

    private fun update(s: LessonPlayer.State) {
        if (!s.active && !s.playing) {
            wake?.takeIf { it.isHeld }?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // Не дольше 20 минут подряд без нового шага — на случай, если что-то зависло.
        if (s.playing) wake?.acquire(20 * 60 * 1000L) else wake?.takeIf { it.isHeld }?.release()
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f)
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.current?.es.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.topic)
                .build(),
        )
        val n = build(s)
        if (s.playing) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun build(s: LessonPlayer.State): Notification {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val item = s.current
        return Notification.Builder(this, LecturaApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🎧 ${s.topic}")
            .setContentText(if (item == null) "" else "${item.ru} — ${item.es}")
            .setSubText("${s.mode.label} · ${minOf(s.index + 1, s.items.size)}/${s.items.size}" + if (s.loop) " · 🔁 круг ${s.round}" else "")
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP, 14))
            .setOngoing(s.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(button(android.R.drawable.ic_media_previous, "Назад", ACTION_PREV, 11))
            .addAction(
                if (s.playing) button(android.R.drawable.ic_media_pause, "Пауза", ACTION_TOGGLE, 12)
                else button(android.R.drawable.ic_media_play, "Играть", ACTION_TOGGLE, 12),
            )
            .addAction(button(android.R.drawable.ic_media_next, "Дальше", ACTION_NEXT, 13))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getForegroundService(
        this, code, Intent(this, LessonService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun button(icon: Int, title: String, act: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, action(act, code)).build()

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        session.release()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 3
        const val ACTION_TOGGLE = "app.lectura.lesson.TOGGLE"
        const val ACTION_NEXT = "app.lectura.lesson.NEXT"
        const val ACTION_PREV = "app.lectura.lesson.PREV"
        const val ACTION_STOP = "app.lectura.lesson.STOP"
    }
}
