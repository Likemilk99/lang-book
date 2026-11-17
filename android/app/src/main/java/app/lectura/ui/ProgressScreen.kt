package app.lectura.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lectura.data.LevelProgress
import app.lectura.data.ProgressService
import app.lectura.data.UserProgress

/** «Мой прогресс»: уровни CEFR (словарь + грамматика) и общая статистика. Всё из одного объекта UserProgress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val p by produceState<UserProgress?>(null) { value = runCatching { ProgressService.compute() }.getOrNull() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мой прогресс") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        val up = p ?: return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CurrentLevelCard(up) }
            items(up.levels.filter { it.hasContent }, key = { it.level }) { LevelCard(it, it.level == up.currentLevel) }
            item { TotalsCard(up) }
            item {
                Text(
                    "Уровень считается как доля освоенного: словарь уровня (знаю = 1, учу = ½) и грамматические темы " +
                        "(освоена после ${app.lectura.data.GrammarProgress.MASTERED_STAGE} успешных сессий ≥ 80%). " +
                        "Списки слов и тем построены по структуре Plan Curricular del Instituto Cervantes. " +
                        "С ${LevelProgress.COVERED_PCT}% уровень считается покрытым.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CurrentLevelCard(up: UserProgress) {
    val cur = up.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                if (up.coveredLevel != null) "Покрыт уровень ${up.coveredLevel}" else "Идёте к уровню ${up.currentLevel}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (cur != null) {
                Text("${cur.level}: ${cur.percent}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { cur.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 4.dp),
                )
                Text(
                    "Словарь ${cur.vocabPct}% · грамматика ${cur.grammarPct}%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (cur.percent >= LevelProgress.COVERED_PCT) Text(
                    "Можно сдавать итоговый тест уровня (в формате DELE) — появится в следующих версиях.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LevelCard(l: LevelProgress, current: Boolean) {
    var open by remember { mutableStateOf(current) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text(l.level, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${l.percent}%", style = MaterialTheme.typography.titleLarge)
            }
            Bar("Словарь: знаю ${l.vocabKnown}, учу ${l.vocabLearning} из ${l.vocabTotal}", l.vocabPct)
            Bar("Грамматика: освоено ${l.grammarMastered}, начато ${l.grammarStarted} из ${l.grammarTotal}", l.grammarPct)
            if (open && l.themes.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("Словарь по темам", style = MaterialTheme.typography.titleSmall)
                l.themes.sortedByDescending { if (it.total == 0) 0.0 else (it.known + 0.5 * it.learning) / it.total }.forEach { t ->
                    val pct = if (t.total == 0) 0 else ((t.known + 0.5 * t.learning) * 100 / t.total).toInt()
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${t.known + t.learning}/${t.total} · $pct%", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(label: String, pct: Int) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
}

@Composable
private fun TotalsCard(up: UserProgress) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("За всё время", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Line("Слов в словаре", "${up.words} (знаю ${up.knownWords})")
            Line("Фраз", "${up.phrases}")
            Line("Память словаря", "${up.memoryNowPct}% · через месяц ~${up.memoryIn30Pct}%")
            Line("Грамматика", "освоено ${up.grammarMastered} из ${up.grammarTotal}, начато ${up.grammarStarted}")
            Line("Книг начато / глав прочитано", "${up.booksStarted} / ${up.chaptersRead}")
            Line("Упражнений", "${up.exercises} · верно ${up.correctPct}%")
            Line("Из них доп. практика", "${up.practice}")
            Line("Сказано вслух", "${up.speaking}")
            Line("Прослушано предложений", "${up.sentencesListened}")
            Line("Дней занятий / серия", "${up.activeDays} / ${up.streak} 🔥")
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
