package app.lectura.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import app.lectura.speech.SpeechTts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Реплики по очереди с ожиданием конца: файл диктора из APK, если он есть, иначе системный TTS.
 * Для диалогов сценариев, где следующая реплика звучит только после предыдущей.
 */
class ClipPlayer(private val context: Context) {
    private val tts = SpeechTts(context)
    private var media: MediaPlayer? = null
    /** Whoever waits for the current clip to end; stop() must wake them up too. */
    private var waiting: CancellableContinuation<Boolean>? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Произнести и дождаться конца. false — прервано или звук недоступен. */
    suspend fun play(asset: String?, text: String, rate: Float = 1f): Boolean {
        stop()
        if (asset != null) playAsset(asset, rate)?.let { return it }
        return tts.speak(text, rate, null)
    }

    /** null — файл не открылся (тогда говорит TTS). */
    private suspend fun playAsset(asset: String, rate: Float): Boolean? {
        val mp = MediaPlayer()
        val ok = runCatching {
            mp.setAudioAttributes(attributes)
            if (asset.startsWith("/")) {
                mp.setDataSource(asset)
            } else {
                context.assets.openFd(asset).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            }
            mp.prepare()
        }.onFailure { Log.w("ClipPlayer", asset, it) }.isSuccess
        if (!ok) {
            mp.release()
            return null
        }
        media = mp
        // A clip whose end never gets reported (e.g. a truncated WAV) must not hold the caller forever.
        val limit = (mp.duration.takeIf { it > 0 } ?: 60_000) / rate.coerceAtLeast(0.5f) + 5_000
        return (withTimeoutOrNull(limit.toLong()) { awaitEnd(mp, rate) } ?: false).also { if (media === mp) stop() }
    }

    private suspend fun awaitEnd(mp: MediaPlayer, rate: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            waiting = cont
            mp.setOnCompletionListener { finish(cont, true) }
            mp.setOnErrorListener { _, _, _ ->
                finish(cont, false)
                true
            }
            cont.invokeOnCancellation { stop() }
            // Установка скорости сама запускает воспроизведение — поэтому после подписки на конец.
            runCatching { mp.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f) }
            runCatching { if (!mp.isPlaying) mp.start() }.onFailure {
                Log.w("ClipPlayer", "start", it)
                finish(cont, false)
            }
        }

    private fun finish(cont: CancellableContinuation<Boolean>, ok: Boolean) {
        if (waiting === cont) waiting = null
        if (cont.isActive) cont.resume(ok)
    }

    /**
     * Stop and release the clip. The waiting play() returns false: without that, a caller that awaits clips in
     * a queue (LiveChat.playLoop) stayed suspended forever after an interruption, and every later clip and
     * replay of the conversation was silent.
     */
    fun stop() {
        waiting?.let { finish(it, false) }
        media?.let { mp ->
            media = null
            runCatching {
                mp.setOnCompletionListener(null)
                mp.stop()
            }
            mp.release()
        }
        tts.stop()
    }

    fun release() {
        stop()
        tts.shutdown()
    }
}
