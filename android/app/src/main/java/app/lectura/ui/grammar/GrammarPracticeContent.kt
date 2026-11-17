package app.lectura.ui.grammar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.data.GrammarProgress
import app.lectura.review.Bad
import app.lectura.review.DontKnow
import app.lectura.review.Good
import app.lectura.review.Instruction
import app.lectura.review.OptionList
import app.lectura.review.Warn

/** Сессия практики. vmKey различает сессии, открытые внутри одной записи навигации. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GrammarPracticeContent(topicId: String, onBack: () -> Unit, vmKey: String = "grammar-practice:$topicId") {
    val app = LecturaApp.instance
    val player = app.player
    val vm: GrammarPracticeVm = viewModel(key = vmKey) { GrammarPracticeVm(topicId) }
    LaunchedEffect(Unit) { vm.ensureStarted() }
    DisposableEffect(Unit) { onDispose { player.stopSpeech() } }
    val exit = {
        player.stopSpeech()
        onBack()
    }

    Scaffold(
        // Любое касание сразу обрывает озвучку (событие не поглощается).
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                player.stopSpeech()
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(vm.topic?.title ?: "Практика", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            if (!vm.loading && !vm.finished && vm.items.isNotEmpty()) {
                                Text(
                                    "Упражнение ${vm.pos + 1} из ${vm.items.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = exit) { Icon(Icons.Filled.Close, "Закрыть") } },
                )
                if (!vm.loading && !vm.finished && vm.items.isNotEmpty()) {
                    val done = vm.pos + (if (vm.current != null) 1 else 0)
                    LinearProgressIndicator(
                        progress = { done / vm.items.size.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal = 16.dp).padding(bottom = 12.dp),
        ) {
            when {
                vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                vm.items.isEmpty() -> EmptyBank(vm.topic == null, exit)
                vm.finished -> FinishedView(vm, onAgain = { vm.start() }, onTopic = exit)
                else -> {
                    val item = vm.items[vm.pos]
                    key(item.id) { ExerciseView(vm, item) }
                }
            }
        }
    }
}

@Composable
private fun EmptyBank(noTopic: Boolean, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (noTopic) "Тема не найдена" else "Для этой темы пока нет упражнений",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("К теме") }
    }
}

@Composable
private fun ColumnScope.ExerciseView(vm: GrammarPracticeVm, item: PracticeItem) {
    val result = vm.current
    val answered = result != null
    val ex = item.ex
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
        when (ex.type) {
            "choice" -> ChoiceView(item, result, vm::answer)
            "input" -> InputView(item, result, vm::answer)
            "order" -> OrderView(item, result, vm::answer)
            else -> TransformView(item, result, vm::answer)
        }
        if (result != null) FeedbackCard(result)
    }
    if (answered) {
        Button(
            onClick = { vm.next() },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp),
        ) {
            Text(if (vm.pos + 1 < vm.items.size) "Дальше" else "Итог", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------- Упражнения ----------

/** Предложение с пропуском(ами); после ответа на месте пропуска — правильная форма. */
@Composable
private fun GapSentence(sentence: String, answer: String, revealed: Boolean, correct: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val parts = GrammarLogic.gapParts(sentence)
    val fills = GrammarLogic.gapFills(sentence, answer)
    Text(
        buildAnnotatedString {
            parts.forEachIndexed { i, p ->
                append(p)
                if (i < parts.size - 1) {
                    if (revealed) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = if (correct) Good else accent)) {
                            append(fills.getOrElse(i) { answer })
                        }
                    } else {
                        withStyle(SpanStyle(color = accent)) { append("_____") }
                    }
                }
            }
        },
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PromptHint(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** Перевод: скрыт до нажатия (чтобы не подсказывать), после ответа — виден. */
@Composable
private fun RuReveal(ru: String, answered: Boolean) {
    if (ru.isBlank()) return
    var shown by rememberSaveable { mutableStateOf(false) }
    if (shown || answered) {
        Text(
            ru,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    } else {
        TextButton(onClick = { shown = true }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Показать перевод")
        }
    }
}

@Composable
private fun ChoiceView(item: PracticeItem, result: ItemResult?, onAnswer: (Verdict, String) -> Unit) {
    val ex = item.ex
    val options = remember(item.id) { GrammarLogic.choiceOptions(ex) }
    val correctIdx = options.indexOfFirst { GrammarLogic.isCorrectOption(ex, it) }
    val chosen = result?.let { r -> options.indexOf(r.given).takeIf { it >= 0 } ?: -1 }
    Instruction("Выберите правильный вариант")
    GapSentence(ex.sentence, ex.answer, revealed = result != null, correct = result?.correct == true)
    PromptHint(ex.prompt)
    RuReveal(ex.ru, result != null)
    Spacer(Modifier.height(16.dp))
    OptionList(options, correctIdx, chosen) { i ->
        onAnswer(if (GrammarLogic.isCorrectOption(ex, options[i])) Verdict.RIGHT else Verdict.WRONG, options[i])
    }
    if (result == null) DontKnow { onAnswer(Verdict.WRONG, "") }
}

@Composable
private fun TextAnswer(
    label: String,
    answered: Boolean,
    wrong: Boolean,
    onCheck: (String) -> Unit,
    onDontKnow: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!answered) runCatching { focus.requestFocus() } }
    val check = { if (text.isNotBlank() && !answered) onCheck(text.trim()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        enabled = !answered,
        isError = wrong,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.titleLarge,
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
        DontKnow(onDontKnow)
    }
}

@Composable
private fun InputView(item: PracticeItem, result: ItemResult?, onAnswer: (Verdict, String) -> Unit) {
    val ex = item.ex
    Instruction("Впишите пропущенное")
    GapSentence(ex.sentence, ex.answer, revealed = result != null, correct = result?.correct == true)
    PromptHint(ex.prompt)
    RuReveal(ex.ru, result != null)
    Spacer(Modifier.height(16.dp))
    TextAnswer(
        label = "Ответ по-испански",
        answered = result != null,
        wrong = result?.verdict == Verdict.WRONG,
        onCheck = { onAnswer(GrammarLogic.check(it, GrammarLogic.inputAccepted(ex)), it) },
        onDontKnow = { onAnswer(Verdict.WRONG, "") },
    )
}

@Composable
private fun TransformView(item: PracticeItem, result: ItemResult?, onAnswer: (Verdict, String) -> Unit) {
    val ex = item.ex
    Instruction(ex.prompt.ifBlank { "Измените предложение" })
    if (ex.sentence.isNotBlank()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                ex.sentence,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    RuReveal(ex.ru, result != null)
    Spacer(Modifier.height(16.dp))
    TextAnswer(
        label = "Новое предложение",
        answered = result != null,
        wrong = result?.verdict == Verdict.WRONG,
        onCheck = { onAnswer(GrammarLogic.check(it, GrammarLogic.transformAccepted(ex)), it) },
        onDontKnow = { onAnswer(Verdict.WRONG, "") },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderView(item: PracticeItem, result: ItemResult?, onAnswer: (Verdict, String) -> Unit) {
    val ex = item.ex
    val tiles = remember(item.id) { GrammarLogic.shuffledTiles(GrammarLogic.orderTiles(ex.answer)) }
    val placed = remember(item.id) { mutableStateListOf<Int>() }
    val answered = result != null
    Instruction(ex.prompt.ifBlank { "Соберите предложение" })
    if (ex.ru.isNotBlank()) {
        Text(ex.ru, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(16.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (result == null) MaterialTheme.colorScheme.outline else if (result.correct) Good else Bad),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        FlowRow(
            Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            placed.forEach { i -> Tile(tiles[i], enabled = !answered) { placed.remove(i) } }
        }
    }
    Spacer(Modifier.height(16.dp))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tiles.forEachIndexed { i, t ->
            val used = i in placed
            Tile(t, enabled = !used && !answered, modifier = Modifier.alpha(if (used) 0.25f else 1f)) { placed.add(i) }
        }
    }
    if (!answered) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { placed.clear() }, enabled = placed.isNotEmpty()) { Text("Сбросить") }
            Button(
                onClick = {
                    val built = placed.map { tiles[it] }
                    onAnswer(GrammarLogic.checkOrder(built, ex), built.joinToString(" "))
                },
                enabled = placed.size == tiles.size,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Проверить") }
        }
        DontKnow { onAnswer(Verdict.WRONG, "") }
    }
}

@Composable
private fun Tile(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

// ---------- Обратная связь ----------

@Composable
private fun FeedbackCard(r: ItemResult) {
    val player = LecturaApp.instance.player
    val ex = r.item.ex
    val full = GrammarLogic.fullSentence(ex)
    val (title, color) = when (r.verdict) {
        Verdict.RIGHT -> "Верно!" to Good
        Verdict.ACCENT -> "Верно, но проверьте ударения" to Warn
        Verdict.WRONG -> "Неверно" to Bad
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            if (r.verdict != Verdict.RIGHT && r.given.isNotBlank() && ex.type != "choice") {
                Text(
                    buildAnnotatedString {
                        append("Ваш ответ: ")
                        withStyle(SpanStyle(textDecoration = if (r.verdict == Verdict.WRONG) TextDecoration.LineThrough else null)) {
                            append(r.given)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (r.verdict != Verdict.RIGHT && (ex.type == "choice" || ex.type == "input")) {
                Text(
                    buildAnnotatedString {
                        append(if (r.verdict == Verdict.ACCENT) "Правильное написание: " else "Правильный ответ: ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(ex.answer) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Surface(
                onClick = { player.speakOnce(full) },
                color = Color.Transparent,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        full,
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
            if (ex.ru.isNotBlank()) Text(ex.ru, style = MaterialTheme.typography.bodyLarge)
            if (ex.explain.isNotBlank()) {
                Text(
                    richText(ex.explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// ---------- Итог ----------

@Composable
private fun FinishedView(vm: GrammarPracticeVm, onAgain: () -> Unit, onTopic: () -> Unit) {
    val total = vm.results.size
    val correct = vm.results.count { it.correct }
    val pct = if (total == 0) 0 else correct * 100 / total
    val mistakes = vm.results.filter { !it.correct }
    val accentSlips = vm.results.filter { it.verdict == Verdict.ACCENT }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$correct / $total", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = pctColor(pct))
                    Text("$pct% правильно", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            pct == 100 -> "Безупречно!"
                            pct >= 80 -> "Отлично — тема закрепляется"
                            pct >= 60 -> "Неплохо, но стоит повторить"
                            else -> "Тема пока даётся трудно — перечитайте объяснение"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            TopicOutcome(vm.before, vm.after, vm.saveError)
            if (mistakes.isNotEmpty()) {
                Text("Ошибки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                mistakes.forEach { MistakeRow(it) }
            }
            if (accentSlips.isNotEmpty()) {
                Text("Засчитано, но с ударениями", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                accentSlips.forEach { MistakeRow(it) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onTopic, modifier = Modifier.weight(1f).height(56.dp)) { Text("К теме") }
            Button(onClick = onAgain, enabled = vm.after != null || vm.saveError, modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Ещё 10")
            }
        }
    }
}

@Composable
private fun TopicOutcome(before: GrammarProgress?, after: GrammarProgress?, error: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Тема", style = MaterialTheme.typography.titleMedium)
            when {
                error -> Text("Не удалось сохранить результат", color = Bad)
                after == null -> Text("Сохраняем…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val from = before?.stage ?: 0
                    val to = after.stage
                    val (msg, color) = when {
                        to > from -> "Ступень повышена: $from → $to ↑" to Good
                        to < from -> "Ступень понижена: $from → $to ↓" to Bad
                        else -> "Ступень без изменений: $to" to MaterialTheme.colorScheme.onSurface
                    }
                    Text(msg, color = color, fontWeight = FontWeight.SemiBold)
                    StageDots(to)
                    if (after.mastered && before?.mastered != true) {
                        Text("Тема освоена! 🎉", color = Good, fontWeight = FontWeight.Bold)
                    } else if (!after.mastered && before?.mastered == true) {
                        Text("Тема больше не считается освоенной — повторите её", color = Warn)
                    }
                    Text("Следующее повторение: ${reviewDateText(after.due)}")
                }
            }
        }
    }
}

@Composable
private fun MistakeRow(r: ItemResult) {
    val ex = r.item.ex
    val player = LecturaApp.instance.player
    val full = GrammarLogic.fullSentence(ex)
    Surface(
        onClick = { player.speakOnce(full) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (ex.type == "transform" && ex.sentence.isNotBlank()) {
                Text(ex.sentence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (r.given.isBlank()) "✗ (не знаю)" else "✗ ${r.given}",
                color = if (r.verdict == Verdict.ACCENT) Warn else Bad,
                textDecoration = if (r.verdict == Verdict.WRONG && r.given.isNotBlank()) TextDecoration.LineThrough else null,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ $full", color = Good, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести", tint = MaterialTheme.colorScheme.primary)
            }
            if (ex.ru.isNotBlank()) Text(ex.ru, style = MaterialTheme.typography.bodySmall)
            if (ex.explain.isNotBlank()) {
                Text(richText(ex.explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
