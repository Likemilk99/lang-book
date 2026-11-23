package app.lectura.ui

import android.widget.Toast

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.data.BookAudio
import app.lectura.data.ChapterActivity
import app.lectura.data.ExpressionIndex
import app.lectura.data.ExpressionMatch
import app.lectura.data.SaveResult
import app.lectura.data.SavedWord
import app.lectura.data.WordCards
import app.lectura.data.allExamples
import app.lectura.data.WordStatus
import app.lectura.data.phraseIndex
import app.lectura.data.vocabIndex
import app.lectura.model.Book
import app.lectura.model.Chapter
import app.lectura.model.GlossaryItem
import app.lectura.model.PhraseItem
import app.lectura.model.Sentence
import app.lectura.model.Token
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

    /** Статус слов по форме и по начальной форме — для подсветки (учу / знаю), в том числе из других книг. */
    val vocab = app.db.words().all().map { vocabIndex(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Сохранённые фразы и выражения → статус: подсвечиваем их в тексте целиком. */
    val phrases = app.db.words().all().map { phraseIndex(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Устойчивые выражения из словаря учебной программы («otra vez», «darse cuenta»…). */
    val expressions = MutableStateFlow(ExpressionIndex(emptyList()))

    /** Карточки по ключу — чтобы по любой форме слова найти его карточку (hablamos → hablar). */
    val cards = app.db.words().all().map { list -> list.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Карточка слова: по начальной форме, а для карточек без неё — по форме из текста. */
    fun cardFor(tk: Token): SavedWord? = WordCards.find(cards.value, tk)

    /**
     * «Учить» / «Знаю» слово из текста: одна карточка на начальную форму. Если карточка уже есть —
     * к ней добавляется это предложение как пример.
     */
    fun saveWord(sentence: Sentence, tokenIndex: Int, known: Boolean = false) {
        val tk = sentence.tokens[tokenIndex]
        viewModelScope.launch {
            val card = WordCards.fromToken(tk, sentence, bookId, System.currentTimeMillis(), app.lexicon.get())
            val result = app.userData.saveWord(card, known)
            if (result == SaveResult.EXAMPLE_ADDED) {
                Toast.makeText(app, "Уже в словаре (${card.word}) — добавлен пример", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** «Я это слово знаю» — сразу средний уровень. */
    fun markKnown(sentence: Sentence, tokenIndex: Int) = saveWord(sentence, tokenIndex, known = true)

    fun removeWord(card: SavedWord) = viewModelScope.launch { app.db.words().delete(card) }

    /** Слово из словарика главы: «Учить» — как новое, «Знаю» — сразу средний уровень. */
    fun saveGlossary(item: GlossaryItem, chapter: Chapter, known: Boolean) {
        val w = glossaryWord(item, chapter, System.currentTimeMillis())
        viewModelScope.launch { app.userData.saveWord(w, known) }
    }

    /** «Учить все»: слова словарика, которых ещё нет в словаре, — в порядке словарика (так же и придут в повторение). */
    fun saveGlossaryAll(items: List<GlossaryItem>, chapter: Chapter) {
        val now = System.currentTimeMillis()
        val words = items.mapIndexed { i, g -> glossaryWord(g, chapter, now + i) }
        viewModelScope.launch { words.forEach { app.userData.saveWord(it) } }
    }

    private fun glossaryWord(item: GlossaryItem, chapter: Chapter, now: Long): SavedWord {
        val form = item.form.ifBlank { item.l }
        val src = chapter.sentences.firstOrNull { s -> s.tokens.any { it.t.equals(form, ignoreCase = true) } }
        return WordCards.fromGlossary(item, src, bookId, now)
    }

    fun markChapterRead(chapter: Int) = viewModelScope.launch {
        runCatching { app.userData.recordChapterActivity(bookId, chapter, ChapterActivity.READ, 100) }
    }

    init {
        viewModelScope.launch {
            runCatching { expressions.value = ExpressionIndex.from(app.curriculum.vocab()) }
        }
        viewModelScope.launch {
            runCatching {
                val b = app.books.load(bookId)
                val p = app.db.progress().get(bookId)
                app.player.open(b, p?.chapter ?: 0, p?.sentence ?: 0)
                book.value = b
            }.onFailure { error.value = it.message ?: it.toString() }
        }
    }

    /** Сохранить фразу (несколько слов) как отдельную карточку. */
    fun savePhrase(
        es: String,
        ru: String,
        sentence: String,
        sentenceRu: String,
        known: Boolean = false,
        lemma: String = es,
        grammar: String = "фраза",
    ) {
        val key = es.lowercase().trim()
        if (key.isBlank() || (!known && key in savedKeys.value)) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val w = SavedWord(
                key = key, word = es.trim(), lemma = lemma.trim(), ru = ru.trim(), grammar = grammar,
                sentence = sentence, sentenceRu = sentenceRu, bookId = bookId, createdAt = now, due = now,
                kind = SavedWord.KIND_PHRASE,
            )
            if (known) app.userData.markKnown(w) else app.userData.addWord(w)
        }
    }

    /** Выражение из словаря: в карточку — как в тексте (если совпадает со словарной формой), иначе словарная форма. */
    fun saveExpression(sentence: Sentence, m: ExpressionMatch, known: Boolean) {
        val surface = phraseText(sentence, m.range)
        val same = ExpressionIndex.fold(surface) == ExpressionIndex.fold(m.expr.es)
        val word = if (same) surface.replaceFirstChar { it.lowercase() } else m.expr.es
        savePhrase(
            word, m.expr.ru, sentence.text, sentence.ru, known = known, lemma = m.expr.es,
            grammar = if (same) "выражение" else "выражение · в тексте: $surface",
        )
    }

    fun removeKey(key: String) = viewModelScope.launch { app.db.words().deleteKey(key) }

    /** Фраза из разговорника главы: пример берём из предложения главы, где она встречается. */
    fun savePhraseItem(item: PhraseItem, chapter: Chapter) {
        val norm = item.es.lowercase().trim().trimEnd('.', '!', '?')
        val src = chapter.sentences.firstOrNull { it.text.lowercase().contains(norm.trimStart('¿', '¡')) }
        savePhrase(item.es, item.ru, src?.text ?: item.es, src?.ru ?: item.ru)
    }

}

private data class Selection(val sentence: Int, val token: Int)

/** «5,2 МБ» */
fun megabytes(bytes: Long): String = "%.1f МБ".format(java.util.Locale.forLanguageTag("ru"), bytes / 1_000_000.0)

private val RATES = listOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val app = LecturaApp.instance
    val vm: ReaderViewModel = viewModel(key = bookId) { ReaderViewModel(app, bookId) }
    val book by vm.book.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val vocab by vm.vocab.collectAsStateWithLifecycle()
    val phrases by vm.phrases.collectAsStateWithLifecycle()
    val ps by app.player.state.collectAsStateWithLifecycle()
    val fontSize by app.prefs.fontSize.collectAsStateWithLifecycle()
    val showRu by app.prefs.showRu.collectAsStateWithLifecycle()
    val audioInstalled by app.bookAudio.installed.collectAsStateWithLifecycle()
    val audioDownloads by app.bookAudio.downloads.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf<Selection?>(null) }
    val expressions by vm.expressions.collectAsStateWithLifecycle()
    var chaptersMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showPhrases by remember { mutableStateOf(false) }
    var showGlossary by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val b = book
    val audioFailure = audioDownloads[bookId] as? BookAudio.Download.Failed
    LaunchedEffect(audioFailure) {
        audioFailure?.let { Toast.makeText(ctx, "Озвучка не скачалась: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val chapterIdx = if (ps.bookId == bookId) ps.chapter else 0
    val chapter = b?.chapters?.getOrNull(chapterIdx)
    // Выражение, в которое входит выбранное слово («otra vez»): подсвечиваем его целиком.
    val selectedExpression: ExpressionMatch? = remember(selection, chapter, expressions) {
        val sel = selection ?: return@remember null
        val sent = chapter?.sentences?.getOrNull(sel.sentence) ?: return@remember null
        expressions.find(sent).firstOrNull { sel.token in it.range }
    }

    // Абзацы главы вместе с «глобальными» номерами предложений внутри главы.
    val paragraphs = remember(chapter) {
        var n = 0
        chapter?.paragraphs?.map { p -> p.sentences.map { s -> (n++) to s } }.orEmpty()
    }
    val listState = rememberLazyListState()
    fun paragraphOf(sentence: Int) = paragraphs.indexOfFirst { p -> p.any { it.first == sentence } }.coerceAtLeast(0)

    LaunchedEffect(chapter) {
        // С начала главы — показываем заголовок (и словарик); иначе — абзац, где остановились.
        if (chapter != null) listState.scrollToItem(if (ps.sentence == 0) 0 else paragraphOf(ps.sentence) + 1)
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
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            fun go(route: String) {
                                moreMenu = false
                                app.player.pause()
                                onNavigate(route)
                            }
                            b?.audio?.let { pack ->
                                val have = audioInstalled[bookId]
                                val dl = audioDownloads[bookId]
                                when {
                                    dl is BookAudio.Download.Running -> DropdownMenuItem(
                                        text = { Text("🎙 Скачиваю озвучку… ${(dl.progress * 100).toInt()}%") },
                                        onClick = {},
                                        enabled = false,
                                    )
                                    have == null || have != pack.version -> DropdownMenuItem(
                                        text = {
                                            Text(
                                                (if (have == null) "🎙 Скачать озвучку" else "🎙 Обновить озвучку") +
                                                    " (${megabytes(pack.size)})",
                                            )
                                        },
                                        onClick = {
                                            moreMenu = false
                                            app.bookAudio.download(b)
                                        },
                                    )
                                    else -> DropdownMenuItem(text = { Text("🗑 Удалить озвучку") }, onClick = {
                                        moreMenu = false
                                        app.player.pause()
                                        app.bookAudio.delete(bookId)
                                    })
                                }
                                HorizontalDivider()
                            }
                            DropdownMenuItem(text = { Text("💬 Полезные фразы") }, onClick = {
                                moreMenu = false
                                showPhrases = true
                            })
                            DropdownMenuItem(text = { Text("❓ Вопросы по главе") }, onClick = { go("questions/$bookId/$chapterIdx") })
                            DropdownMenuItem(text = { Text("🗣 Повторять за диктором") }, onClick = { go("speak/$bookId/$chapterIdx/shadow") })
                            DropdownMenuItem(text = { Text("🎭 Ролевая игра") }, onClick = { go("speak/$bookId/$chapterIdx/role") })
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Шрифт крупнее") },
                                leadingIcon = { Icon(Icons.Filled.TextIncrease, null) },
                                onClick = { app.prefs.setFontSize(fontSize + 2) },
                            )
                            DropdownMenuItem(
                                text = { Text("Шрифт мельче") },
                                leadingIcon = { Icon(Icons.Filled.TextDecrease, null) },
                                onClick = { app.prefs.setFontSize(fontSize - 2) },
                            )
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
                        if (chapter.glossary.isNotEmpty()) {
                            OutlinedButton(onClick = { showGlossary = true }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("📚 Словарик главы (${chapter.glossary.size}) — перед чтением")
                            }
                        }
                    }
                }
                itemsIndexed(paragraphs) { _, para ->
                    Column {
                        ParagraphText(
                            sentences = para,
                            current = if (ps.bookId == bookId) ps.sentence else -1,
                            selection = selection,
                            highlight = selectedExpression?.range,
                            vocab = vocab,
                            phrases = phrases,
                            expressions = expressions,
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
                    // Дочитали до конца главы — отмечаем её прочитанной.
                    LaunchedEffect(bookId, chapterIdx) { vm.markChapterRead(chapterIdx) }
                    ChapterEnd(
                        chapter = chapter,
                        hasNext = chapterIdx + 1 < b.chapters.size,
                        onNext = { app.player.seekChapter(chapterIdx + 1) },
                        onPhrases = { showPhrases = true },
                        onNavigate = { mode ->
                            app.player.pause()
                            onNavigate(
                                when {
                                    mode == "questions" -> "questions/$bookId/$chapterIdx"
                                    mode.startsWith("grammar/") -> mode
                                    else -> "speak/$bookId/$chapterIdx/$mode"
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    val sel = selection
    if (sel != null && chapter != null) {
        val sentence = chapter.sentences.getOrNull(sel.sentence)
        val token = sentence?.tokens?.getOrNull(sel.token)
        if (sentence != null && token != null) {
            val card = WordCards.find(cards, token)
            WordSheet(
                sentence = sentence,
                tokenIndex = sel.token,
                card = card,
                status = vocab[token.key] ?: token.l?.lowercase()?.let { vocab[it] },
                onSave = { vm.saveWord(sentence, sel.token) },
                onRemove = { card?.let { vm.removeWord(it) } },
                onKnown = { vm.markKnown(sentence, sel.token) },
                savedKeys = saved,
                vocab = vocab,
                phraseStatus = selectedExpression?.let { m ->
                    phrases[ExpressionIndex.phraseKey(m.expr.es)] ?: phrases[ExpressionIndex.phraseKey(phraseText(sentence, m.range))]
                },
                onLearnWord = { i -> if (vm.cardFor(sentence.tokens[i]) == null) vm.saveWord(sentence, i) },
                onSavePhrase = { es, ru -> vm.savePhrase(es, ru, sentence.text, sentence.ru) },
                expression = selectedExpression,
                expressions = expressions,
                onSaveExpression = { m, known -> vm.saveExpression(sentence, m, known) },
                onReadFromHere = {
                    selection = null
                    app.player.seek(sel.sentence)
                    app.player.play()
                },
                onDismiss = { selection = null },
            )
        }
    }

    if (showGlossary && chapter != null) {
        GlossarySheet(
            chapter = chapter,
            vocab = vocab,
            onSave = { item, known -> vm.saveGlossary(item, chapter, known) },
            onSaveAll = { items -> vm.saveGlossaryAll(items, chapter) },
            onDismiss = { showGlossary = false },
        )
    }

    if (showPhrases && chapter != null) {
        PhrasebookSheet(
            chapter = chapter,
            saved = saved,
            onAdd = { vm.savePhraseItem(it, chapter) },
            onRemove = { vm.removeKey(it.es.lowercase().trim()) },
            onDismiss = { showPhrases = false },
        )
    }
}

@Composable
private fun ChapterEnd(
    chapter: Chapter,
    hasNext: Boolean,
    onNext: () -> Unit,
    onPhrases: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Закрепить главу", style = MaterialTheme.typography.titleMedium)
        if (chapter.grammar.isNotEmpty()) {
            val titles by androidx.compose.runtime.produceState(emptyMap<String, String>(), chapter) {
                value = chapter.grammar.associateWith { id ->
                    LecturaApp.instance.curriculum.topic(id)?.title ?: id
                }
            }
            Text("Грамматика в этой главе:", style = MaterialTheme.typography.labelLarge)
            chapter.grammar.forEach { id ->
                OutlinedButton(onClick = { onNavigate("grammar/$id") }, modifier = Modifier.fillMaxWidth()) {
                    Text("📐 ${titles[id] ?: id}")
                }
            }
        }
        if (chapter.phrases.isNotEmpty()) {
            OutlinedButton(onClick = onPhrases, modifier = Modifier.fillMaxWidth()) {
                Text("💬 Полезные фразы (${chapter.phrases.size})")
            }
        }
        if (chapter.questions.isNotEmpty()) {
            OutlinedButton(onClick = { onNavigate("questions") }, modifier = Modifier.fillMaxWidth()) {
                Text("❓ Вопросы по главе (${chapter.questions.size})")
            }
        }
        OutlinedButton(onClick = { onNavigate("shadow") }, modifier = Modifier.fillMaxWidth()) {
            Text("🗣 Повторять за диктором")
        }
        if (chapter.sentences.any { it.sp != null }) {
            OutlinedButton(onClick = { onNavigate("role") }, modifier = Modifier.fillMaxWidth()) {
                Text("🎭 Ролевая игра")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (hasNext) {
            FilledTonalButton(onClick = onNext) { Text("Следующая глава →") }
        } else {
            Text("Конец книги 🎉", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhrasebookSheet(
    chapter: Chapter,
    saved: Set<String>,
    onAdd: (PhraseItem) -> Unit,
    onRemove: (PhraseItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val player = LecturaApp.instance.player
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Полезные фразы", style = MaterialTheme.typography.titleLarge)
                if (chapter.phrases.isEmpty()) Text("В этой главе пока нет разговорника — обновите книгу.")
            }
            itemsIndexed(chapter.phrases) { _, ph ->
                val isSaved = ph.es.lowercase().trim() in saved
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { player.speakOnce(ph.es) }) {
                        Text(ph.es, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(ph.ru, style = MaterialTheme.typography.bodyLarge)
                        if (ph.pattern.isNotBlank()) Text(
                            "Модель: ${ph.pattern}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (ph.note.isNotBlank()) Text(
                            ph.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { if (isSaved) onRemove(ph) else onAdd(ph) }) {
                        Icon(if (isSaved) Icons.Filled.Check else Icons.Filled.Add, if (isSaved) "В словаре" else "Добавить")
                    }
                }
            }
        }
    }
}

@Composable
private fun ParagraphText(
    sentences: List<Pair<Int, Sentence>>,
    current: Int,
    selection: Selection?,
    highlight: IntRange?,
    vocab: Map<String, WordStatus>,
    phrases: Map<String, WordStatus>,
    expressions: ExpressionIndex,
    fontSize: Float,
    onWord: (Int, Int) -> Unit,
) {
    val knownColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val colors = MaterialTheme.colorScheme
    val onWordState by rememberUpdatedState(onWord)
    val text = remember(sentences, current, selection, highlight, vocab, phrases, expressions, colors) {
        buildAnnotatedString {
            sentences.forEachIndexed { k, (si, s) ->
                if (k > 0) append(" ")
                val start = length
                // Сохранённые фразы («otra vez») — одним цветом и сплошным подчёркиванием, вместе с пробелами.
                val phraseRanges = expressions.savedRanges(s, phrases)
                s.tokens.forEachIndexed { ti, tk ->
                    val phrase = phraseRanges.firstOrNull { ti in it.first }
                    if (s.spaces[ti]) {
                        if (phrase != null && ti != phrase.first.first && phrase.second == WordStatus.LEARNING) {
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = colors.tertiary)) { append(" ") }
                        } else {
                            append(" ")
                        }
                    }
                    if (tk.isWord) {
                        val wordStatus = vocab[tk.key] ?: tk.l?.lowercase()?.let { vocab[it] }
                        val status = phrase?.second ?: wordStatus
                        val isSaved = status == WordStatus.LEARNING
                        val isSelected = selection?.sentence == si && (selection.token == ti || (highlight != null && ti in highlight))
                        val style = SpanStyle(
                            color = when {
                                phrase?.second == WordStatus.LEARNING -> colors.tertiary
                                status == WordStatus.LEARNING -> colors.primary
                                status == WordStatus.KNOWN -> knownColor
                                else -> Color.Unspecified
                            },
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
            if (ps.narration) Text("🎙", modifier = Modifier.padding(start = 4.dp))
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
    card: SavedWord?,
    status: WordStatus?,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onKnown: () -> Unit,
    savedKeys: Set<String>,
    vocab: Map<String, WordStatus>,
    phraseStatus: WordStatus?,
    onLearnWord: (Int) -> Unit,
    onSavePhrase: (es: String, ru: String) -> Unit,
    expression: ExpressionMatch?,
    expressions: ExpressionIndex,
    onSaveExpression: (ExpressionMatch, Boolean) -> Unit,
    onReadFromHere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val player = LecturaApp.instance.player
    val tk = sentence.tokens[tokenIndex]
    var showSentenceRu by remember(sentence) { mutableStateOf(false) }
    var phraseMode by remember(sentence, tokenIndex) { mutableStateOf(false) }
    LaunchedEffect(tk) { if (expression == null) player.speakOnce(tk.t) } // иначе произнесётся выражение целиком

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            if (expression != null && !phraseMode) {
                ExpressionCard(sentence, expression, savedKeys, phraseStatus, vocab, expressions, onLearnWord, onSaveExpression)
                Spacer(Modifier.height(16.dp))
                Text("Слово отдельно", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
            when (tk.r) {
                "old" -> Text(
                    "Устаревшее слово — понимать полезно, учить не обязательно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                "rare" -> Text(
                    "Редкое/книжное слово — в живой речи встречается нечасто.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Одна карточка на слово: по другой форме того же слова кнопка добавляет пример, а не удаляет.
            val hasExample = card != null && card.allExamples.any { it.sentence.trim().equals(sentence.text.trim(), ignoreCase = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !hasExample) {
                    Icon(if (card != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            card == null -> "Учить"
                            hasExample -> "В словаре"
                            else -> "Добавить пример"
                        },
                    )
                }
                FilledTonalButton(onClick = onKnown, enabled = status != WordStatus.KNOWN) {
                    Text(if (status == WordStatus.KNOWN) "Знаю ✓" else "Знаю")
                }
            }
            if (card != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val n = card.allExamples.size
                    Text(
                        "В словаре: ${card.word}" + if (n > 0) " · примеров: $n" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRemove) { Text("Убрать") }
                }
            }
            OutlinedButton(onClick = { phraseMode = !phraseMode }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (phraseMode) "Отмена" else "Выделить несколько слов (фразу)")
            }
            if (status == WordStatus.KNOWN) Text(
                "Вы знаете это слово (или его форму) — оно в практике, но не как новое.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            if (phraseMode) {
                PhraseSelector(sentence, tokenIndex, expression?.range, expressions, savedKeys) { es, ru ->
                    onSavePhrase(es, ru)
                    phraseMode = false
                }
                return@Column
            }

            Text(
                buildAnnotatedString {
                    sentence.tokens.forEachIndexed { i, t ->
                        if (sentence.spaces[i]) append(" ")
                        val bold = i == tokenIndex || (expression != null && i in expression.range)
                        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.t) } else append(t.t)
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

/** Выбор диапазона слов в предложении: тап — начало, второй тап — конец. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhraseSelector(
    sentence: Sentence,
    initial: Int,
    initialRange: IntRange?,
    expressions: ExpressionIndex,
    savedKeys: Set<String>,
    onSave: (String, String) -> Unit,
) {
    val player = LecturaApp.instance.player
    var start by remember { mutableStateOf(initialRange?.first ?: initial) }
    var end by remember { mutableStateOf(initialRange?.last ?: initial) }
    var picking by remember { mutableStateOf(initialRange == null) } // следующий тап задаёт конец
    val range = minOf(start, end)..maxOf(start, end)
    val es = phraseText(sentence, range)
    // Перевод: из словаря выражений, если фрагмент — известное выражение; иначе пословно (нужно проверить).
    val dict = remember(sentence, range, expressions) {
        expressions.lookup(es) ?: expressions.find(sentence).firstOrNull { it.range == range }?.expr
    }
    val autoRu = dict?.ru ?: range.mapNotNull { sentence.tokens[it].ru?.takeIf { r -> r.isNotBlank() } }.joinToString(" ")
    var ru by remember(range, dict) { mutableStateOf(autoRu) }

    Text("Нажмите на первое, затем на последнее слово фразы", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sentence.tokens.forEachIndexed { i, t ->
            val inRange = i in range
            Text(
                t.t,
                modifier = Modifier
                    .background(
                        if (inRange) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable {
                        if (picking) {
                            end = i
                        } else {
                            start = i
                            end = i
                        }
                        picking = !picking
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(es, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = { player.speakOnce(es) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести") }
    }
    OutlinedTextField(
        value = ru,
        onValueChange = { ru = it },
        label = { Text(if (dict != null) "Перевод из словаря (можно поправить)" else "Пословный перевод — проверьте и поправьте") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Перевод предложения: ${sentence.ru}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    val already = es.lowercase() in savedKeys
    Button(
        onClick = { onSave(es, ru) },
        enabled = !already && range.count() >= 2 && ru.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (already) "Фраза уже в словаре" else "Сохранить фразу") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlossarySheet(
    chapter: Chapter,
    vocab: Map<String, WordStatus>,
    onSave: (GlossaryItem, Boolean) -> Unit,
    onSaveAll: (List<GlossaryItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    val player = LecturaApp.instance.player
    val fresh = chapter.glossary.filter { g -> vocab[g.l.lowercase()] == null && vocab[g.form.lowercase()] == null }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Словарик главы", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Ключевые слова выше начального уровня. Просмотрите их до чтения — текст пойдёт легче.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fresh.isNotEmpty()) {
                    Button(onClick = { onSaveAll(fresh) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Icon(Icons.Filled.BookmarkBorder, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (fresh.size == chapter.glossary.size) "Учить все слова (${fresh.size})" else "Учить остальные (${fresh.size})")
                    }
                    Text(
                        "Слова, которые вы уже знаете, сначала отметьте «Знаю» — тогда они не попадут в новые.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else if (chapter.glossary.isNotEmpty()) {
                    Text(
                        "✓ Все слова словарика уже в словаре",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            itemsIndexed(chapter.glossary) { _, g ->
                val status = vocab[g.l.lowercase()] ?: vocab[g.form.lowercase()]
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { player.speakOnce(g.form.ifBlank { g.l }) }) {
                            Text(
                                g.l + if (g.form.isNotBlank() && !g.form.equals(g.l, true)) "  (${g.form})" else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(g.ru, style = MaterialTheme.typography.bodyLarge)
                            if (g.note.isNotBlank()) Text(
                                g.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (status == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onSave(g, false) }) { Text("Учить") }
                            FilledTonalButton(onClick = { onSave(g, true) }) { Text("Знаю") }
                        }
                    } else {
                        Text(
                            if (status == WordStatus.KNOWN) "✓ Знаете" else "✓ В словаре",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** Текст фрагмента предложения (токены [range]) с правильными пробелами, без запятой в конце. */
internal fun phraseText(sentence: Sentence, range: IntRange): String = ExpressionIndex.phraseOf(sentence, range)

/** Карточка устойчивого выражения, в которое входит слово: перевод целиком, из каких слов состоит, «Учить» / «Знаю». */
@Composable
private fun ExpressionCard(
    sentence: Sentence,
    m: ExpressionMatch,
    savedKeys: Set<String>,
    phraseStatus: WordStatus?,
    vocab: Map<String, WordStatus>,
    expressions: ExpressionIndex,
    onLearnWord: (Int) -> Unit,
    onSave: (ExpressionMatch, Boolean) -> Unit,
) {
    val player = LecturaApp.instance.player
    val surface = phraseText(sentence, m.range)
    val differs = ExpressionIndex.fold(surface) != ExpressionIndex.fold(m.expr.es)
    val saved = phraseStatus != null || surface.lowercase() in savedKeys || m.expr.es.lowercase() in savedKeys
    val onCard = MaterialTheme.colorScheme.onTertiaryContainer
    LaunchedEffect(m) { player.speakOnce(surface) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Выражение · ${m.expr.level}", style = MaterialTheme.typography.labelMedium, color = onCard)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(surface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                IconButton(onClick = { player.speakOnce(surface) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести") }
            }
            if (differs) Text("словарная форма: ${m.expr.es}", style = MaterialTheme.typography.bodyMedium, color = onCard)
            Text(m.expr.ru, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
            if (saved) {
                Text(
                    if (phraseStatus == WordStatus.KNOWN) "✓ Выражение в словаре · знаю" else "✓ Выражение в словаре · учу",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(m, false) }) { Text("Учить выражение") }
                    FilledTonalButton(onClick = { onSave(m, true) }) { Text("Знаю") }
                }
            }

            // Из каких слов: употребляется ли слово само по себе (есть в программе — с уровнем) и где ещё встречается.
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = onCard.copy(alpha = 0.2f))
            Text("Слова по отдельности", style = MaterialTheme.typography.labelLarge, color = onCard)
            for (i in m.range) {
                val tk = sentence.tokens[i]
                if (!tk.isWord) continue
                val entry = expressions.word(tk)
                val st = vocab[tk.key] ?: tk.l?.lowercase()?.let { vocab[it] }
                val lemma = entry?.es ?: tk.l
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(tk.t) }
                                if (!lemma.isNullOrBlank() && !lemma.equals(tk.t, ignoreCase = true)) append(" → $lemma")
                                val ru = entry?.ru ?: tk.ru
                                if (!ru.isNullOrBlank()) append(" — $ru")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (entry != null) "само по себе — частое слово, уровень ${entry.level}" else "само по себе почти не встречается — учите в выражении",
                            style = MaterialTheme.typography.bodySmall,
                            color = onCard,
                        )
                        val also = if (entry == null || ExpressionIndex.fold(tk.t) in FUNCTION_WORDS) emptyList() else expressions.containing(tk, m.expr, limit = 3)
                        if (also.isNotEmpty()) Text(
                            "ещё: " + also.joinToString(", ") { "${it.es} — ${it.ru}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = onCard,
                        )
                    }
                    when {
                        st == WordStatus.KNOWN -> Text("знаю ✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        st == WordStatus.LEARNING -> Text("учу ✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        entry != null -> TextButton(onClick = { onLearnWord(i) }) { Text("Учить") }
                    }
                }
            }
        }
    }
}

/** Служебные слова: список «где ещё встречается» для них бесполезен (их сотни). */
private val FUNCTION_WORDS = setOf(
    "a", "al", "de", "del", "en", "con", "por", "para", "que", "el", "la", "los", "las", "lo", "un", "una", "uno",
    "y", "o", "se", "me", "te", "nos", "os", "le", "les", "no", "sin", "sobre", "es", "ser", "estar", "hay", "tener", "hacer",
)
