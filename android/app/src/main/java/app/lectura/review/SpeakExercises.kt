package app.lectura.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.model.Phrase
import app.lectura.speech.MatchResult
import app.lectura.speech.SpeakButton

/**
 * Проверка устного ответа: кнопка микрофона → верно (дальше) или разбор: что распознано, как правильно
 * (с озвучкой), «Ещё раз» / «Я сказал правильно» / [giveUp].
 * [expected] — что нужно сказать; [ref] — id фразы разговорника (озвучка диктором).
 */
@Composable
fun SpeakCheck(
    expected: String,
    ref: String,
    onShowAnswer: () -> Unit,
    onAnswer: (Answer) -> Unit,
    giveUp: String = "Не знаю",
) {
    var retried by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<MatchResult?>(null) }
    val f = failed
    if (f == null) {
        SpeakButton(expected = expected, modifier = Modifier.fillMaxWidth()) { r ->
            if (r.passed) {
                onAnswer(if (retried) Answer(Outcome.HARD, "Со второй попытки") else Answer(Outcome.CORRECT))
            } else {
                failed = r
                onShowAnswer()
            }
        }
        GiveUp(giveUp) { onAnswer(Answer(Outcome.WRONG)) }
        return
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Warn.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, Warn),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (f.heard.isBlank()) "Ничего не распознано" else "Распознано: «${f.heard}»",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Правильно:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            Text(expected, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            ListenButtons(expected, Modifier.padding(top = 8.dp), ref)
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                LecturaApp.instance.player.stopSpeech()
                retried = true
                failed = null
            },
            modifier = Modifier.weight(1f).height(52.dp),
        ) { Text("Ещё раз") }
        Button(
            onClick = {
                onAnswer(
                    if (retried) Answer(Outcome.HARD, "Засчитано по вашей оценке") else Answer(Outcome.CORRECT, "Засчитано по вашей оценке"),
                )
            },
            modifier = Modifier.weight(1f).height(52.dp),
        ) { Text("Я сказал правильно") }
    }
    GiveUp(giveUp) { onAnswer(Answer(Outcome.WRONG)) }
}

@Composable
private fun GiveUp(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, textAlign = TextAlign.Center)
    }
}

/** «Послушайте и повторите»: испанский виден, диктор произносит его при показе. */
@Composable
fun ListenRepeatView(ex: Exercise.ListenRepeat, answered: Boolean, onShowAnswer: () -> Unit, onAnswer: (Answer) -> Unit) {
    val w = ex.word
    Instruction("Послушайте и повторите вслух")
    Text(w.word, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        w.ru,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    ListenButtons(w.word, Modifier.padding(top = 12.dp), w.ref)
    Spacer(Modifier.height(20.dp))
    if (answered) return
    SpeakCheck(w.word, w.ref, onShowAnswer, onAnswer, giveUp = "Не получается")
}

/** «Скажите по шаблону»: шаблон фразы и перевод нового варианта → сказать вариант по-испански. */
@Composable
fun PatternSayView(ex: Exercise.PatternSay, answered: Boolean, onShowAnswer: () -> Unit, onAnswer: (Answer) -> Unit) {
    Instruction("Скажите по-испански по образцу")
    Text(ex.variantRu, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Образец", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                patternText(ex.pattern),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "${ex.word.word} — ${ex.word.ru}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    if (answered) return
    SpeakCheck(ex.variantEs, ex.word.ref, onShowAnswer, onAnswer)
}

/** Шаблон с выделенным пропуском «___». */
@Composable
fun patternText(pattern: String) = buildAnnotatedString {
    val i = pattern.indexOf(Phrase.GAP)
    if (i < 0) {
        append(pattern)
        return@buildAnnotatedString
    }
    append(pattern.substring(0, i))
    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append("____") }
    append(pattern.substring(i + Phrase.GAP.length))
}
