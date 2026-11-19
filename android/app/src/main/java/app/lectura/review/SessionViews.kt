package app.lectura.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lectura.ui.MemoryCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Текущее упражнение сессии + обратная связь + «Дальше». */
@Composable
fun ColumnScope.SessionView(vm: ReviewViewModel, task: Task) {
    key(task.id) {
        when (val ex = task.exercise) {
            is Exercise.Flash -> FlashCardView(vm, ex)
            else -> AutoCheckedView(vm, ex)
        }
    }
}

@Composable
private fun ColumnScope.AutoCheckedView(vm: ReviewViewModel, ex: Exercise) {
    val answer = vm.answer
    val answered = answer != null
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        when (ex) {
            is Exercise.SayEs -> SayEsView(ex, answered, onShowAnswer = { vm.onAnswerShown(ex) }, onAnswer = vm::submit)
            is Exercise.ListenRepeat -> ListenRepeatView(ex, answered, onShowAnswer = { vm.onAnswerShown(ex) }, onAnswer = vm::submit)
            is Exercise.PatternSay -> PatternSayView(ex, answered, onShowAnswer = { vm.onAnswerShown(ex) }, onAnswer = vm::submit)
            is Exercise.ClozeChoice -> ClozeChoiceView(ex, answered, vm::submit)
            is Exercise.ClozeInput -> ClozeInputView(ex, answered, vm::submit)
            is Exercise.ListenChoice -> ListenChoiceView(ex, answered, vm::submit)
            is Exercise.BuildSentence -> BuildSentenceView(ex, answered, vm::submit)
            is Exercise.DialogReply -> DialogReplyView(ex, vm::submit)
            is Exercise.Flash -> Unit
        }
        if (answer != null) FeedbackPanel(ex, answer)
    }
    if (answered) {
        Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(60.dp)) {
            Text("Дальше", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun SetupPanel(vm: ReviewViewModel) {
    val phrases = vm.due.count { it.isPhrase }
    Column(Modifier.verticalScroll(rememberScrollState())) {
    MemoryCard(vm.allWords, Modifier.padding(bottom = 12.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("К повторению: ${countText(vm.due.size)}", style = MaterialTheme.typography.titleLarge)
            if (phrases > 0) {
                Text(
                    "слов: ${vm.due.size - phrases}, фраз: $phrases",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.due.size > SessionBatch.SIZE) {
                Text(
                    "За один подход — ${SessionBatch.SIZE}: сначала те, что вы уже учили и можете забыть, потом новые. " +
                        "Остальные — следующими подходами.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            Text("Режим", style = MaterialTheme.typography.titleSmall)
            ReviewMode.entries.forEach { m ->
                FilterChip(
                    selected = vm.mode == m,
                    onClick = { vm.mode = m },
                    label = { Text(m.label) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (vm.mode == ReviewMode.MIXED) {
                Text(
                    buildString {
                        append("Говорите с первого раза: сначала повторяете за диктором и узнаёте на слух, ")
                        append("потом вспоминаете сами — сказать вслух, по шаблону, вписать, собрать фразу.")
                        if (vm.dialogsAvailable > 0) append(" Плюс до ${ReviewViewModel.MAX_DIALOGS} ответов в диалоге.")
                        if (!vm.speechAvailable) append(" Распознавание речи недоступно — упражнения с речью пропускаются.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Карточки: какой стороной показывать", style = MaterialTheme.typography.titleSmall)
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
                "Испанский звучит только в момент, когда появляется на экране. Любое нажатие сразу прерывает озвучку.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Начать (${minOf(vm.due.size, SessionBatch.SIZE)})")
            }
            if (vm.practiceAvailable > 0) {
                OutlinedButton(onClick = { vm.startPractice() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Дополнительная практика (${vm.practiceAvailable})")
                }
            }
        }
    }
    }
}

/** Кнопка «ещё потренироваться» с пояснением, что расписание она не ломает. */
@Composable
private fun PracticeButton(vm: ReviewViewModel) {
    if (vm.practiceAvailable == 0) return
    Button(onClick = { vm.startPractice() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp)) {
        Text(
            if (vm.practice) "Ещё практика (${vm.practiceAvailable})" else "Дополнительная практика (${vm.practiceAvailable})",
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Text(
        "Слова, которые вы скорее всего подзабыли. Верные ответы не сдвигают расписание, " +
            "ошибка возвращает слово в обычное повторение.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
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
fun FinishedView(vm: ReviewViewModel, onAgain: () -> Unit) {
    val results = vm.results
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 32.dp)) {
        if (results.isEmpty()) {
            Text("По плану сейчас нечего повторять", style = MaterialTheme.typography.titleLarge)
        } else {
            val correct = results.count { it.correct }
            val pct = (correct * 100f / results.size).roundToInt()
            Text(if (vm.practice) "Практика окончена 💪" else "Готово! 🎉", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text("Упражнений: ${results.size}", style = MaterialTheme.typography.titleMedium)
            Text("Верно: $correct ($pct%)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("По типам", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                    results.groupBy { it.type }.toSortedMap().forEach { (type, list) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(type.label, Modifier.weight(1f))
                            Text("${list.count { it.correct }} / ${list.size}", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (vm.dueLeft > 0 && results.isNotEmpty()) {
            Button(onClick = { vm.continueReview() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp)) {
                Text("Ещё подход: ${minOf(vm.dueLeft, SessionBatch.SIZE)}", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Ещё ждут повторения: ${countText(vm.dueLeft)}. Можно сделать перерыв и вернуться позже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        vm.nextDue?.takeIf { vm.dueLeft == 0 }?.let {
            Text(
                "Следующее повторение: " + SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(it)),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        PracticeButton(vm)
        if (results.isNotEmpty()) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Проверить плановые повторения")
            }
        }
        MemoryCard(vm.allWords, Modifier.padding(top = 16.dp))
    }
}

/** Плановые выбираются из БД не больше 200 за раз. */
private fun countText(n: Int) = if (n >= 200) "200+" else n.toString()
