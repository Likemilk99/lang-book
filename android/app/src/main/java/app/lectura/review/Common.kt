package app.lectura.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp

val Good = Color(0xFF2E7D32)
val Bad = Color(0xFFC62828)
val Warn = Color(0xFFEF6C00)

/** Подсказка «что делать» над упражнением. */
@Composable
fun Instruction(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/**
 * Варианты ответа. chosen == null — ещё не выбрали; после выбора правильный подсвечивается зелёным,
 * выбранный неправильный — красным.
 */
@Composable
fun OptionList(options: List<String>, correct: Int, chosen: Int?, onChoose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, text ->
            val color = when {
                chosen == null -> MaterialTheme.colorScheme.surfaceContainerHigh
                i == correct -> Good.copy(alpha = 0.22f)
                i == chosen -> Bad.copy(alpha = 0.22f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
            val border = when {
                chosen != null && i == correct -> BorderStroke(2.dp, Good)
                chosen != null && i == chosen -> BorderStroke(2.dp, Bad)
                else -> null
            }
            Surface(
                onClick = { onChoose(i) },
                enabled = chosen == null,
                shape = RoundedCornerShape(12.dp),
                color = color,
                border = border,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Предложение с пропуском; после ответа на месте пропуска — правильная форма. */
@Composable
fun ClozeText(cloze: Cloze, revealed: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            append(cloze.before)
            if (revealed) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(cloze.answer) }
            } else {
                withStyle(SpanStyle(color = accent)) { append(" _____ ") }
            }
            append(cloze.after)
        },
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!revealed) {
        androidx.compose.material3.TextButton(
            onClick = { LecturaApp.instance.player.speakWithPauses(listOf(cloze.before, cloze.after)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.padding(end = 6.dp))
            Text("Прослушать")
        }
    }
}

@Composable
fun Hint(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** Кнопки «Послушать» и «Медленно». */
@Composable
fun ListenButtons(text: String, modifier: Modifier = Modifier) {
    val player = LecturaApp.instance.player
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { player.speakOnce(text) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
            Text("  Ещё раз")
        }
        OutlinedButton(onClick = { player.speakOnce(text, slow = true) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.SlowMotionVideo, null)
            Text("  Медленно")
        }
    }
}

/** Итог упражнения: верно / почти / неверно, правильный ответ и перевод. */
@Composable
fun FeedbackPanel(ex: Exercise, answer: Answer) {
    val player = LecturaApp.instance.player
    val (title, color) = when (answer.outcome) {
        Outcome.CORRECT -> "Верно!" to Good
        Outcome.HARD -> "Засчитано" to Warn
        Outcome.WRONG -> (if (ex is Exercise.DialogReply) "Не совсем" else "Неверно — вернёмся к этому позже") to Bad
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            answer.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = { player.speakOnce(ex.answerEs) },
                color = Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ex.answerEs,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        "Произнести",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (ex.answerRu.isNotBlank()) Text(ex.answerRu, style = MaterialTheme.typography.bodyLarge)
            if (ex is Exercise.ForWord && ex !is Exercise.BuildSentence && ex.word.sentence.isNotBlank() &&
                ex !is Exercise.ClozeChoice && ex !is Exercise.ClozeInput
            ) {
                Text(
                    ex.word.sentence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
