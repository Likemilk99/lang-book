package app.lectura.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.data.ListenItem
import app.lectura.data.ListenQuestion
import app.lectura.data.Listening
import app.lectura.playback.ClipPlayer
import app.lectura.review.Bad
import app.lectura.review.Good
import app.lectura.review.OptionList
import app.lectura.review.TextMatch
import app.lectura.review.Warn
import app.lectura.speech.SpeechMatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Карточка во вкладке «Говорить». */
@Composable
fun ListenCard(onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("👂 Тренажёр слуха", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Понять, что вам говорят: диктант и реплики продавцов, врачей, чиновников — на обычной скорости и быстрее, один раз.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

enum class ListenMode(val label: String) { DICTATION("Диктант"), UNDERSTAND("Понять на слух") }

class ListenVm : ViewModel() {
    private val app = LecturaApp.instance
    val clips = ClipPlayer(app)
    var mode by mutableStateOf(ListenMode.UNDERSTAND)
    var speed by mutableStateOf(1f)
    var once by mutableStateOf(false)
    var started by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var dictation by mutableStateOf<List<ListenItem>>(emptyList())
    var questions by mutableStateOf<List<ListenQuestion>>(emptyList())
    var index by mutableIntStateOf(0)
    var correct by mutableIntStateOf(0)
    /** Сколько раз прослушана текущая фраза. */
    var plays by mutableIntStateOf(0)
    private var job: Job? = null

    val total: Int get() = if (mode == ListenMode.DICTATION) dictation.size else questions.size

    fun start() = viewModelScope.launch {
        loading = true
        index = 0
        correct = 0
        plays = 0
        if (mode == ListenMode.DICTATION) dictation = Listening.dictation() else questions = Listening.comprehension()
        loading = false
        started = true
    }

    fun play(item: ListenItem) {
        if (once && plays >= 1) return
        plays++
        job?.cancel()
        job = viewModelScope.launch { clips.play(item.clip, item.es, speed) }
    }

    /** Ответ на текущую фразу: статистика дня; ошибка по фразе из повторения возвращает её в повторение. */
    fun answered(item: ListenItem, ok: Boolean) {
        if (ok) correct++
        viewModelScope.launch {
            runCatching {
                app.userData.recordFreeExercise(ok)
                item.word?.let { app.userData.recordPractice(it, ok) }
            }
        }
    }

    fun next() {
        job?.cancel()
        clips.stop()
        plays = 0
        index++
    }

    override fun onCleared() = clips.release()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(onBack: () -> Unit) {
    val vm: ListenVm = viewModel { ListenVm() }
    DisposableEffect(Unit) { onDispose { vm.clips.stop() } }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Тренажёр слуха")
                        if (vm.started) Text("${vm.mode.label} · ${minOf(vm.index + 1, vm.total)} из ${vm.total}", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (vm.started) vm.started = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !vm.started -> Setup(vm)
                vm.index >= vm.total -> Finish(vm)
                vm.mode == ListenMode.DICTATION -> Dictation(vm, vm.dictation[vm.index])
                else -> Understand(vm, vm.questions[vm.index])
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(vm: ListenVm) {
    Text("Что тренируем", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ListenMode.entries.forEach { m -> FilterChip(selected = vm.mode == m, onClick = { vm.mode = m }, label = { Text(m.label) }) }
    }
    Text(
        when (vm.mode) {
            ListenMode.DICTATION -> "Звучит фраза — напишите, что услышали. Видно, какие слова вы пропустили."
            ListenMode.UNDERSTAND -> "Звучит то, что вам скажет продавец, врач, чиновник, — выберите, что это значит. Текст — только после ответа."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Скорость", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.8f to "0.8 медленнее", 1f to "1.0 обычная", 1.2f to "1.2 быстрая речь").forEach { (v, label) ->
            FilterChip(selected = vm.speed == v, onClick = { vm.speed = v }, label = { Text(label) })
        }
    }
    FilterChip(selected = vm.once, onClick = { vm.once = !vm.once }, label = { Text("Один раз, без повтора — как в жизни") })
    Button(onClick = { vm.start() }, enabled = !vm.loading, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Начать (${Listening.SESSION})")
    }
}

@Composable
private fun PlayButton(vm: ListenVm, item: ListenItem) {
    LaunchedEffect(vm.index) {
        delay(300)
        vm.play(item)
    }
    val blocked = vm.once && vm.plays >= 1
    OutlinedButton(onClick = { vm.play(item) }, enabled = !blocked, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
        Text(if (blocked) "  Только один раз" else "  Послушать ещё раз")
    }
}

@Composable
private fun Dictation(vm: ListenVm, item: ListenItem) {
    var text by remember(vm.index) { mutableStateOf("") }
    var checked by remember(vm.index) { mutableStateOf(false) }
    PlayButton(vm, item)
    OutlinedTextField(
        value = text,
        onValueChange = { if (!checked) text = it },
        label = { Text("Что вы услышали?") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!checked && text.isNotBlank()) checked = true }),
    )
    if (!checked) {
        Button(onClick = { checked = true }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Проверить") }
        return
    }
    val exact = TextMatch.same(text, item.es)
    val loose = TextMatch.sameLoose(text, item.es)
    val match = remember(vm.index) { SpeechMatch.compare(item.es, listOf(text)) }
    val ok = exact || loose || match.passed
    LaunchedEffect(vm.index) { vm.answered(item, ok) }
    val (title, color) = when {
        exact -> "Верно!" to Good
        loose -> "Почти: проверьте ударения" to Warn
        match.passed -> "Почти: пропущено немного" to Warn
        else -> "Расслышано ${(match.score * 100).toInt()}% слов" to Bad
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold)
            // Эталон: услышанные слова — обычные, пропущенные — красным и подчёркнуты.
            Text(
                buildAnnotatedString {
                    match.words.forEachIndexed { i, w ->
                        if (i > 0) append(' ')
                        if (w.matched) {
                            append(w.word)
                        } else {
                            withStyle(SpanStyle(color = Bad, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(w.word) }
                        }
                    }
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(item.ru, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = {
                vm.plays = 0
                vm.play(item)
            }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Text("  Послушать с текстом")
            }
        }
    }
    Button(onClick = vm::next, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Дальше") }
}

@Composable
private fun Understand(vm: ListenVm, q: ListenQuestion) {
    var chosen by remember(vm.index) { mutableStateOf<Int?>(null) }
    PlayButton(vm, q.item)
    Text("Что вам сказали?", style = MaterialTheme.typography.titleLarge)
    OptionList(q.options, q.answer, chosen) { i ->
        chosen = i
        vm.answered(q.item, i == q.answer)
    }
    if (chosen != null) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Row(Modifier.padding(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(q.item.es, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(q.item.ru, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = {
                    vm.plays = 0
                    vm.play(q.item)
                }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Послушать") }
            }
        }
        Button(onClick = vm::next, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Дальше") }
    }
}

@Composable
private fun Finish(vm: ListenVm) {
    Text("Готово! 🎉", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text("Верно: ${vm.correct} из ${vm.total}", style = MaterialTheme.typography.titleMedium)
    Text(
        when {
            vm.correct * 10 >= vm.total * 8 && vm.speed < 1.2f -> "Отлично — попробуйте скорость быстрее или «один раз»."
            vm.correct * 2 < vm.total && vm.speed > 0.8f -> "Трудно — попробуйте помедленнее, а потом снова обычную скорость."
            else -> "Повторяйте каждый день — понимание на слух растёт быстрее всего от регулярности."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.started = false }, modifier = Modifier.weight(1f)) { Text("Настройки") }
        Button(onClick = { vm.start() }, modifier = Modifier.weight(1f)) { Text("Ещё раз") }
    }
}
