package app.lectura.ui.grammar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.data.GrammarProgress
import app.lectura.data.MAX_STAGE
import app.lectura.model.GrammarTopic
import app.lectura.review.Bad
import app.lectura.review.Good

/** Страница темы: объяснение, примеры, ошибки, связанные темы, статистика и кнопка практики. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GrammarTopicContent(
    topicId: String,
    onBack: () -> Unit,
    onPractice: () -> Unit,
    onOpenTopic: (String) -> Unit,
) {
    val app = LecturaApp.instance
    val data by produceState<Pair<GrammarTopic?, Map<String, GrammarTopic>>?>(null, topicId) {
        val all = runCatching { app.curriculum.grammar() }.getOrDefault(emptyMap())
        val byId = all.values.flatMap { it.topics }.associateBy { it.id }
        value = byId[topicId] to byId
    }
    val progressList by app.db.grammar().all().collectAsState(initial = emptyList())
    val progress = progressList.firstOrNull { it.topicId == topicId }
    DisposableEffect(Unit) { onDispose { app.player.stopSpeech() } }

    val topic = data?.first
    val bankSize = topic?.exercises?.count { GrammarLogic.isUsable(it) } ?: 0
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topic?.title ?: "Тема", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        bottomBar = {
            if (topic != null) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            app.player.stopSpeech()
                            onPractice()
                        },
                        enabled = bankSize > 0,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                    ) {
                        val n = minOf(GrammarLogic.SESSION_SIZE, bankSize)
                        Text(
                            if (bankSize == 0) "Упражнений пока нет" else "Практиковать ($n ${plural(n, "упражнение", "упражнения", "упражнений")})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        },
    ) { pad ->
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val t = d.first
        if (t == null) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Тема не найдена", textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        TopicBody(t, d.second, progress, pad, onOpenTopic)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicBody(
    t: GrammarTopic,
    all: Map<String, GrammarTopic>,
    progress: GrammarProgress?,
    pad: PaddingValues,
    onOpenTopic: (String) -> Unit,
) {
    val player = LecturaApp.instance.player
    val primary = MaterialTheme.colorScheme.primary
    LazyColumn(
        Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                if (t.titleEs.isNotBlank()) {
                    Text(t.titleEs, style = MaterialTheme.typography.titleMedium, color = primary, fontStyle = FontStyle.Italic)
                }
                Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(richText(t.summary), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            }
        }
        item { StatsBlock(progress) }
        items(t.sections) { s ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.title.isNotBlank()) {
                    Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                if (s.text.isNotBlank()) RichParagraphs(s.text)
                if (s.table.isNotEmpty()) GrammarTable(s.table)
            }
        }
        if (t.examples.isNotEmpty()) {
            item { SectionTitle("Примеры") }
            items(t.examples) { ex ->
                Surface(
                    onClick = { player.speakOnce(ex.es) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ex.es, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(ex.ru, style = MaterialTheme.typography.bodyMedium)
                            if (ex.note.isNotBlank()) {
                                Text(
                                    richText(ex.note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        IconButton(onClick = { player.speakOnce(ex.es) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести", tint = primary)
                        }
                    }
                }
            }
        }
        if (t.mistakes.isNotEmpty()) {
            item { SectionTitle("Типичные ошибки") }
            items(t.mistakes) { m ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "✗ ${m.wrong}",
                            color = Bad,
                            textDecoration = TextDecoration.LineThrough,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("✓ ${m.right}", color = Good, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (m.why.isNotBlank()) {
                            Text(richText(m.why), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        val related = t.related.mapNotNull { all[it] }
        if (related.isNotEmpty()) {
            item { SectionTitle("Связанные темы") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    related.forEach { r ->
                        AssistChip(onClick = { onOpenTopic(r.id) }, label = { Text(r.title) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(bottom = 10.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatsBlock(p: GrammarProgress?) {
    val now = remember(p) { System.currentTimeMillis() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ваш прогресс", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(p, now)
            }
            if (p == null || p.sessions == 0) {
                Text(
                    "Вы ещё не практиковали эту тему. Прочитайте объяснение и нажмите «Практиковать».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text("Сессий: ${p.sessions} · точность ${p.accuracy() ?: 0}% (${p.correct} из ${p.attempts})")
            Row {
                Text("Последний результат: ")
                Text("${p.lastPct}%", color = pctColor(p.lastPct), fontWeight = FontWeight.SemiBold)
                Text(" · лучший: ")
                Text("${p.bestPct}%", color = pctColor(p.bestPct), fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ступень ${p.stage} из $MAX_STAGE  ")
                StageDots(p.stage)
            }
            Text(
                if (p.mastered) "Тема освоена — повторяйте по расписанию"
                else "До освоения: ступень ${GrammarProgress.MASTERED_STAGE} (сессии от 80%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Следующее повторение: ${reviewDateText(p.due, now)}",
                fontWeight = if (p.isDue(now)) FontWeight.SemiBold else null,
                color = if (p.isDue(now)) app.lectura.review.Warn else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
