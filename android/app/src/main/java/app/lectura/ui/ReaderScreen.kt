package app.lectura.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.data.SavedWord
import app.lectura.model.Book
import app.lectura.model.Sentence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReaderViewModel(private val app: LecturaApp, val bookId: String) : ViewModel() {
    val book = MutableStateFlow<Book?>(null)
    val error = MutableStateFlow<String?>(null)
    val savedKeys = app.db.words().keys().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch {
            runCatching {
                val b = app.books.load(bookId)
                val p = app.db.progress().get(bookId)
                app.player.open(b, p?.chapter ?: 0, p?.sentence ?: 0)
                book.value = b
            }.onFailure { error.value = it.message ?: it.toString() }
        }
    }

    fun toggleSave(sentence: Sentence, tokenIndex: Int) {
        val tk = sentence.tokens[tokenIndex]
        viewModelScope.launch {
            if (tk.key in savedKeys.value) {
                app.db.words().deleteKey(tk.key)
            } else {
                val now = System.currentTimeMillis()
                app.db.words().insert(
                    SavedWord(
                        key = tk.key, word = tk.t, lemma = tk.l ?: tk.t, ru = tk.ru.orEmpty(),
                        grammar = tk.g.orEmpty(), sentence = sentence.text, sentenceRu = sentence.ru,
                        bookId = bookId, createdAt = now, due = now,
                    ),
                )
            }
        }
    }
}

private data class Selection(val sentence: Int, val token: Int)

private val RATES = listOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val app = LecturaApp.instance
    val vm: ReaderViewModel = viewModel(key = bookId) { ReaderViewModel(app, bookId) }
    val book by vm.book.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val ps by app.player.state.collectAsStateWithLifecycle()
    val fontSize by app.prefs.fontSize.collectAsStateWithLifecycle()
    val showRu by app.prefs.showRu.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf<Selection?>(null) }
    var chaptersMenu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val b = book
    val chapterIdx = if (ps.bookId == bookId) ps.chapter else 0
    val chapter = b?.chapters?.getOrNull(chapterIdx)

    // Абзацы главы вместе с «глобальными» номерами предложений внутри главы.
    val paragraphs = remember(chapter) {
        var n = 0
        chapter?.paragraphs?.map { p -> p.sentences.map { s -> (n++) to s } }.orEmpty()
    }
    val listState = rememberLazyListState()
    fun paragraphOf(sentence: Int) = paragraphs.indexOfFirst { p -> p.any { it.first == sentence } }.coerceAtLeast(0)

    LaunchedEffect(chapter) {
        if (chapter != null) listState.scrollToItem(paragraphOf(ps.sentence) + 1)
    }
    LaunchedEffect(ps.sentence, ps.playing) {
        if (!ps.playing || chapter == null) return@LaunchedEffect
        val item = paragraphOf(ps.sentence) + 1
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (item !in visible.dropLast(1)) listState.animateScrollToItem(item)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(b?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (chapter != null) Text(
                            "Глава ${chapterIdx + 1}/${b.chapters.size}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconToggleButton(checked = showRu, onCheckedChange = { app.prefs.setShowRu(it) }) {
                        Icon(Icons.Filled.Translate, "Перевод абзацев")
                    }
                    IconButton(onClick = { app.prefs.setFontSize(fontSize - 2) }) { Icon(Icons.Filled.TextDecrease, "Мельче") }
                    IconButton(onClick = { app.prefs.setFontSize(fontSize + 2) }) { Icon(Icons.Filled.TextIncrease, "Крупнее") }
                    Box {
                        IconButton(onClick = { chaptersMenu = true }) { Icon(Icons.AutoMirrored.Filled.List, "Главы") }
                        DropdownMenu(expanded = chaptersMenu, onDismissRequest = { chaptersMenu = false }) {
                            b?.chapters?.forEachIndexed { i, ch ->
                                DropdownMenuItem(
                                    text = { Text("${i + 1}. ${ch.title}", fontWeight = if (i == chapterIdx) FontWeight.Bold else null) },
                                    onClick = {
                                        chaptersMenu = false
                                        app.player.seekChapter(i)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = { if (chapter != null) PlayerBar(ps) },
    ) { pad ->
        when {
            error != null -> Text("Ошибка: $error", Modifier.padding(pad).padding(24.dp))
            chapter == null -> Box(Modifier.fillMaxSize().padding(pad))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        if (ps.voiceMissing) VoiceMissingBanner {
                            ctx.startActivity(
                                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        Text(chapter.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        if (chapter.titleRu.isNotBlank()) Text(
                            chapter.titleRu,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(paragraphs) { _, para ->
                    Column {
                        ParagraphText(
                            sentences = para,
                            current = if (ps.bookId == bookId) ps.sentence else -1,
                            selection = selection,
                            saved = saved,
                            fontSize = fontSize,
                            onWord = { s, t -> selection = Selection(s, t) },
                        )
                        if (showRu) Text(
                            para.joinToString(" ") { it.second.ru },
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                        if (chapterIdx + 1 < b.chapters.size) {
                            FilledTonalButton(onClick = { app.player.seekChapter(chapterIdx + 1) }) {
                                Text("Следующая глава →")
                            }
                        } else {
                            Text("Конец книги 🎉", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    val sel = selection
    if (sel != null && chapter != null) {
        val sentence = chapter.sentences.getOrNull(sel.sentence)
        val token = sentence?.tokens?.getOrNull(sel.token)
        if (sentence != null && token != null) {
            WordSheet(
                sentence = sentence,
                tokenIndex = sel.token,
                isSaved = token.key in saved,
                onToggleSave = { vm.toggleSave(sentence, sel.token) },
                onReadFromHere = {
                    selection = null
                    app.player.seek(sel.sentence)
                    app.player.play()
                },
                onDismiss = { selection = null },
            )
        }
    }
}

@Composable
private fun ParagraphText(
    sentences: List<Pair<Int, Sentence>>,
    current: Int,
    selection: Selection?,
    saved: Set<String>,
    fontSize: Float,
    onWord: (Int, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val onWordState by rememberUpdatedState(onWord)
    val text = remember(sentences, current, selection, saved, colors) {
        buildAnnotatedString {
            sentences.forEachIndexed { k, (si, s) ->
                if (k > 0) append(" ")
                val start = length
                s.tokens.forEachIndexed { ti, tk ->
                    if (s.spaces[ti]) append(" ")
                    if (tk.isWord) {
                        val isSaved = tk.key in saved
                        val isSelected = selection?.sentence == si && selection.token == ti
                        val style = SpanStyle(
                            color = if (isSaved) colors.primary else Color.Unspecified,
                            textDecoration = if (isSaved) TextDecoration.Underline else null,
                            background = if (isSelected) colors.tertiaryContainer else Color.Unspecified,
                        )
                        withLink(LinkAnnotation.Clickable("w$si:$ti", TextLinkStyles(style)) { onWordState(si, ti) }) {
                            append(tk.t)
                        }
                    } else {
                        append(tk.t)
                    }
                }
                if (si == current) addStyle(SpanStyle(background = colors.secondaryContainer), start, length)
            }
        }
    }
    Text(
        text,
        style = TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.55f).sp,
            color = colors.onSurface,
        ),
    )
}

@Composable
private fun PlayerBar(ps: app.lectura.playback.Player.State) {
    val player = LecturaApp.instance.player
    var rateMenu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { rateMenu = true }) { Text("${"%.1f".format(ps.rate)}×") }
                DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                    RATES.forEach { r ->
                        DropdownMenuItem(text = { Text("${"%.1f".format(r)}×") }, onClick = {
                            rateMenu = false
                            player.setRate(r)
                        })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { player.prev() }) { Icon(Icons.Filled.SkipPrevious, "Предыдущее") }
            FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(if (ps.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Играть")
            }
            IconButton(onClick = { player.next() }) { Icon(Icons.Filled.SkipNext, "Следующее") }
            Spacer(Modifier.weight(1f))
            Text(
                "${ps.sentence + 1}/${ps.sentenceCount}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

@Composable
private fun VoiceMissingBanner(onInstall: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Не установлен испанский голос (Испания).")
            TextButton(onClick = onInstall) { Text("Установить голос") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordSheet(
    sentence: Sentence,
    tokenIndex: Int,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onReadFromHere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val player = LecturaApp.instance.player
    val tk = sentence.tokens[tokenIndex]
    var showSentenceRu by remember(sentence) { mutableStateOf(false) }
    LaunchedEffect(tk) { player.speakOnce(tk.t) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tk.t, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { player.speakOnce(tk.t) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести") }
                TextButton(onClick = { player.speakOnce(tk.t, slow = true) }) { Text("медленно") }
            }
            if (!tk.l.isNullOrBlank() && !tk.l.equals(tk.t, ignoreCase = true)) {
                Text("→ ${tk.l}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(tk.ru.orEmpty(), style = MaterialTheme.typography.titleLarge)
            if (!tk.g.isNullOrBlank()) Text(
                tk.g,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onToggleSave) {
                Icon(if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isSaved) "В словаре" else "Сохранить в словарь")
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text(
                buildAnnotatedString {
                    sentence.tokens.forEachIndexed { i, t ->
                        if (sentence.spaces[i]) append(" ")
                        if (i == tokenIndex) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.t) } else append(t.t)
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (showSentenceRu) {
                Text(
                    sentence.ru,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { player.speakOnce(sentence.text) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Фраза")
                }
                OutlinedButton(onClick = { showSentenceRu = !showSentenceRu }) { Text("Перевод") }
                OutlinedButton(onClick = onReadFromHere) { Text("Читать отсюда") }
            }
        }
    }
}
