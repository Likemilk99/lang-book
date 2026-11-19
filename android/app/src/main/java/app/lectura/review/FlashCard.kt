package app.lectura.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.lectura.data.SavedWord
import app.lectura.data.Srs
import app.lectura.ui.WordImageView

/** Карточка с переворотом и ручной оценкой (Снова / Трудно / Хорошо / Легко). */
@Composable
fun ColumnScope.FlashCardView(vm: ReviewViewModel, ex: Exercise.Flash) {
    val player = LecturaApp.instance.player
    val card = ex.word
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) { if (revealed) vm.onFlashRevealed(ex) }

    val esFront = ex.spanishFront
    val showSpanish = esFront || revealed
    val showRussian = !esFront || revealed

    Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled = !revealed) { revealed = true }) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Картинка видна, когда смысл уже открыт (на испанской стороне до ответа она была бы подсказкой).
            if (!card.isPhrase && showRussian) {
                WordImageView(card.lemma.ifBlank { card.word }, card.ru, Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }
            if (esFront) SpanishSide(card) else RussianSide(card)
            if (revealed) {
                HorizontalDivider(Modifier.padding(vertical = 20.dp))
                if (esFront) RussianSide(card) else SpanishSide(card)
            }
            if (showRussian && showSpanish && card.sentenceRu.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    card.sentenceRu,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!revealed) {
                Spacer(Modifier.height(32.dp))
                Text("Нажмите, чтобы перевернуть", color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    if (showSpanish) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { player.speakOnce(card.word) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Text(if (card.isPhrase) "  Фраза" else "  Слово")
            }
            if (card.sentence.isNotBlank()) {
                OutlinedButton(onClick = { player.speakOnce(card.sentence) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                    Text("  Пример")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (revealed) {
        // Нажатие на оценку сразу прерывает диктора и показывает следующее (stopSpeech — во ViewModel).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GradeButton("Снова", Srs.label(card, Srs.AGAIN), Bad, Modifier.weight(1f)) { vm.gradeFlash(Srs.AGAIN) }
            GradeButton("Трудно", Srs.label(card, Srs.HARD), Warn, Modifier.weight(1f)) { vm.gradeFlash(Srs.HARD) }
            GradeButton("Хорошо", Srs.label(card, Srs.GOOD), Good, Modifier.weight(1f)) { vm.gradeFlash(Srs.GOOD) }
            GradeButton("Легко", Srs.label(card, Srs.EASY), Color(0xFF1565C0), Modifier.weight(1f)) { vm.gradeFlash(Srs.EASY) }
        }
    } else {
        Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Показать ответ")
        }
    }
}

@Composable
private fun SpanishSide(card: SavedWord) {
    Text(card.word, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val extra = listOfNotNull(
        card.lemma.takeIf { it.isNotBlank() && !it.equals(card.word, ignoreCase = true) },
        card.grammar.takeIf { it.isNotBlank() },
    )
    if (extra.isNotEmpty()) {
        Text(
            extra.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    if (card.sentence.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        Text(highlight(card.sentence, card.word), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RussianSide(card: SavedWord) {
    Text(card.ru, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
}

@Composable
private fun GradeButton(title: String, sub: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(sub, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun highlight(sentence: String, word: String) = buildAnnotatedString {
    val c = TextMatch.cloze(sentence, word)
    if (c == null) {
        append(sentence)
    } else {
        append(c.before)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(c.answer) }
        append(c.after)
    }
}
