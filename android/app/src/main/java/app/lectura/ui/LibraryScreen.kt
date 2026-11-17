package app.lectura.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectura.LecturaApp
import app.lectura.data.BookInfo
import app.lectura.data.BookStat
import app.lectura.data.BookStats
import app.lectura.model.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onBookStats: (String) -> Unit = {}, onProgress: () -> Unit = {}) {
    val app = LecturaApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by app.books.books.collectAsStateWithLifecycle()
    val progress by app.db.progress().all().collectAsState(initial = emptyList())
    val words by app.db.words().all().collectAsState(initial = emptyList())
    val activity by app.db.activity().all().collectAsState(initial = emptyList())
    var toDelete by remember { mutableStateOf<BookInfo?>(null) }
    var showData by remember { mutableStateOf(false) }
    var showTopics by remember { mutableStateOf(false) }
    var levels by rememberSaveable { mutableStateOf(setOf<String>()) }
    var tags by rememberSaveable { mutableStateOf(setOf<String>()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { app.books.refresh() } }

    // Статистика всех книг (книги кэшируются в репозитории, расчёт — в фоне).
    val stats by produceState(emptyMap<String, BookStat>(), books, words, activity, progress) {
        value = withContext(Dispatchers.Default) {
            books.mapNotNull { info ->
                runCatching {
                    val b = app.books.load(info.id)
                    info.id to BookStats.compute(b, words, activity, progress.firstOrNull { it.bookId == info.id })
                }.getOrNull()
            }.toMap()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.books.import(uri) }
                .onSuccess { Toast.makeText(ctx, "Добавлено: $it", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Не получилось: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    // Фильтр: уровни — любой из выбранных; темы — внутри группы «или», между группами «и».
    val shown = books.filter { b ->
        (levels.isEmpty() || b.level in levels) &&
            tags.groupBy { Tags.groupOf(it)?.title }.all { (_, group) -> group.any { it in b.tags } }
    }
    val presentLevels = Tags.levels.filter { l -> books.any { it.level == l } }
    val presentTags = books.flatMap { it.tags }.toSet()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Книги") },
                actions = {
                    IconButton(onClick = { showData = true }) { Icon(Icons.Filled.Storage, "Данные и резервная копия") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Импорт") },
            )
        },
    ) { pad ->
        if (books.isEmpty()) {
            Column(Modifier.padding(pad).padding(24.dp)) {
                Text("Пока нет книг", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Подготовьте книгу на Mac:\npython3 pipeline/prepare_book.py --topic \"…\" --push\n\n" +
                        "или нажмите «Импорт» и выберите .json файл.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatsCard(onClick = onProgress) }
            item {
                // Уровни + кнопка «Темы»
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = tags.isNotEmpty(),
                            onClick = { showTopics = true },
                            leadingIcon = { Icon(Icons.Filled.FilterList, null) },
                            label = { Text(if (tags.isEmpty()) "Темы" else "Темы · ${tags.size}") },
                        )
                    }
                    items(presentLevels) { l ->
                        FilterChip(
                            selected = l in levels,
                            onClick = { levels = if (l in levels) levels - l else levels + l },
                            label = { Text(l) },
                        )
                    }
                }
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { t ->
                            InputChip(selected = true, onClick = { tags = tags - t }, label = { Text("${Tags.label(t)} ✕") })
                        }
                        TextButton(onClick = { tags = emptySet() }) { Text("Сбросить") }
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text("Нет книг под этот фильтр.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { it.id }) { b ->
                BookCard(
                    b, stats[b.id], progress.firstOrNull { it.bookId == b.id }?.chapter,
                    onOpen = { onOpen(b.id) },
                    onStats = { onBookStats(b.id) },
                    onLongClick = { toDelete = b },
                )
            }
        }
    }

    if (showTopics) {
        ModalBottomSheet(
            onDismissRequest = { showTopics = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Темы", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (tags.isNotEmpty()) TextButton(onClick = { tags = emptySet() }) { Text("Сбросить") }
                }
                Tags.groups.forEach { g ->
                    // Показываем только теги, которые есть хотя бы у одной книги.
                    val available = g.tags.filter { it.id in presentTags }
                    if (available.isEmpty()) return@forEach
                    Text(g.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        available.forEach { t ->
                            val n = books.count { t.id in it.tags }
                            FilterChip(
                                selected = t.id in tags,
                                onClick = { tags = if (t.id in tags) tags - t.id else tags + t.id },
                                label = { Text("${t.label} · $n") },
                            )
                        }
                    }
                }
                if (presentTags.isEmpty()) Text("У книг пока нет тегов — обновите их через конвейер (--enrich).")
                Text(
                    "Внутри группы — любая из тем, между группами — все условия сразу.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    if (showData) DataDialog(onDismiss = { showData = false })

    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить «${b.title}»?") },
            text = { Text("Файл книги удалится с телефона. Сохранённые слова останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        app.books.delete(b)
                        app.db.progress().delete(b.id)
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BookCard(
    b: BookInfo,
    st: BookStat?,
    chapter: Int?,
    onOpen: () -> Unit,
    onStats: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongClick)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val meta = listOf(b.author, b.level, "${b.chapters} гл.").filter { it.isNotBlank() }
                    Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onStats) { Icon(Icons.Filled.BarChart, "Статистика книги") }
            }
            if (b.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp, end = 12.dp)) {
                    b.tags.take(5).forEach { t ->
                        AssistChip(onClick = {}, label = { Text(Tags.label(t), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    }
                }
            }
            if (st != null) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.padding(end = 12.dp)) {
                    LinearProgressIndicator(progress = { st.overallPct / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        buildString {
                            append("Пройдено ${st.overallPct}%")
                            if (chapter != null) append(" · сейчас гл. ${chapter + 1}")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "📖 ${st.chaptersRead}/${st.chapters.size} гл. · 🔤 слова ${st.vocabPct}% · ✅ упражнения ${st.exercisesDone}/${st.exercisesTotal}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
