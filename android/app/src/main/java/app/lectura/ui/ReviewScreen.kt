package app.lectura.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.data.SavedWord
import app.lectura.data.Srs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

enum class Direction(val key: String, val label: String) {
    ES_RU("es_ru", "Испанский → русский"),
    RU_ES("ru_es", "Русский → испанский"),
    MIXED("mixed", "Вперемешку"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: ES_RU
    }
}

class ReviewViewModel(private val app: LecturaApp) : ViewModel() {
    val queue = mutableStateListOf<SavedWord>()
    var revealed by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var loaded by mutableStateOf(false)
    var started by mutableStateOf(false)
    var nextDue by mutableStateOf<Long?>(null)

    var autoWord by mutableStateOf(app.prefs.reviewAutoWord)
    var autoExample by mutableStateOf(app.prefs.reviewAutoExample)
    var direction by mutableStateOf(Direction.of(app.prefs.reviewDirection))

    /** Для «вперемешку»: у каждой карточки своё направление на всю сессию. */
    private val spanishFront = HashMap<Long, Boolean>()

    fun load() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        queue.clear()
        queue.addAll(app.db.words().due(now).shuffled())
        nextDue = app.db.words().nextDue(now)
        revealed = false
        loaded = true
    }

    fun start() {
        app.prefs.reviewAutoWord = autoWord
        app.prefs.reviewAutoExample = autoExample
        app.prefs.reviewDirection = direction.key
        spanishFront.clear()
        started = true
    }

    fun isSpanishFront(w: SavedWord): Boolean = when (direction) {
        Direction.ES_RU -> true
        Direction.RU_ES -> false
        Direction.MIXED -> spanishFront.getOrPut(w.id) { Random.nextBoolean() }
    }

    fun grade(g: Int) {
        val w = queue.removeFirstOrNull() ?: return
        val upd = Srs.schedule(w, g)
        if (g == Srs.AGAIN) queue.add(upd)
        revealed = false
        done++
        viewModelScope.launch {
            app.userData.updateWord(upd)
            if (queue.isEmpty()) nextDue = app.db.words().nextDue(System.currentTimeMillis())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen() {
    val app = LecturaApp.instance
    val player = app.player
    val vm: ReviewViewModel = viewModel { ReviewViewModel(app) }
    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(Unit) { onDispose { player.stopSpeech() } }
    val card = vm.queue.firstOrNull()

    // Авто-озвучка: только испанский и только в момент, когда испанская сторона появляется.
    LaunchedEffect(vm.started, card?.id, vm.done, vm.revealed) {
        val c = card ?: return@LaunchedEffect
        if (!vm.started) return@LaunchedEffect
        val esFront = vm.isSpanishFront(c)
        val spanishJustShown = (esFront && !vm.revealed) || (!esFront && vm.revealed)
        if (!spanishJustShown) return@LaunchedEffect
        player.speakSequence(
            listOfNotNull(
                c.word.takeIf { vm.autoWord },
                c.sentence.takeIf { vm.autoExample && it.isNotBlank() },
            ),
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(if (card != null && vm.started) "Повторение · осталось ${vm.queue.size}" else "Повторение")
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !vm.loaded -> Unit
                card == null -> Finished(vm)
                !vm.started -> SetupPanel(vm)
                else -> CardView(vm, card)
            }
        }
    }
}

@Composable
private fun Finished(vm: ReviewViewModel) {
    Spacer(Modifier.height(48.dp))
    Text(
        if (vm.done > 0) "Готово! Повторено: ${vm.done} 🎉" else "Сейчас нечего повторять",
        style = MaterialTheme.typography.titleLarge,
    )
    vm.nextDue?.let {
        Text(
            "Следующие слова: " + SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(it)),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SetupPanel(vm: ReviewViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Слов к повторению: ${vm.queue.size}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Text("Какой стороной показывать", style = MaterialTheme.typography.titleSmall)
            Direction.entries.forEach { d ->
                FilterChip(
                    selected = vm.direction == d,
                    onClick = { vm.direction = d },
                    label = { Text(d.label) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Озвучка (только испанский)", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Сразу произносить слово", vm.autoWord) { vm.autoWord = it }
            SwitchRow("Сразу произносить пример", vm.autoExample) { vm.autoExample = it }
            Text(
                "Если спереди русское слово — озвучка начнётся, когда перевернёте карточку.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Начать") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CardView(vm: ReviewViewModel, card: SavedWord) {
    val player = LecturaApp.instance.player
    val esFront = vm.isSpanishFront(card)
    val showSpanish = esFront || vm.revealed
    val showRussian = !esFront || vm.revealed

    Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled = !vm.revealed) { vm.revealed = true }) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Лицевая сторона
            if (esFront) SpanishSide(card) else RussianWord(card)

            if (vm.revealed) {
                HorizontalDivider(Modifier.padding(vertical = 20.dp))
                if (esFront) RussianWord(card) else SpanishSide(card)
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
            if (!vm.revealed) {
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
                Text("  Слово")
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

    if (vm.revealed) {
        // Нажатие на оценку сразу прерывает диктора и показывает следующую карточку.
        val grade = { g: Int ->
            player.stopSpeech()
            vm.grade(g)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GradeButton("Снова", Srs.label(card, Srs.AGAIN), Color(0xFFC62828), Modifier.weight(1f)) { grade(Srs.AGAIN) }
            GradeButton("Трудно", Srs.label(card, Srs.HARD), Color(0xFFEF6C00), Modifier.weight(1f)) { grade(Srs.HARD) }
            GradeButton("Хорошо", Srs.label(card, Srs.GOOD), Color(0xFF2E7D32), Modifier.weight(1f)) { grade(Srs.GOOD) }
            GradeButton("Легко", Srs.label(card, Srs.EASY), Color(0xFF1565C0), Modifier.weight(1f)) { grade(Srs.EASY) }
        }
    } else {
        Button(onClick = { vm.revealed = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Показать ответ")
        }
    }
}

@Composable
private fun SpanishSide(card: SavedWord) {
    Text(card.word, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val extra = listOf(
        card.lemma.takeIf { !it.equals(card.word, ignoreCase = true) },
        card.grammar.takeIf { it.isNotBlank() },
    ).filterNotNull()
    if (extra.isNotEmpty()) Text(
        extra.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (card.sentence.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        Text(highlight(card.sentence, card.word), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RussianWord(card: SavedWord) {
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
    val i = sentence.indexOf(word)
    if (i < 0) {
        append(sentence)
    } else {
        append(sentence.substring(0, i))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word) }
        append(sentence.substring(i + word.length))
    }
}
