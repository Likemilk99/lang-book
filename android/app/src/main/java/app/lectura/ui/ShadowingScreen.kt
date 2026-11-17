package app.lectura.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.model.Book
import app.lectura.model.Chapter
import app.lectura.model.Sentence
import app.lectura.speech.MatchResult
import app.lectura.speech.SpeakButton
import app.lectura.speech.SpeechTts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Повторение за диктором и ролевая игра по главе.
// mode: "shadow" — повторять каждое предложение; "role" — произносить реплики выбранного персонажа.

private val RATE_CHIPS = listOf(0.6f, 0.8f, 1.0f)

class ShadowingViewModel(app: LecturaApp, bookId: String) : ViewModel() {
    val tts = SpeechTts(app)

    var book by mutableStateOf<Book?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var voiceMissing by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var rate by mutableFloatStateOf(RATE_CHIPS.minBy { abs(it - app.player.state.value.rate) })

    // Повторение за диктором
    var index by mutableIntStateOf(0)
    var hideText by mutableStateOf(false)
    var autoAdvance by mutableStateOf(true)
    /** Лучший результат по каждой фразе (индекс в списке фраз → доля 0..1). */
    val best = mutableStateMapOf<Int, Float>()

    // Ролевая игра
    var character by mutableStateOf<String?>(null)
    /** Текущее предложение главы (индекс в Chapter.sentences); sentences.size — сцена окончена. */
    var pos by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var onlyDialogues by mutableStateOf(false)
    val roleResults = mutableStateMapOf<Int, MatchResult>()

    private var sayJob: Job? = null
    private var sayToken = 0

    init {
        viewModelScope.launch {
            runCatching { app.books.load(bookId) }
                .onSuccess {
                    tts.setCharacters(it.characters)
                    book = it
                }
                .onFailure { error = it.message ?: it.toString() }
        }
        viewModelScope.launch {
            tts.awaitReady()
            voiceMissing = tts.voiceMissing
        }
    }

    /** Произнести, не дожидаясь (кнопки «послушать»). */
    fun say(text: String, rate: Float, speaker: String?) {
        sayJob?.cancel()
        sayJob = viewModelScope.launch { sayAndWait(text, rate, speaker) }
    }

    /** Произнести и дождаться конца. false — речь прервана или TTS недоступен. */
    suspend fun sayAndWait(text: String, rate: Float, speaker: String?): Boolean {
        val token = ++sayToken
        speaking = true
        try {
            return tts.speak(text, rate, speaker)
        } finally {
            if (token == sayToken) speaking = false
        }
    }

    fun silence() {
        sayJob?.cancel()
        tts.stop()
    }

    fun startRole(name: String, sentences: List<Sentence>) {
        silence()
        character = name
        roleResults.clear()
        pos = nextLine(sentences, -1, onlyDialogues)
        running = true
    }

    override fun onCleared() {
        tts.shutdown()
    }
}

private fun Sentence.speaker(): String? = sp?.trim()?.takeIf { it.isNotEmpty() }

private fun isSpeakable(s: Sentence) = s.tokens.any { it.isWord }

private fun isLine(s: Sentence, onlyDialogues: Boolean) = isSpeakable(s) && (!onlyDialogues || s.speaker() != null)

/** Следующая реплика после `from` (или sentences.size, если сцена кончилась). */
private fun nextLine(sentences: List<Sentence>, from: Int, onlyDialogues: Boolean): Int =
    (from + 1 until sentences.size).firstOrNull { isLine(sentences[it], onlyDialogues) } ?: sentences.size

private fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowingScreen(bookId: String, chapter: Int, mode: String, onBack: () -> Unit) {
    val app = LecturaApp.instance
    val vm: ShadowingViewModel = viewModel(key = "speak/$bookId/$chapter/$mode") { ShadowingViewModel(app, bookId) }
    val role = mode == "role"
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { app.player.pause() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                vm.running = false
                vm.silence()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val book = vm.book
    val ch = book?.chapters?.getOrNull(chapter)
    val phrases = remember(ch) { ch?.sentences?.filter(::isSpeakable).orEmpty() }

    val subtitle = when {
        ch == null -> ""
        role -> {
            val only = vm.onlyDialogues
            val total = ch.sentences.count { isLine(it, only) }
            val done = ch.sentences.withIndex().count { (i, s) -> i <= vm.pos && isLine(s, only) }
            if (vm.character == null) "Ролевая игра" else "Ролевая игра · ${done.coerceAtMost(total)}/$total"
        }
        else -> "Повторение за диктором · ${(vm.index + 1).coerceAtMost(phrases.size)}/${phrases.size}"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ch?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.silence()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (vm.voiceMissing) Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Не установлен испанский голос (Испания).", Modifier.weight(1f))
                    TextButton(onClick = {
                        ctx.startActivity(
                            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("Установить") }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    vm.error != null -> Text("Ошибка: ${vm.error}", Modifier.padding(24.dp))
                    book == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    ch == null -> Text("Глава не найдена", Modifier.padding(24.dp))
                    role -> RoleMode(vm, book, ch, onBack)
                    else -> ShadowMode(vm, phrases, onBack)
                }
            }
        }
    }
}

// ───────────────────────── Повторение за диктором ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ShadowMode(vm: ShadowingViewModel, phrases: List<Sentence>, onBack: () -> Unit) {
    if (phrases.isEmpty()) {
        CenteredMessage("В этой главе нет фраз для повторения.", onBack)
        return
    }
    val i = vm.index.coerceIn(0, phrases.size)
    val passedCount = vm.best.values.count { it >= MatchResult.PASS_SCORE }
    if (i == phrases.size) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Глава пройдена! 🎉", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Зачтено $passedCount из ${phrases.size} ${plural(phrases.size, "фразы", "фраз", "фраз")}")
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                vm.best.clear()
                vm.index = 0
            }) { Text("Ещё раз с начала") }
            TextButton(onClick = { vm.index = phrases.size - 1 }) { Text("← К последней фразе") }
            OutlinedButton(onClick = onBack) { Text("Готово") }
        }
        return
    }

    val s = phrases[i]
    var result by remember(i) { mutableStateOf<MatchResult?>(null) }
    var showRu by remember(i) { mutableStateOf(false) }
    var reveal by remember(i) { mutableStateOf(false) }

    // Сначала диктор читает фразу.
    LaunchedEffect(i) {
        delay(400)
        vm.sayAndWait(s.text, vm.rate, s.speaker())
    }
    // Автопереход после удачной попытки.
    LaunchedEffect(result) {
        if (result?.passed == true && vm.autoAdvance) {
            delay(1600)
            vm.index = i + 1
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = vm.hideText, onClick = { vm.hideText = !vm.hideText }, label = { Text("Скрыть текст") })
            FilterChip(selected = vm.autoAdvance, onClick = { vm.autoAdvance = !vm.autoAdvance }, label = { Text("Автопереход") })
            RateChips(vm)
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable { showRu = !showRu },
        ) {
            Column(Modifier.padding(16.dp)) {
                s.speaker()?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (vm.hideText && result == null && !reveal) {
                    Text("🎧 Текст скрыт — слушайте и повторяйте", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { reveal = true }, contentPadding = PaddingValues(0.dp)) { Text("Показать текст") }
                } else {
                    Text(s.text, style = MaterialTheme.typography.headlineSmall)
                }
                if (showRu) {
                    Text(
                        s.ru.ifBlank { "(перевода нет)" },
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Нажмите — перевод",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { vm.say(s.text, vm.rate, s.speaker()) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Spacer(Modifier.width(6.dp))
                Text("Послушать ещё")
            }
            OutlinedButton(onClick = { vm.say(s.text, minOf(0.6f, vm.rate * 0.75f), s.speaker()) }) {
                Text("🐢 Медленно")
            }
        }

        key(i) {
            SpeakButton(expected = s.text, modifier = Modifier.fillMaxWidth()) { r ->
                result = r
                vm.best[i] = maxOf(vm.best[i] ?: 0f, r.score)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.index = i - 1 }, enabled = i > 0) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.index = i + 1 }) { Text(if (i + 1 < phrases.size) "Дальше →" else "Завершить") }
        }
        Text(
            "Зачтено: $passedCount из ${phrases.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RateChips(vm: ShadowingViewModel) {
    RATE_CHIPS.forEach { r ->
        FilterChip(selected = vm.rate == r, onClick = { vm.rate = r }, label = { Text("$r×") })
    }
}

// ───────────────────────── Ролевая игра ─────────────────────────

@Composable
private fun RoleMode(vm: ShadowingViewModel, book: Book, ch: Chapter, onBack: () -> Unit) {
    val sentences = ch.sentences
    val speakers = remember(ch) { sentences.mapNotNull { it.speaker() }.distinct() }
    if (speakers.isEmpty()) {
        val bookHasDialogue = remember(book) { book.chapters.any { c -> c.sentences.any { it.speaker() != null } } }
        CenteredMessage(
            if (bookHasDialogue) {
                "В этой главе нет диалогов. Выберите другую главу — или потренируйтесь в режиме «Повторение за диктором»."
            } else {
                "В этой книге пока нет разметки диалогов (кто какую реплику говорит), поэтому ролевая игра недоступна.\n\n" +
                    "Книгу нужно обновить: подготовьте её заново на Mac (prepare_book.py) и загрузите на телефон. " +
                    "А пока можно потренироваться в режиме «Повторение за диктором»."
            },
            onBack,
        )
        return
    }
    val candidates = remember(book, speakers) {
        (book.characters.map { it.name.trim() }.filter { it in speakers } + speakers).distinct()
    }
    val me = vm.character
    if (me == null || me !in candidates) {
        CharacterPicker(book, sentences, candidates) { vm.startRole(it, sentences) }
        return
    }
    RoleRun(vm, sentences, me, onBack)
}

@Composable
private fun CharacterPicker(book: Book, sentences: List<Sentence>, candidates: List<String>, onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Выберите роль", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Вы будете говорить реплики этого персонажа (подсказка — по-русски), остальных озвучит телефон.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidates.forEach { name ->
            val count = sentences.count { it.speaker() == name && isSpeakable(it) }
            val gender = book.characters.firstOrNull { it.name.trim() == name }?.gender
            val sign = when (gender) {
                "m" -> " ♂"
                "f" -> " ♀"
                else -> ""
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onPick(name) },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name + sign, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "$count ${plural(count, "реплика", "реплики", "реплик")}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RoleRun(vm: ShadowingViewModel, sentences: List<Sentence>, me: String, onBack: () -> Unit) {
    val n = sentences.size
    val only = vm.onlyDialogues
    val pos = vm.pos
    val cur = sentences.getOrNull(pos)
    val curIsLine = cur != null && isLine(cur, only)
    val myTurn = cur != null && curIsLine && cur.speaker() == me
    val myResult = vm.roleResults[pos]
    val lines = remember(sentences, only) { sentences.indices.filter { isLine(sentences[it], only) } }
    val shown = lines.filter { it < pos || (it == pos && !myTurn) }

    // Переключили «Только диалоги» и текущее предложение выпало — сдвигаемся к следующей реплике.
    LaunchedEffect(pos, only) {
        if (cur != null && !curIsLine) vm.pos = nextLine(sentences, pos, only)
    }
    // Чужие реплики и авторский текст звучат сами, одна за другой.
    LaunchedEffect(pos, vm.running, only, me) {
        if (!vm.running || cur == null || !curIsLine || myTurn) return@LaunchedEffect
        delay(300)
        val ok = vm.sayAndWait(cur.text, vm.rate, cur.speaker())
        if (!ok) {
            vm.running = false
            return@LaunchedEffect
        }
        delay(250)
        vm.pos = nextLine(sentences, pos, only)
    }
    // Своя реплика удалась — продолжаем сами.
    LaunchedEffect(myResult) {
        if (myResult?.passed == true && vm.running) {
            delay(1800)
            vm.pos = nextLine(sentences, pos, only)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(selected = only, onClick = { vm.onlyDialogues = !only }, label = { Text("Только диалоги") })
            RateChips(vm)
            TextButton(onClick = {
                vm.running = false
                vm.silence()
                vm.character = null
            }) { Text("Роль: $me ✎") }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it }) { idx ->
                LineBubble(
                    s = sentences[idx],
                    me = me,
                    active = idx == pos && vm.speaking,
                    result = vm.roleResults[idx],
                )
            }
        }

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                when {
                    pos >= n -> {
                        val mine = lines.count { sentences[it].speaker() == me }
                        val passed = vm.roleResults.values.count { it.passed }
                        Text("Сцена окончена! 🎭", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Зачтено реплик: $passed из $mine")
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.startRole(me, sentences) }) { Text("Ещё раз") }
                            OutlinedButton(onClick = { vm.character = null }) { Text("Другая роль") }
                            TextButton(onClick = onBack) { Text("Готово") }
                        }
                    }
                    myTurn -> MyTurnPanel(vm, pos, cur, me, myResult) {
                        vm.pos = nextLine(sentences, pos, only)
                        vm.running = true
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.running) {
                            OutlinedButton(onClick = {
                                vm.running = false
                                vm.silence()
                            }) { Text("⏸ Пауза") }
                        } else {
                            Button(onClick = { vm.running = true }) { Text("▶ Продолжить") }
                            if (cur != null) OutlinedButton(onClick = { vm.say(cur.text, vm.rate, cur.speaker()) }) {
                                Text("🔁 Ещё раз")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.pos = nextLine(sentences, pos, only) }) { Text("Дальше ⏭") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyTurnPanel(vm: ShadowingViewModel, index: Int, s: Sentence, me: String, result: MatchResult?, onNext: () -> Unit) {
    var hint by remember(index) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ваша реплика · $me", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (s.ru.isNotBlank()) Text(s.ru, style = MaterialTheme.typography.titleLarge)
        if (result == null) {
            if (hint || s.ru.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton(onClick = { vm.say(s.text, vm.rate, null) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Послушать")
                    }
                }
            } else {
                TextButton(onClick = { hint = true }, contentPadding = PaddingValues(0.dp)) { Text("💡 Подсказка") }
            }
        }
        // Результат (испанская фраза с подсветкой слов) показывает сам SpeakButton.
        key(index) {
            SpeakButton(expected = s.text, modifier = Modifier.fillMaxWidth()) { r -> vm.roleResults[index] = r }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNext) { Text(if (result == null) "Пропустить ⏭" else "Дальше →") }
        }
    }
}

@Composable
private fun LineBubble(s: Sentence, me: String, active: Boolean, result: MatchResult?) {
    var showRu by remember(s) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val who = s.speaker()
    val mine = who == me
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = when {
            who == null -> Alignment.CenterHorizontally
            mine -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        if (who != null) Text(
            if (mine) "$who (вы)" else who,
            style = MaterialTheme.typography.labelMedium,
            color = if (mine) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Surface(
            color = when {
                active -> colors.tertiaryContainer
                who == null -> Color.Transparent
                mine -> colors.primaryContainer
                else -> colors.surfaceContainerHigh
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 320.dp).clickable { showRu = !showRu },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    s.text,
                    style = if (who == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    fontStyle = if (who == null) FontStyle.Italic else null,
                    textAlign = if (who == null) TextAlign.Center else null,
                    color = if (who == null && !active) colors.onSurfaceVariant else Color.Unspecified,
                )
                if (showRu && s.ru.isNotBlank()) Text(
                    s.ru,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (mine) {
                    val pct = result?.let { (it.score * 100).roundToInt() }
                    Text(
                        when {
                            result == null -> "пропущено"
                            result.passed -> "✓ $pct%"
                            else -> "✗ $pct%"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (result?.passed == true) colors.primary else colors.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onBack) { Text("Назад") }
    }
}
