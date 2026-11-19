package app.lectura.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun SayEsView(ex: Exercise.SayEs, answered: Boolean, onShowAnswer: () -> Unit, onAnswer: (Answer) -> Unit) {
    val w = ex.word
    Instruction(if (w.isPhrase) "Скажите эту фразу по-испански" else "Скажите по-испански")
    Text(w.ru, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (!w.isPhrase) Hint(w.grammar)
    Spacer(Modifier.height(24.dp))
    if (answered) return
    SpeakCheck(w.word, w.ref, onShowAnswer, onAnswer)
}

@Composable
fun ClozeInputView(ex: Exercise.ClozeInput, answered: Boolean, onAnswer: (Answer) -> Unit) {
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun check() {
        if (text.isBlank() || answered) return
        // Можно вписать и одно слово, и всё предложение целиком.
        val right = listOf(ex.cloze.answer, ex.word.word, ex.cloze.before + ex.cloze.answer + ex.cloze.after)
        val result = when {
            right.any { TextMatch.same(text, it) } -> Answer(Outcome.CORRECT)
            right.any { TextMatch.sameLoose(text, it) } ->
                Answer(Outcome.HARD, "Почти! Обратите внимание на ударения и ñ: «${ex.cloze.answer}»")
            else -> Answer(Outcome.WRONG, "Вы написали: «${text.trim()}»")
        }
        wrong = result.outcome == Outcome.WRONG
        onAnswer(result)
    }

    Instruction(if (ex.word.isPhrase) "Впишите пропущенную фразу" else "Впишите пропущенное слово")
    ClozeText(ex.cloze, revealed = answered)
    Text(
        "(${ex.word.ru})",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Hint(ex.word.sentenceRu)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        enabled = !answered,
        isError = wrong,
        singleLine = true,
        label = { Text("Ответ по-испански") },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { check() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    if (!answered) {
        Button(
            onClick = { check() },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
        ) { Text("Проверить") }
        DontKnow { onAnswer(Answer(Outcome.WRONG)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildSentenceView(ex: Exercise.BuildSentence, answered: Boolean, onAnswer: (Answer) -> Unit) {
    val placed = remember { mutableStateListOf<Int>() }
    var triedOnce by remember { mutableStateOf(false) }

    fun check() {
        val built = placed.map { TextMatch.norm(ex.tiles[it]) }
        val ok = built == ex.target.map { TextMatch.norm(it) }
        when {
            ok -> onAnswer(if (triedOnce) Answer(Outcome.HARD, "Со второй попытки") else Answer(Outcome.CORRECT))
            !triedOnce -> triedOnce = true
            else -> onAnswer(Answer(Outcome.WRONG))
        }
    }

    Instruction("Соберите фразу по-испански")
    Text(
        ex.word.sentenceRu,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        FlowRow(
            Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            placed.forEach { i ->
                WordTile(ex.tiles[i], enabled = !answered) { placed.remove(i) }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ex.tiles.forEachIndexed { i, t ->
            val used = i in placed
            WordTile(t, enabled = !used && !answered, modifier = Modifier.alpha(if (used) 0.25f else 1f)) { placed.add(i) }
        }
    }
    if (triedOnce && !answered) {
        Text(
            "Пока не так — попробуйте ещё раз",
            color = Warn,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    if (!answered) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { placed.clear() }, enabled = placed.isNotEmpty()) { Text("Сбросить") }
            Button(
                onClick = { check() },
                enabled = placed.size == ex.tiles.size,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Проверить") }
        }
        DontKnow { onAnswer(Answer(Outcome.WRONG)) }
    }
}

@Composable
private fun WordTile(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
