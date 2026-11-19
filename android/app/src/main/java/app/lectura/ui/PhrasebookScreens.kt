package app.lectura.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.model.Phrase
import app.lectura.model.PhraseTheme
import app.lectura.model.Phrasebook
import app.lectura.playback.speakEs
import app.lectura.review.patternText
import app.lectura.speech.MatchResult
import app.lectura.speech.SpeakButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** id фраз разговорника, которые уже в повторении. */
@Composable
internal fun savedRefs(): Set<String> {
    val refs by LecturaApp.instance.db.words().refs().collectAsState(initial = emptyList())
    return remember(refs) { refs.toSet() }
}

@Composable
private fun rememberPhrasebook(level: String): Phrasebook? {
    val pb by produceState<Phrasebook?>(null, level) { value = LecturaApp.instance.phrasebook.level(level) }
    return pb
}

/** Карточка разговорника во вкладке «Говорить». */
@Composable
fun PhrasebookCard(book: Phrasebook, saved: Set<String>, onOpen: () -> Unit) {
    val all = book.themes.flatMap { it.phrases }
    val inReview = all.count { it.id in saved }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("💬 Разговорник ${book.level} · ${book.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(book.intro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${all.size} фраз · ${book.themes.size} тем · в повторении $inReview",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (all.isNotEmpty()) LinearProgressIndicator(progress = { inReview.toFloat() / all.size }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Разговорники для вкладки «Говорить» (все уровни). */
@Composable
fun rememberPhrasebooks(): List<Phrasebook> {
    val list by produceState(emptyList<Phrasebook>()) { value = LecturaApp.instance.phrasebook.all() }
    return list
}

@Composable
fun rememberSavedRefs(): Set<String> = savedRefs()

/** Темы разговорника уровня. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhrasebookScreen(level: String, onBack: () -> Unit, onTheme: (String) -> Unit) {
    val book = rememberPhrasebook(level)
    val saved = savedRefs()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(book?.let { "Разговорник ${it.level}" } ?: "Разговорник") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        val b = book ?: return@Scaffold Loading(Modifier.padding(pad))
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text(b.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(b.intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Как учить: откройте тему → «Тренировка» (слушаете и повторяете) → «Учить все» — " +
                            "фразы попадут в повторение, где вы будете говорить их сами.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(b.themes, key = { it.id }) { t ->
                val n = t.phrases.count { it.id in saved }
                Card(onClick = { onTheme(t.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${t.phrases.size} фраз" + if (n > 0) " · в повторении $n" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (n == t.phrases.size) Icon(Icons.Filled.Check, "Все в повторении", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Loading(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Фразы темы: звук, перевод, подсказка, шаблон с вариантами; «Учить все» и «Тренировка». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhraseThemeScreen(level: String, themeId: String, onBack: () -> Unit, onTrain: () -> Unit) {
    val app = LecturaApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val book = rememberPhrasebook(level)
    val theme = book?.themes?.firstOrNull { it.id == themeId }
    val saved = savedRefs()
    DisposableEffect(Unit) { onDispose { app.player.stopSpeech() } }

    fun add(phrases: List<Phrase>) = scope.launch {
        val n = app.userData.addPhrases(phrases)
        Toast.makeText(ctx, if (n > 0) "В повторение: +$n" else "Уже в повторении", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(theme?.let { "${it.emoji} ${it.title}" } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        val t = theme ?: return@Scaffold Loading(Modifier.padding(pad))
        val missing = t.phrases.filter { it.id !in saved }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTrain, modifier = Modifier.weight(1f)) { Text("🗣 Тренировка") }
                    FilledTonalButton(
                        onClick = { add(missing) },
                        enabled = missing.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (missing.isEmpty()) "Все в повторении" else "Учить все (${missing.size})") }
                }
            }
            items(t.phrases, key = { it.id }) { p -> PhraseCard(p, inReview = p.id in saved, onAdd = { add(listOf(p)) }) }
        }
    }
}

@Composable
internal fun PhraseCard(p: Phrase, inReview: Boolean, onAdd: () -> Unit) {
    var showVariants by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(p.es, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(p.ru, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { speakEs(p.es, p.id) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести") }
                if (inReview) {
                    Icon(Icons.Filled.Check, "В повторении", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                } else {
                    IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "В повторение") }
                }
            }
            if (p.note.isNotBlank()) {
                Text(
                    p.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                )
            }
            if (p.hasPattern) {
                TextButton(onClick = { showVariants = !showVariants }, contentPadding = PaddingValues(0.dp)) {
                    Text(patternText(p.pattern))
                    Text(if (showVariants) "  ▲" else "  · варианты ▼", style = MaterialTheme.typography.labelMedium)
                }
                if (showVariants) {
                    p.slots.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.es, style = MaterialTheme.typography.bodyLarge)
                                Text(s.ru, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { speakEs(s.es, p.id) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести") }
                        }
                    }
                }
            }
        }
    }
}

/** Тренировка темы разговорника «послушай и повтори». */
@Composable
fun PhraseTrainScreen(level: String, themeId: String, onBack: () -> Unit) {
    val book = rememberPhrasebook(level)
    val theme: PhraseTheme? = book?.themes?.firstOrNull { it.id == themeId }
    if (theme == null) {
        Loading(Modifier)
        return
    }
    PhraseTrainer(theme.title, theme.phrases, onBack)
}

/**
 * «Послушай и повтори» по списку фраз: диктор произносит фразу, вы повторяете, распознавание проверяет.
 * Удачная попытка — следующая фраза сама. В конце — «Учить все» и [onFinished] (% фраз, сказанных верно).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhraseTrainer(title: String, phrases: List<Phrase>, onBack: () -> Unit, onFinished: (Int) -> Unit = {}) {
    val app = LecturaApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    val results = remember { mutableStateMapOf<Int, MatchResult>() }
    var showRu by remember { mutableStateOf(true) }
    val saved = savedRefs()
    DisposableEffect(Unit) { onDispose { app.player.stopSpeech() } }
    LaunchedEffect(index >= phrases.size) {
        if (index >= phrases.size && phrases.isNotEmpty()) {
            onFinished(results.values.count { it.passed } * 100 / phrases.size)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            "Послушайте и повторите · ${minOf(index + 1, phrases.size)} из ${phrases.size}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinearProgressIndicator(progress = { index.toFloat() / phrases.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            if (index >= phrases.size) {
                val passed = results.values.count { it.passed }
                Text("Готово! 🎉", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Получилось сразу: $passed из ${phrases.size}")
                val missing = phrases.filter { it.id !in saved }
                if (missing.isNotEmpty()) {
                    Text(
                        "Добавьте фразы в повторение — там вы будете вспоминать и говорить их сами, по шаблону и без подсказки.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = {
                        scope.launch {
                            val n = app.userData.addPhrases(missing)
                            Toast.makeText(ctx, "В повторение: +$n", Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Учить все (${missing.size})") }
                }
                OutlinedButton(onClick = {
                    results.clear()
                    index = 0
                }, modifier = Modifier.fillMaxWidth()) { Text("Ещё раз с начала") }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
                return@Column
            }
            val p = phrases[index]
            val r = results[index]
            // Новая фраза — диктор произносит её сам.
            LaunchedEffect(index) {
                delay(300)
                speakEs(p.es, p.id)
            }
            // Получилось — через секунду следующая.
            LaunchedEffect(r) {
                if (r?.passed == true) {
                    delay(1200)
                    index++
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(p.es, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (showRu) {
                Text(p.ru, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { speakEs(p.es, p.id) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                    Text("  Ещё раз")
                }
                OutlinedButton(onClick = { speakEs(p.es, p.id, slow = true) }, modifier = Modifier.weight(1f)) { Text("🐢 Медленно") }
            }
            if (p.note.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Text(p.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.runtime.key(index) {
                SpeakButton(expected = p.es, modifier = Modifier.fillMaxWidth()) { res ->
                    if (res.score >= (results[index]?.score ?: -1f)) results[index] = res
                }
            }
            androidx.compose.runtime.key(index) { VoiceCompare(p.es, app.phrasebook.clip(p.id)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showRu = !showRu }) { Text(if (showRu) "Скрыть перевод" else "Показать перевод") }
                TextButton(onClick = { index++ }) { Text(if (r == null) "Пропустить ⏭" else "Дальше →") }
            }
        }
    }
}
