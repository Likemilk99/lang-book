package app.lectura.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.model.Chapter
import app.lectura.model.Question
import app.lectura.review.Bad
import app.lectura.review.Good
import app.lectura.review.OptionList
import kotlinx.coroutines.launch

private class Loaded(val chapter: Chapter?)

/** Вопросы на понимание главы (Chapter.questions) с выбором ответа. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionsScreen(bookId: String, chapter: Int, onBack: () -> Unit) {
    val app = LecturaApp.instance
    val player = app.player
    // null — ещё грузится; Loaded(null) — главы нет / книга не открылась.
    val loaded by produceState<Loaded?>(null, bookId, chapter) {
        value = Loaded(runCatching { app.books.load(bookId).chapters.getOrNull(chapter) }.getOrNull())
    }
    val ch = loaded?.chapter
    DisposableEffect(Unit) { onDispose { player.stopSpeech() } }
    val questions = ch?.questions.orEmpty().filter { it.options.isNotEmpty() && it.answer in it.options.indices }
    var index by remember { mutableIntStateOf(0) }
    var correct by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(ch?.let { "Вопросы · ${it.title}" } ?: "Вопросы", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = {
                            player.stopSpeech()
                            onBack()
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    },
                )
                if (questions.isNotEmpty() && index < questions.size) {
                    LinearProgressIndicator(
                        progress = { index.toFloat() / questions.size },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            when {
                loaded == null -> Unit
                questions.isEmpty() -> Text(
                    "В этой главе пока нет вопросов. Обновите книгу (подготовьте её заново в новой версии) — " +
                        "и вопросы появятся.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp),
                )
                index >= questions.size -> Score(
                    correct = correct,
                    total = questions.size,
                    onAgain = {
                        index = 0
                        correct = 0
                        round++
                    },
                    onBack = onBack,
                )
                else -> key(round, index) {
                    QuestionView(
                        q = questions[index],
                        number = index + 1,
                        total = questions.size,
                        onAnswered = { ok -> if (ok) correct++ },
                        onNext = {
                            player.stopSpeech()
                            index++
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionView(q: Question, number: Int, total: Int, onAnswered: (Boolean) -> Unit, onNext: () -> Unit) {
    val app = LecturaApp.instance
    val player = app.player
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<Int?>(null) }
    var showRu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { player.speakOnce(q.q) }

    Text(
        "Вопрос $number из $total",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth().clickable { showRu = !showRu }) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(q.q, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { player.speakOnce(q.q) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести")
                }
            }
            Text(
                if (showRu) q.ru.ifBlank { "(перевода нет)" } else "Нажмите, чтобы увидеть перевод",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    OptionList(q.options, q.answer, chosen) { i ->
        player.stopSpeech()
        chosen = i
        val ok = i == q.answer
        onAnswered(ok)
        scope.launch { runCatching { app.userData.recordFreeExercise(ok) } }
    }
    val c = chosen
    if (c != null) {
        val ok = c == q.answer
        Text(
            if (ok) "Верно!" else "Неверно. Правильный ответ: ${q.options[q.answer]}",
            color = if (ok) Good else Bad,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp)) {
            Text(if (number < total) "Дальше" else "Результат")
        }
    }
}

@Composable
private fun Score(correct: Int, total: Int, onAgain: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Верно $correct из $total", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            when {
                correct == total -> "Отлично — вы всё поняли! 🎉"
                correct * 2 >= total -> "Хорошо! Можно перечитать главу и попробовать ещё раз."
                else -> "Стоит перечитать главу — потом вопросы пойдут легче."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onAgain, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Ещё раз") }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text("К книге") }
    }
}
