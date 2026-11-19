package app.lectura.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import app.lectura.speech.SpeechTts
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Реплики по очереди с ожиданием конца: файл диктора из APK, если он есть, иначе системный TTS.
 * Для диалогов сценариев, где следующая реплика звучит только после предыдущей.
 */
class ClipPlayer(private val context: Context) {
    private val tts = SpeechTts(context)
    private var media: MediaPlayer? = null

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
        return suspendCancellableCoroutine { cont ->
            mp.setOnCompletionListener { if (cont.isActive) cont.resume(true) }
            mp.setOnErrorListener { _, _, _ ->
                if (cont.isActive) cont.resume(false)
                true
            }
            cont.invokeOnCancellation { stop() }
            // Установка скорости сама запускает воспроизведение — поэтому после подписки на конец.
            runCatching { mp.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f) }
            if (!mp.isPlaying) mp.start()
        }.also { if (media === mp) stop() }
    }

    fun stop() {
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
