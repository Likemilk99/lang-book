package app.lectura.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.lectura.LecturaApp
import app.lectura.playback.ClipPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Запись своего голоса (без распознавания — только чтобы послушать себя рядом с диктором). */
class VoiceRecorder(private val context: Context) {
    val file = File(context.cacheDir, "my_voice.m4a")
    private var rec: MediaRecorder? = null

    fun start(): Boolean = runCatching {
        stop()
        file.delete()
        rec = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(64000)
            setOutputFile(file.path)
            prepare()
            start()
        }
        true
    }.getOrElse {
        Log.w("VoiceRecorder", "start", it)
        rec = null
        false
    }

    /** true — запись получилась. */
    fun stop(): Boolean {
        val r = rec ?: return false
        rec = null
        return runCatching {
            r.stop()
            true
        }.getOrDefault(false).also { r.release() }
    }
}

/**
 * «Записать себя» → «Диктор → вы»: слушаете эталон и свою запись подряд — так слышно разницу
 * в звуках и интонации. [clip] — озвучка диктора (null — системный голос).
 */
@Composable
fun VoiceCompare(es: String, clip: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(ctx) }
    val player = remember { ClipPlayer(ctx) }
    var recording by remember(es) { mutableStateOf(false) }
    var hasTake by remember(es) { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            player.release()
        }
    }

    fun startRec() {
        LecturaApp.instance.player.stopSpeech()
        player.stop()
        recording = recorder.start()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startRec() }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            when {
                recording -> {
                    hasTake = recorder.stop()
                    recording = false
                }
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRec()
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }, modifier = Modifier.weight(1f)) {
            Text(if (recording) "■ Стоп" else "🎙 Записать себя", color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        OutlinedButton(onClick = {
            scope.launch {
                player.play(clip, es)
                delay(400)
                player.play(recorder.file.path, es)
            }
        }, enabled = hasTake && !recording, modifier = Modifier.weight(1f)) { Text("▶ Диктор → вы") }
    }
}
