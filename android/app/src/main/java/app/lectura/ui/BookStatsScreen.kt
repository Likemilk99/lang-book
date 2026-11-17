package app.lectura.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.data.BookStats
import app.lectura.data.ChapterStat
import app.lectura.model.Book
import app.lectura.model.Tags

/** Статистика книги: прочитано, словарь книги, упражнения по каждой главе. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookStatsScreen(bookId: String, onBack: () -> Unit, onOpenChapter: (Int) -> Unit) {
    val app = LecturaApp.instance
    val book by produceState<Book?>(null, bookId) { value = runCatching { app.books.load(bookId) }.getOrNull() }
    val words by app.db.words().all().collectAsState(initial = emptyList())
    val activity by app.db.activity().forBook(bookId).collectAsState(initial = emptyList())
    val progress by app.db.progress().all().collectAsState(initial = emptyList())
    val b = book
    val st = b?.let { BookStats.compute(it, words, activity, progress.firstOrNull { p -> p.bookId == bookId }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(b?.title ?: "Книга", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        if (b == null || st == null) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Книга пройдена на ${st.overallPct}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { st.overallPct / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        Text("📖 Прочитано глав: ${st.chaptersRead} из ${st.chapters.size}")
                        Text("✅ Упражнений закрыто: ${st.exercisesDone} из ${st.exercisesTotal}")
                        Text("🔤 Слов книги в вашем словаре: ${st.vocabInDict} из ${st.vocabTotal} (${st.vocabPct}%)")
                        Text(
                            "Из них уже знаете: ${st.vocabKnown} (${st.knownPct}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (b.tags.isNotEmpty()) Text(
                            b.tags.joinToString(" · ") { Tags.label(it) },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                Text(
                    "Упражнение засчитано с результата ${ChapterStat.DONE_PCT}%. Нажмите на главу, чтобы открыть её.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(st.chapters, key = { it.index }) { ch -> ChapterRow(ch) { onOpenChapter(ch.index) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterRow(ch: ChapterStat, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ch.index + 1}. ${ch.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${ch.exercisesDone}/${ch.exercisesTotal}", style = MaterialTheme.typography.labelLarge)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                Badge("📖 прочитана", if (ch.read) 100 else null)
                Badge("🗣 за диктором", ch.shadow)
                if (ch.hasQuestions) Badge("❓ вопросы", ch.questions)
                if (ch.hasRole) Badge("🎭 роль", ch.role)
                if (ch.phrasesTotal > 0) Badge("💬 фразы ${ch.phrasesSaved}/${ch.phrasesTotal}", if (ch.phrasesSaved == ch.phrasesTotal) 100 else null, showPct = false)
                val vocabPct = if (ch.vocabTotal == 0) 0 else ch.vocabInDict * 100 / ch.vocabTotal
                Badge("🔤 слова $vocabPct%", vocabPct.takeIf { it >= ChapterStat.DONE_PCT }, showPct = false)
            }
        }
    }
}

/** Плашка упражнения: зелёная — закрыто, жёлтая — пробовали, серая — не делали. */
@Composable
private fun Badge(label: String, pct: Int?, showPct: Boolean = true) {
    val done = pct != null && pct >= ChapterStat.DONE_PCT
    val color = when {
        done -> Color(0xFF2E7D32).copy(alpha = 0.18f)
        pct != null -> Color(0xFFF9A825).copy(alpha = 0.22f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label + if (showPct && pct != null && pct < 100) " $pct%" else if (done && showPct) " ✓" else "",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
