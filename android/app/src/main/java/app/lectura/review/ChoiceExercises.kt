package app.lectura.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp

@Composable
fun ClozeChoiceView(ex: Exercise.ClozeChoice, answered: Boolean, onAnswer: (Answer) -> Unit) {
    var chosen by remember { mutableStateOf<Int?>(null) }
    Instruction(if (ex.word.isPhrase) "Выберите пропущенную фразу" else "Выберите пропущенное слово")
    ClozeText(ex.cloze, revealed = answered)
    Hint(ex.word.sentenceRu)
    Spacer(Modifier.height(20.dp))
    OptionList(ex.options, ex.answerIndex, chosen) { i ->
        chosen = i
        onAnswer(Answer(if (i == ex.answerIndex) Outcome.CORRECT else Outcome.WRONG))
    }
    if (!answered) {
        DontKnow {
            chosen = -1 // показать правильный вариант
            onAnswer(Answer(Outcome.WRONG))
        }
    }
}

@Composable
fun ListenChoiceView(ex: Exercise.ListenChoice, answered: Boolean, onAnswer: (Answer) -> Unit) {
    var chosen by remember { mutableStateOf<Int?>(null) }
    Instruction("Послушайте и выберите перевод")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = { LecturaApp.instance.player.speakOnce(ex.word.word) },
            modifier = Modifier.size(96.dp),
        ) {
            Icon(Icons.Filled.Hearing, "Послушать", Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (answered) {
            Text(ex.word.word, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    ListenButtons(ex.word.word, Modifier.padding(vertical = 12.dp))
    OptionList(ex.options, ex.answerIndex, chosen) { i ->
        chosen = i
        onAnswer(Answer(if (i == ex.answerIndex) Outcome.CORRECT else Outcome.WRONG))
    }
    if (!answered) {
        DontKnow {
            chosen = -1 // показать правильный вариант
            onAnswer(Answer(Outcome.WRONG))
        }
    }
}

@Composable
fun DialogReplyView(ex: Exercise.DialogReply, onAnswer: (Answer) -> Unit) {
    val player = LecturaApp.instance.player
    var chosen by remember { mutableStateOf<Int?>(null) }
    var showRu by remember { mutableStateOf(false) }
    Instruction("Вам говорят… Что уместно ответить?")
    Card(Modifier.fillMaxWidth().clickable { showRu = !showRu }) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "«${ex.reply.prompt}»",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { player.speakOnce(ex.reply.prompt) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести")
                }
            }
            Text(
                if (showRu) ex.reply.promptRu.ifBlank { "(перевода нет)" } else "Нажмите, чтобы увидеть перевод",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    OptionList(ex.options, ex.answerIndex, chosen) { i ->
        chosen = i
        onAnswer(Answer(if (i == ex.answerIndex) Outcome.CORRECT else Outcome.WRONG))
    }
}

@Composable
fun DontKnow(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Не знаю", textAlign = TextAlign.Center)
    }
}
