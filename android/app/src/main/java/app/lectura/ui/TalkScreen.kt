package app.lectura.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectura.LecturaApp
import app.lectura.data.ChapterStat
import app.lectura.data.ChapterActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Глава, доступная для разговорной практики. role/shadow — лучший результат, %, null — ещё не пробовали. */
private data class TalkChapter(
    val chapter: Int,
    val title: String,
    val dialogueLines: Int,
    val role: Int?,
    val shadow: Int?,
)

private data class TalkBook(val id: String, val title: String, val level: String, val chapters: List<TalkChapter>)

/**
 * Вкладка «Говорить»: разговорники (готовые фразы по темам) и ролевая игра / повтор за диктором
 * по главам начатых книг (от первой до той, где вы сейчас), свежие книги и главы — сверху.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TalkScreen(onRoute: (String) -> Unit) {
    val app = LecturaApp.instance
    val books by app.books.books.collectAsStateWithLifecycle()
    val progress by app.db.progress().all().collectAsState(initial = null)
    val activity by app.db.activity().all().collectAsState(initial = null)
    LaunchedEffect(Unit) { if (app.books.books.value.isEmpty()) app.books.refresh() }
    val phrasebooks = rememberPhrasebooks()
    val scenarios = rememberScenarios()
    val scenarioStates = rememberScenarioStates(scenarios)
    val savedRefs = rememberSavedRefs()
    val list by produceState<List<TalkBook>?>(null, books, progress, activity) {
        val p = progress ?: return@produceState
        val a = activity ?: return@produceState
        value = withContext(Dispatchers.Default) {
            books.mapNotNull { info ->
                val bp = p.firstOrNull { it.bookId == info.id }
                val ba = a.filter { it.bookId == info.id }
                if (bp == null && ba.isEmpty()) return@mapNotNull null
                val book = runCatching { app.books.load(info.id) }.getOrNull() ?: return@mapNotNull null
                val last = maxOf(bp?.updatedAt ?: 0, ba.maxOfOrNull { it.updatedAt } ?: 0)
                val upTo = maxOf(bp?.chapter ?: 0, ba.maxOfOrNull { it.chapter } ?: 0).coerceAtMost(book.chapters.lastIndex)
                val chapters = (upTo downTo 0).map { i ->
                    val ch = book.chapters[i]
                    fun best(kind: String) = ba.firstOrNull { it.chapter == i && it.kind == kind }?.best
                    TalkChapter(
                        chapter = i,
                        title = ch.title,
                        dialogueLines = ch.sentences.count { !it.sp.isNullOrBlank() },
                        role = best(ChapterActivity.ROLE),
                        shadow = best(ChapterActivity.SHADOW),
                    )
                }
                last to TalkBook(book.id, book.title, book.level, chapters)
            }.sortedByDescending { it.first }.map { it.second }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Говорить") }) },
    ) { pad ->
        val l = list ?: return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (scenarios.isNotEmpty()) {
                item {
                    Column {
                        Text("Сценарии", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Ситуация целиком: фразы → разговор → роль за обе стороны → неожиданный поворот → понять ответ → ответить своими словами.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                scenarios.groupBy { it.level }.forEach { (level, list) ->
                    item(key = "sl-$level") {
                        val can = list.count { scenarioStates[it.id]?.canDo == true }
                        Text("$level · могу $can из ${list.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(list, key = { "sc-" + it.id }) { sc ->
                        ScenarioCard(sc, scenarioStates[sc.id]) { onRoute("scenario/${sc.id}") }
                    }
                }
                item {
                    Text("Разговорники", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                }
            }
            items(phrasebooks, key = { "pb-" + it.level }) { pb ->
                PhrasebookCard(pb, savedRefs) { onRoute("phrasebook/${pb.level}") }
            }
            item {
                Text(
                    "Диалоги из книг",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            item { HowToCard() }
            if (l.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Здесь появятся диалоги из глав, которые вы читаете.")
                        Button(onClick = { onRoute("library") }) { Text("Открыть библиотеку") }
                    }
                }
            }
            l.forEach { b ->
                item(key = "h-" + b.id) {
                    Text(
                        "${b.title} · ${b.level}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(b.chapters, key = { b.id + "-" + it.chapter }) { c ->
                    ChapterCard(c) { mode -> onRoute("speak/${b.id}/${c.chapter}/$mode") }
                }
            }
        }
    }
}

@Composable
private fun HowToCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🎭 Ролевая игра — вы видите свою реплику по-русски и говорите её по-испански, остальные отвечают голосами диктора.")
            Text("🗣 Повтор за диктором — слушаете фразу и повторяете вслух, приложение проверяет, узнаются ли ваши слова.")
            Text(
                "Лучше всего: сначала повтор, потом роль. Упражнение засчитывается от ${ChapterStat.DONE_PCT}%.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterCard(c: TalkChapter, onStart: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Гл. ${c.chapter + 1} · ${c.title}", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (c.dialogueLines > 0) {
                    FilledTonalButton(onClick = { onStart("role") }) { Text("🎭 Роль" + score(c.role)) }
                }
                OutlinedButton(onClick = { onStart("shadow") }) { Text("🗣 Повтор" + score(c.shadow)) }
            }
        }
    }
}

private fun score(best: Int?): String = when {
    best == null -> ""
    best >= ChapterStat.DONE_PCT -> " · $best% ✓"
    else -> " · $best%"
}
