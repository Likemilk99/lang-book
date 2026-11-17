package app.lectura.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.data.MAX_STAGE
import app.lectura.data.SavedWord
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(onReview: () -> Unit) {
    val app = LecturaApp.instance
    val scope = rememberCoroutineScope()
    val words by app.db.words().all().collectAsState(initial = emptyList())
    val now = remember { System.currentTimeMillis() }
    val due = words.count { it.due <= now }
    var filter by rememberSaveable { mutableStateOf(VocabFilter.ALL) }
    val shown = when (filter) {
        VocabFilter.ALL -> words
        VocabFilter.WORDS -> words.filter { !it.isPhrase }
        VocabFilter.PHRASES -> words.filter { it.isPhrase }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Словарь · ${words.size}") }) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                if (words.isEmpty()) {
                    Text("Нажмите на слово в книге и сохраните его — оно появится здесь и в повторении.")
                } else {
                    FilledTonalButton(onClick = onReview, enabled = due > 0, modifier = Modifier.fillMaxWidth()) {
                        Text(if (due > 0) "Повторить сейчас: $due" else "На сегодня всё повторено")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VocabFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { w ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    buildString {
                                        append(w.word)
                                        if (w.lemma.isNotBlank() && !w.lemma.equals(w.word, true)) append("  (${w.lemma})")
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (w.isPhrase) {
                                    Spacer(Modifier.width(8.dp))
                                    PhraseBadge()
                                }
                            }
                            Text(w.ru, style = MaterialTheme.typography.bodyLarge)
                            StageDots(w)
                            Text(
                                w.sentence,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { app.player.speakOnce(w.word) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести")
                        }
                        IconButton(onClick = { scope.launch { app.db.words().delete(w) } }) {
                            Icon(Icons.Filled.Delete, "Удалить")
                        }
                    }
                }
            }
        }
    }
}

private enum class VocabFilter(val label: String) { ALL("Все"), WORDS("Слова"), PHRASES("Фразы") }

@Composable
private fun PhraseBadge() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(
            "фраза",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Ступень лестницы сложности: ●●○○○○ */
@Composable
private fun StageDots(w: SavedWord) {
    val stage = w.stage.coerceIn(0, MAX_STAGE)
    Text(
        "●".repeat(stage) + "○".repeat(MAX_STAGE - stage),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
