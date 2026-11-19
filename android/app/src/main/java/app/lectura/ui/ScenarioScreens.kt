package app.lectura.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.data.ScenarioRepository
import app.lectura.data.ScenarioState
import app.lectura.data.ScenarioStep
import app.lectura.model.Scenario
import app.lectura.model.ScenarioDialog
import app.lectura.model.ScenarioLine
import app.lectura.playback.ClipPlayer
import app.lectura.review.Bad
import app.lectura.review.Good
import app.lectura.review.OptionList
import app.lectura.speech.MatchResult
import app.lectura.speech.SpeakButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ общие

@Composable
private fun rememberScenario(id: String): Scenario? {
    val s by produceState<Scenario?>(null, id) { value = LecturaApp.instance.scenarios.get(id) }
    return s
}

@Composable
fun rememberScenarios(): List<Scenario> {
    val list by produceState(emptyList<Scenario>()) { value = LecturaApp.instance.scenarios.all() }
    return list
}

/** Состояния всех сценариев (из Room, обновляются сами). */
@Composable
fun rememberScenarioStates(scenarios: List<Scenario>): Map<String, ScenarioState> {
    val progress by LecturaApp.instance.db.scenarios().all().collectAsState(initial = emptyList())
    return remember(scenarios, progress) { scenarios.associate { it.id to ScenarioState.of(it, progress) } }
}

private fun record(scenarioId: String, step: ScenarioStep, percent: Int) {
    val app = LecturaApp.instance
    app.applicationScope.launch { runCatching { app.userData.recordScenarioStep(scenarioId, step.key, percent) } }
}

/** Карточка сценария в списке. */
@Composable
fun ScenarioCard(s: Scenario, state: ScenarioState?, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium)
                Text(s.canDo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state != null && state.started && !state.canDo) {
                    LinearProgressIndicator(
                        progress = { state.doneCount.toFloat() / state.steps.size },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                state?.canDo == true -> Text("✓ Могу", color = Good, fontWeight = FontWeight.SemiBold)
                state != null && state.started -> Text("${state.doneCount}/${state.steps.size}", style = MaterialTheme.typography.labelLarge)
                else -> Text(s.level, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ обзор сценария

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioScreen(id: String, onBack: () -> Unit, onStep: (ScenarioStep) -> Unit) {
    val s = rememberScenario(id)
    val states = rememberScenarioStates(listOfNotNull(s))
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(s?.let { "${it.emoji} ${it.title}" } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        val sc = s ?: return@Scaffold Loading(Modifier.padding(pad))
        val st = states[sc.id] ?: ScenarioState.of(sc, emptyList())
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sc.situation, style = MaterialTheme.typography.bodyLarge)
                        Text("Собеседник: ${sc.them.role}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (st.canDo) "✓ ${sc.canDo}" else "Цель: ${sc.canDo}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (st.canDo) Good else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            st.next?.let { next ->
                item {
                    Button(onClick = { onStep(next) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(if (st.started) "Продолжить: ${next.title}" else "Начать: ${next.title}")
                    }
                }
            }
            items(st.steps, key = { it.key }) { step ->
                val best = st.best[step]
                Card(onClick = { onStep(step) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (st.done(step)) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            null,
                            tint = if (st.done(step)) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${step.emoji} ${step.title}", style = MaterialTheme.typography.titleSmall)
                            Text(stepHint(step, sc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (best != null) Text("$best%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private fun stepHint(step: ScenarioStep, s: Scenario): String = when (step) {
    ScenarioStep.PHRASES -> "${s.phrases.size} фраз: послушать и повторить"
    ScenarioStep.LISTEN -> "Сначала без текста — что поймёте на слух"
    ScenarioStep.ROLE_ME -> "Вы говорите свои реплики, ${s.them.role.lowercase()} отвечает"
    ScenarioStep.ROLE_THEM -> "Теперь вы — ${s.them.role.lowercase()}: так легче понять, что вам скажут"
    ScenarioStep.TWIST -> s.dialogs.getOrNull(1)?.title ?: "Всё идёт не по плану"
    ScenarioStep.UNDERSTAND -> "${s.understand.size} фраз на слух: что вам говорят?"
    ScenarioStep.ANSWER -> "${s.prompts.size} вопросов — отвечайте своими словами"
}

// ------------------------------------------------------------------ шаг

@Composable
fun ScenarioStepScreen(id: String, stepKey: String, onBack: () -> Unit) {
    val s = rememberScenario(id)
    val step = ScenarioStep.of(stepKey)
    if (s == null || step == null) {
        Loading(Modifier)
        return
    }
    when (step) {
        ScenarioStep.PHRASES -> PhraseTrainer("${s.emoji} ${s.title}: фразы", s.phrases, onBack) { record(s.id, step, it) }
        ScenarioStep.LISTEN -> DialogScreen(s, s.dialogs[0], step, mySide = null, onBack)
        ScenarioStep.ROLE_ME -> DialogScreen(s, s.dialogs[0], step, mySide = ScenarioLine.ME, onBack)
        ScenarioStep.ROLE_THEM -> DialogScreen(s, s.dialogs[0], step, mySide = ScenarioLine.THEM, onBack)
        ScenarioStep.TWIST -> DialogScreen(s, s.dialogs.getOrElse(1) { s.dialogs[0] }, step, mySide = ScenarioLine.ME, onBack)
        ScenarioStep.UNDERSTAND -> UnderstandScreen(s, onBack)
        ScenarioStep.ANSWER -> AnswerScreen(s, onBack)
    }
}

/** Плеер и скорость для шагов сценария. */
class ScenarioVm : ViewModel() {
    val clips = ClipPlayer(LecturaApp.instance)
    var slow by mutableStateOf(false)
    val rate: Float get() = if (slow) 0.75f else 1f
    private var job: Job? = null

    fun say(clip: String?, text: String) {
        job?.cancel()
        job = viewModelScope.launch { clips.play(clip, text, rate) }
    }

    suspend fun sayAndWait(clip: String?, text: String): Boolean {
        job?.cancel()
        return clips.play(clip, text, rate)
    }

    fun silence() {
        job?.cancel()
        clips.stop()
    }

    override fun onCleared() = clips.release()
}

@Composable
private fun SlowChip(vm: ScenarioVm) {
    FilterChip(selected = vm.slow, onClick = { vm.slow = !vm.slow }, label = { Text("🐢 Медленнее") })
}

/**
 * Диалог сценария. [mySide] == null — просто слушать (текст скрыт, можно открыть);
 * иначе реплики этой стороны говорите вы (подсказка по-русски), остальные звучат сами.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DialogScreen(s: Scenario, d: ScenarioDialog, step: ScenarioStep, mySide: String?, onBack: () -> Unit) {
    val vm: ScenarioVm = viewModel(key = "${s.id}-${step.key}") { ScenarioVm() }
    val repo = LecturaApp.instance.scenarios
    var pos by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var showText by remember { mutableStateOf(mySide != null) }
    val results = remember { mutableStateMapOf<Int, MatchResult>() }
    val lines = d.lines
    val finished = pos >= lines.size
    val cur = lines.getOrNull(pos)
    val myTurn = cur != null && mySide != null && cur.who == mySide
    fun clip(i: Int) = repo.clip(ScenarioRepository.lineClip(s.id, d.id, i))
    DisposableEffect(Unit) { onDispose { vm.silence() } }

    // Чужие реплики звучат сами, одна за другой.
    LaunchedEffect(pos, running) {
        if (!running || cur == null || myTurn) return@LaunchedEffect
        delay(350)
        if (vm.sayAndWait(clip(pos), cur.es)) pos++ else running = false
    }
    // Своя реплика удалась — дальше.
    LaunchedEffect(results[pos]) {
        if (results[pos]?.passed == true) {
            delay(1200)
            pos++
        }
    }
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        val mine = lines.count { it.who == mySide }
        record(s.id, step, if (mySide == null) 100 else results.values.count { it.passed } * 100 / mine.coerceAtLeast(1))
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(step.title)
                        Text(
                            when (mySide) {
                                null -> d.title
                                ScenarioLine.ME -> d.title
                                else -> "Вы — ${s.them.role.lowercase()}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                SlowChip(vm)
                if (mySide == null) FilterChip(selected = showText, onClick = { showText = !showText }, label = { Text("Текст") })
                Text(
                    "Нажмите на реплику — увидите перевод",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            val shown = (0 until minOf(pos + 1, lines.size)).filter { it < pos || !myTurn }
            val listState = rememberLazyListState()
            LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it }) { i ->
                    val l = lines[i]
                    Bubble(
                        line = l,
                        mine = if (mySide == null) l.isMe else l.who == mySide,
                        who = if (l.isMe) "Вы" else s.them.role,
                        showText = showText || i < pos && mySide != null,
                        active = i == pos && !finished,
                        result = results[i],
                        onPlay = { vm.say(clip(i), l.es) },
                    )
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    finished -> {
                        val mine = lines.count { it.who == mySide }
                        Text(
                            if (mySide == null) "Разговор прослушан ✓" else "Сказано: ${results.values.count { it.passed }} из $mine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                results.clear()
                                pos = 0
                                running = true
                            }, modifier = Modifier.weight(1f)) { Text("Ещё раз") }
                            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Готово") }
                        }
                    }
                    myTurn -> {
                        val line = cur!!
                        MyLine(line, results[pos], onPlay = { vm.say(clip(pos), line.es) }) { r ->
                            if (r.score >= (results[pos]?.score ?: -1f)) results[pos] = r
                        }
                        TextButton(onClick = { pos++ }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (results[pos] == null) "Пропустить ⏭" else "Дальше →")
                        }
                    }
                    running -> OutlinedButton(onClick = {
                        running = false
                        vm.silence()
                    }, modifier = Modifier.fillMaxWidth()) { Text("⏸ Пауза") }
                    else -> Button(onClick = { running = true }, modifier = Modifier.fillMaxWidth()) { Text("▶ Продолжить") }
                }
            }
        }
    }
}

/** Ваша реплика: перевод крупно, испанский — по подсказке, микрофон. */
@Composable
private fun MyLine(line: ScenarioLine, result: MatchResult?, onPlay: () -> Unit, onResult: (MatchResult) -> Unit) {
    var hint by remember(line) { mutableStateOf(false) }
    Text("Ваша реплика", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(line.ru, style = MaterialTheme.typography.titleLarge)
    if (result == null) {
        if (hint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.es, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                IconButton(onClick = onPlay) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Послушать") }
            }
        } else {
            TextButton(onClick = { hint = true }, contentPadding = PaddingValues(0.dp)) { Text("💡 Подсказка") }
        }
    }
    key(line) { SpeakButton(expected = line.es, modifier = Modifier.fillMaxWidth(), onResult = onResult) }
}

@Composable
private fun Bubble(
    line: ScenarioLine,
    mine: Boolean,
    who: String,
    showText: Boolean,
    active: Boolean,
    result: MatchResult?,
    onPlay: () -> Unit,
) {
    var ru by remember(line) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (mine) colors.primaryContainer else colors.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            border = if (active) BorderStroke(2.dp, colors.primary) else null,
            modifier = Modifier.widthIn(max = 320.dp).clickable { ru = !ru },
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(who, style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.weight(1f, fill = false))
                    result?.let {
                        Text(
                            if (it.passed) "  ✓ ${(it.score * 100).toInt()}%" else "  ${(it.score * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (it.passed) Good else Bad,
                        )
                    }
                }
                Text(if (showText) line.es else "🔊 · · ·", style = MaterialTheme.typography.bodyLarge)
                if (ru) Text(line.ru, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                TextButton(onClick = onPlay, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                    Text("  Послушать", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ понять ответ

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnderstandScreen(s: Scenario, onBack: () -> Unit) {
    val vm: ScenarioVm = viewModel(key = "${s.id}-understand") { ScenarioVm() }
    val repo = LecturaApp.instance.scenarios
    var index by remember { mutableIntStateOf(0) }
    var correct by remember { mutableIntStateOf(0) }
    val items = s.understand
    DisposableEffect(Unit) { onDispose { vm.silence() } }
    StepScaffold(ScenarioStep.UNDERSTAND.title, "${minOf(index + 1, items.size)} из ${items.size}", index, items.size, onBack) {
        if (index >= items.size) {
            LaunchedEffect(Unit) { record(s.id, ScenarioStep.UNDERSTAND, correct * 100 / items.size) }
            Finish("Понято: $correct из ${items.size}", onAgain = {
                index = 0
                correct = 0
            }, onBack = onBack)
            return@StepScaffold
        }
        val u = items[index]
        val clip = repo.clip(ScenarioRepository.understandClip(s.id, index))
        var chosen by remember(index) { mutableStateOf<Int?>(null) }
        // Варианты в случайном порядке — чтобы правильный не угадывался по месту.
        val order = remember(index) { u.options.indices.shuffled() }
        val answer = order.indexOf(u.answer)
        LaunchedEffect(index) {
            delay(300)
            vm.say(clip, u.es)
        }
        Text("${s.them.role} говорит:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.say(clip, u.es) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Text("  Ещё раз")
            }
            SlowChip(vm)
        }
        Text(u.question, style = MaterialTheme.typography.titleLarge)
        OptionList(order.map { u.options[it] }, answer, chosen) { i ->
            chosen = i
            if (i == answer) correct++
        }
        if (chosen != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(u.es, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(u.ru, style = MaterialTheme.typography.bodyMedium)
                    if (u.why.isNotBlank()) Text(u.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { index++ }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Дальше") }
        }
    }
}

// ------------------------------------------------------------------ ответить своими словами

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnswerScreen(s: Scenario, onBack: () -> Unit) {
    val vm: ScenarioVm = viewModel(key = "${s.id}-answer") { ScenarioVm() }
    val repo = LecturaApp.instance.scenarios
    var index by remember { mutableIntStateOf(0) }
    var passed by remember { mutableIntStateOf(0) }
    val items = s.prompts
    DisposableEffect(Unit) { onDispose { vm.silence() } }
    StepScaffold(ScenarioStep.ANSWER.title, "${minOf(index + 1, items.size)} из ${items.size}", index, items.size, onBack) {
        if (index >= items.size) {
            LaunchedEffect(Unit) { record(s.id, ScenarioStep.ANSWER, passed * 100 / items.size) }
            Finish("Ответов засчитано: $passed из ${items.size}", onAgain = {
                index = 0
                passed = 0
            }, onBack = onBack)
            return@StepScaffold
        }
        val p = items[index]
        val clip = repo.clip(ScenarioRepository.promptClip(s.id, index))
        var result by remember(index) { mutableStateOf<MatchResult?>(null) }
        var showText by remember(index) { mutableStateOf(false) }
        var counted by remember(index) { mutableStateOf(false) }
        fun count() {
            if (!counted) {
                counted = true
                passed++
            }
        }
        LaunchedEffect(index) {
            delay(300)
            vm.say(clip, p.es)
        }
        Text("${s.them.role} спрашивает:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.say(clip, p.es) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Text("  Ещё раз")
            }
            SlowChip(vm)
        }
        if (showText) {
            Text(p.es, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(p.ru, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = { showText = true }, contentPadding = PaddingValues(0.dp)) { Text("Показать текст и перевод") }
        }
        if (p.hint.isNotBlank()) Text("💡 ${p.hint}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        key(index) {
            SpeakButton(expected = p.accepted.first(), accepted = p.accepted, modifier = Modifier.fillMaxWidth()) { r ->
                result = r
                if (r.passed) count()
            }
        }
        val r = result
        if (r != null && !r.passed) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Можно ответить, например:", style = MaterialTheme.typography.labelLarge)
                    p.accepted.forEachIndexed { k, a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("• $a", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                vm.say(if (k == 0) repo.clip(ScenarioRepository.answerClip(s.id, index)) else null, a)
                            }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Послушать") }
                        }
                    }
                    if (!counted) {
                        TextButton(onClick = { count() }) { Text("Мой ответ тоже правильный — засчитать") }
                    }
                }
            }
        }
        if (r != null) {
            Button(onClick = { index++ }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Дальше") }
        } else {
            TextButton(onClick = { index++ }, modifier = Modifier.fillMaxWidth()) { Text("Пропустить ⏭") }
        }
    }
}

// ------------------------------------------------------------------ каркас шага

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepScaffold(
    title: String,
    subtitle: String,
    index: Int,
    total: Int,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(subtitle, style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinearProgressIndicator(progress = { index.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            content()
        }
    }
}

@Composable
private fun Finish(text: String, onAgain: () -> Unit, onBack: () -> Unit) {
    Text("Готово! 🎉", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(text, style = MaterialTheme.typography.titleMedium)
    Text(
        "Шаг засчитывается от ${ScenarioState.DONE_PCT}%.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Ещё раз") }
        Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("К сценарию") }
    }
}
