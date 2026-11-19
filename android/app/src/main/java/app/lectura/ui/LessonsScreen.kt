package app.lectura.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectura.LecturaApp
import app.lectura.data.LessonSource
import app.lectura.data.LessonSources
import app.lectura.playback.LessonItem
import app.lectura.playback.LessonMode
import app.lectura.playback.LessonPlayer
import kotlin.math.roundToInt

/** Карточка во вкладке «Говорить». */
@Composable
fun LessonsCard(onOpen: () -> Unit) {
    val s by LecturaApp.instance.lessons.state.collectAsStateWithLifecycle()
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🎧 Аудиоуроки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (s.active) "Сейчас: ${s.title} · ${s.index + 1}/${s.items.size}"
                else "Без экрана, в наушниках: русская фраза → вы говорите → диктор. Для прогулки и дороги.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LessonsScreen(onBack: () -> Unit) {
    val lessons = LecturaApp.instance.lessons
    val s by lessons.state.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val sources by produceState<List<LessonSource>?>(null, refresh) {
        value = runCatching { LessonSources.all() }.getOrDefault(emptyList())
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Аудиоуроки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        val list = sources ?: return@Scaffold Loading(Modifier.padding(pad))
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LessonMode.entries.forEach { m ->
                        FilterChip(selected = s.mode == m, onClick = { lessons.setMode(m) }, label = { Text(m.label) })
                    }
                    FilterChip(selected = s.slow, onClick = { lessons.setSlow(!s.slow) }, label = { Text("🐢 Медленнее") })
                }
            }
            item {
                Text(
                    when (s.mode) {
                        LessonMode.ANTICIPATE -> "Звучит русская фраза — в паузе скажите её по-испански, потом диктор дважды произнесёт правильно."
                        LessonMode.REPEAT -> "Звучит испанская фраза — в паузе повторите её вслух, потом ещё раз."
                    } + " Экран можно выключить: управление — в уведомлении и кнопками наушников.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.items.isNotEmpty()) item { NowPlaying(s, lessons) }
            list.groupBy { it.group }.forEach { (group, sources) ->
                item(key = "g-$group") {
                    Text(group, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                }
                items(sources, key = { it.id }) { src ->
                    Card(onClick = { lessons.start(src.title, src.items, s.mode) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(src.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${src.items.size} фраз · ~${minutes(src.items, s.slow)} мин",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Filled.PlayArrow, "Начать", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/** Примерная длина урока: подсказка, пауза на ответ, эталон дважды. */
private fun minutes(items: List<LessonItem>, slow: Boolean): Int =
    (items.sumOf { 5_000L + LessonPlayer.pauseFor(it.es, slow) + it.es.length * 140L } / 60_000.0).roundToInt().coerceAtLeast(1)

@Composable
private fun NowPlaying(s: LessonPlayer.State, lessons: LessonPlayer) {
    val item = s.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${s.title} · ${minOf(s.index + 1, s.items.size)} из ${s.items.size}", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (s.index + 1f) / s.items.size }, modifier = Modifier.fillMaxWidth())
            if (s.phase == LessonPlayer.Phase.DONE) {
                Text("Урок пройден 🎉", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            } else if (item != null) {
                val anticipate = s.mode == LessonMode.ANTICIPATE
                val showEs = !anticipate || s.phase == LessonPlayer.Phase.ANSWER
                Text(
                    if (showEs) item.es else item.ru,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showEs && anticipate) {
                    Text(item.ru, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (s.phase == LessonPlayer.Phase.YOUR_TURN) {
                    Text(
                        if (anticipate) "🗣 Скажите по-испански" else "🗣 Повторите",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.ruMissing && anticipate) {
                    Text(
                        "Русский голос не установлен — подсказку читайте с экрана (Настройки → Синтез речи → русский).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = lessons::prev) { Icon(Icons.Filled.SkipPrevious, "Назад") }
                FilledIconButton(onClick = lessons::toggle) {
                    Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.playing) "Пауза" else "Играть")
                }
                IconButton(onClick = lessons::next) { Icon(Icons.Filled.SkipNext, "Дальше") }
                IconButton(onClick = lessons::stop) { Icon(Icons.Filled.Stop, "Остановить") }
            }
        }
    }
}
