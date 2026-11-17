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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.lectura.LecturaApp
import app.lectura.MainActivity
import app.lectura.R
import kotlinx.coroutines.launch

/**
 * Держит процесс живым, пока книга читается вслух (экран может быть выключен),
 * показывает уведомление с кнопками и принимает кнопки гарнитуры через MediaSession.
 */
class PlaybackService : LifecycleService() {

    private val player get() = LecturaApp.instance.player
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "lectura").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = player.play()
                override fun onPause() = player.pause()
                override fun onSkipToNext() = player.next()
                override fun onSkipToPrevious() = player.prev()
                override fun onStop() = player.pause()
            })
            isActive = true
        }
        lifecycleScope.launch {
            player.state.collect { update(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            player.pause()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, build(player.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.prev()
        }
        return START_NOT_STICKY
    }

    private fun update(s: Player.State) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, s.rate)
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, player.currentText)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.bookTitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Глава ${s.chapter + 1}")
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

    private fun build(s: Player.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, LecturaApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.bookTitle.ifBlank { "Lectura" })
            .setContentText(player.currentText)
            .setSubText("Глава ${s.chapter + 1} · ${s.sentence + 1}/${s.sentenceCount}")
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP, 4))
            .setOngoing(s.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(button(android.R.drawable.ic_media_previous, "Назад", ACTION_PREV, 1))
            .addAction(
                if (s.playing) button(android.R.drawable.ic_media_pause, "Пауза", ACTION_TOGGLE, 2)
                else button(android.R.drawable.ic_media_play, "Играть", ACTION_TOGGLE, 2),
            )
            .addAction(button(android.R.drawable.ic_media_next, "Дальше", ACTION_NEXT, 3))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getForegroundService(
        this, code, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun button(icon: Int, title: String, act: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, action(act, code)).build()

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "app.lectura.TOGGLE"
        const val ACTION_NEXT = "app.lectura.NEXT"
        const val ACTION_PREV = "app.lectura.PREV"
        const val ACTION_STOP = "app.lectura.STOP"
    }
}
