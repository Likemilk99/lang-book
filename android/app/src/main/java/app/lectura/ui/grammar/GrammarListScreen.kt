package app.lectura.ui.grammar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.data.GrammarProgress
import app.lectura.model.CURRICULUM_LEVELS
import app.lectura.model.GrammarLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GrammarListContent(onTopic: (String) -> Unit, onBack: () -> Unit) {
    val app = LecturaApp.instance
    val levels by produceState<Map<String, GrammarLevel>?>(null) {
        value = runCatching { app.curriculum.grammar() }.getOrDefault(emptyMap())
    }
    val progressList by app.db.grammar().all().collectAsState(initial = null)
    val now = remember(progressList) { System.currentTimeMillis() }
    var chosen by remember { mutableStateOf(app.prefs.grammarLevel) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Грамматика") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        val lv = levels
        val pl = progressList
        if (lv == null || pl == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val progress = pl.associateBy { it.topicId }
        if (lv.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Грамматические темы пока не загружены", textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        // Уровень по умолчанию — первый, где есть неосвоенные темы.
        val auto = CURRICULUM_LEVELS.firstOrNull { l ->
            lv[l]?.topics?.any { progress[it.id]?.mastered != true } == true
        } ?: CURRICULUM_LEVELS.first { it in lv }
        val level = chosen.takeIf { it in lv } ?: auto
        val topics = lv[level]?.topics.orEmpty()

        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CURRICULUM_LEVELS.forEach { l ->
                        FilterChip(
                            selected = l == level,
                            enabled = l in lv,
                            onClick = {
                                chosen = l
                                app.prefs.grammarLevel = l
                            },
                            label = { Text(l) },
                        )
                    }
                }
            }
            item { LevelHeader(level, topics.map { progress[it.id] }, now) }
            if (topics.isEmpty()) {
                item {
                    Text(
                        "На уровне $level пока нет тем",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            itemsIndexed(topics, key = { _, t -> t.id }) { i, t ->
                val p = progress[t.id]
                Card(
                    onClick = { onTopic(t.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(32.dp),
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            if (t.titleEs.isNotBlank()) {
                                Text(
                                    t.titleEs,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(
                                Modifier.padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                StatusChip(p, now)
                                if (p != null && p.sessions > 0) {
                                    Text(
                                        "последний: ${p.lastPct}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = pctColor(p.lastPct),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelHeader(level: String, progress: List<GrammarProgress?>, now: Long) {
    val total = progress.size
    val mastered = progress.count { it?.mastered == true }
    val attempts = progress.sumOf { it?.attempts ?: 0 }
    val correct = progress.sumOf { it?.correct ?: 0 }
    val due = progress.count { it.isDue(now) }
    val started = progress.count { (it?.sessions ?: 0) > 0 }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Уровень $level", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else mastered / total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Text("Освоено тем: $mastered из $total")
            Text("Начато: $started")
            Text(if (attempts > 0) "Средняя точность: ${correct * 100 / attempts}%" else "Средняя точность: —")
            if (due > 0) {
                Text("Пора повторить: $due", color = app.lectura.review.Warn, fontWeight = FontWeight.SemiBold)
            } else {
                Text("Пора повторить: 0")
            }
        }
    }
}
