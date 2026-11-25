package app.lectura.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectura.LecturaApp
import app.lectura.data.ChatMsg
import app.lectura.data.ChatState
import app.lectura.data.MacLink
import app.lectura.model.CHAT_LEVELS
import app.lectura.model.CHAT_TOPICS
import app.lectura.model.ChatPair
import app.lectura.model.ChatTopic
import app.lectura.model.ChatVoice
import app.lectura.model.VocabEntry
import app.lectura.model.customChatTopic
import app.lectura.speech.SpeechInput
import app.lectura.speech.SpeechTts
import kotlinx.coroutines.launch
import java.util.Locale

/** Карточка во вкладке «Говорить». */
@Composable
fun LiveChatCard(onOpen: () -> Unit) {
    val st by LecturaApp.instance.chat.state.collectAsStateWithLifecycle()
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🗨️ Живой разговор", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val s = st
            Text(
                if (s != null) "Идёт разговор: ${s.topic.emoji} ${s.topic.title} · ${s.level}. Нажмите, чтобы продолжить."
                else "Свободный разговор с персонажем на вашем уровне: голосом или текстом, с подсказками. Нужен Mac.",
                style = MaterialTheme.typography.bodySmall,
            )
            MacStatusRow(0, clickable = false)
        }
    }
}

/** «Живой разговор»: выбор темы или сам разговор, если он уже идёт. */
@Composable
fun LiveChatScreen(onBack: () -> Unit) {
    val app = LecturaApp.instance
    MacLinkWhileVisible()
    val st by app.chat.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { app.chat.stopAudio() } }
    val s = st
    if (s == null) ChatSetup(onBack) else ChatRoom(s, onBack)
}

// -------------------------------------------------------------------- выбор темы

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChatSetup(onBack: () -> Unit) {
    val app = LecturaApp.instance
    val mac by app.mac.state.collectAsStateWithLifecycle()
    var level by rememberSaveable { mutableStateOf(app.prefs.chatLevel) }
    var custom by rememberSaveable { mutableStateOf(app.prefs.chatCustomTopic) }
    val online = mac.status == MacLink.Status.IDLE || mac.status == MacLink.Status.BUSY
    val ready = online && mac.chat

    fun start(topic: ChatTopic) {
        app.prefs.chatLevel = level
        app.chat.start(topic, level)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Живой разговор") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Собеседник играет роль в сцене и говорит на вашем уровне. Отвечайте голосом или текстом — " +
                        "можно вставить русское слово, если не знаете испанского. «Подсказка» внизу покажет слова и пример ответа.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                MacStatusRow(0)
                if (online && !mac.chat) {
                    Text(
                        "Сервер на Mac старой версии — перезапустите его (Lectura Server.command).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                Text("Уровень", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CHAT_LEVELS.forEach { l ->
                        FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l) })
                    }
                }
            }
            item { VoiceSettings(mac) }
            item {
                Text("Темы", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            }
            items(CHAT_TOPICS, key = { it.id }) { t ->
                Card(onClick = { if (ready) start(t) }, modifier = Modifier.fillMaxWidth(), enabled = ready) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${t.emoji} ${t.title}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            t.goals.joinToString(" · ") { it.ru },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✍️ Своя тема", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = custom,
                            onValueChange = { custom = it.take(300) },
                            placeholder = { Text("Например: звоню в школу записать ребёнка") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                        Button(
                            onClick = {
                                app.prefs.chatCustomTopic = custom
                                start(customChatTopic(custom))
                            },
                            enabled = ready && custom.isNotBlank(),
                        ) { Text("Начать") }
                    }
                }
            }
            if (!ready) {
                item {
                    Text(
                        "Разговор ведёт Mac: запустите на нём Lectura Server.command (дома, в той же сети Wi-Fi).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Голос собеседника и проверка Whisper; меняется и прямо в разговоре. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSettings(mac: MacLink.State, compact: Boolean = false) {
    val chat = LecturaApp.instance.chat
    val prefs = LecturaApp.instance.prefs
    var voice by remember { mutableStateOf(chat.voice) }
    var whisper by remember { mutableStateOf(chat.whisper) }
    var autoSend by remember { mutableStateOf(prefs.chatAutoSend) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!compact) Text("Голос собеседника", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatVoice.entries.forEach { v ->
                FilterChip(
                    selected = voice == v,
                    onClick = {
                        voice = v
                        chat.voice = v
                    },
                    label = { Text(v.title) },
                )
            }
        }
        if (!compact) {
            Text(
                when (voice) {
                    ChatVoice.NONE -> "Только текст — быстрее всего."
                    ChatVoice.PHONE -> "Системный голос телефона: начинает говорить сразу, как появилась фраза."
                    ChatVoice.MAC -> "Голос Mac (Qwen3-TTS): живее, но первая фраза звучит примерно через 5 с."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (voice == ChatVoice.MAC) {
            if (mac.status in setOf(MacLink.Status.IDLE, MacLink.Status.BUSY) && !mac.macAudio) {
                Text(
                    "Mac запущен без голоса — реплики прочитает телефон.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            CheckRow(
                checked = whisper,
                text = "Проверять озвучку Whisper (медленнее: до 3 попыток на фразу)",
            ) {
                whisper = it
                chat.whisper = it
            }
        }
        if (!compact) {
            CheckRow(checked = autoSend, text = "Голосовой ответ отправлять сразу, без правки") {
                autoSend = it
                prefs.chatAutoSend = it
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, text: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// -------------------------------------------------------------------- разговор

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChatRoom(st: ChatState, onBack: () -> Unit) {
    val app = LecturaApp.instance
    val mac by app.mac.state.collectAsStateWithLifecycle()
    var input by rememberSaveable(st.id) { mutableStateOf("") }
    var hintLevel by rememberSaveable(st.id) { mutableIntStateOf(0) }
    var summary by rememberSaveable(st.id) { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    /** Bumped on every send: the mic stops, and the next answer needs a new tap. */
    var sent by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val lastText = st.messages.lastOrNull()?.text.orEmpty()
    LaunchedEffect(st.messages.size, lastText.length, st.busy) {
        if (st.messages.isNotEmpty()) list.animateScrollToItem(st.messages.size)
    }
    // Новый ход — подсказка снова свёрнута.
    LaunchedEffect(st.messages.size) { if (!st.busy) hintLevel = 0 }

    fun send(spoken: Boolean) {
        if (input.isBlank() || st.busy) return
        app.chat.say(input, spoken)
        input = ""
        hintLevel = 0
        sent++
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${st.topic.emoji} ${st.topic.title}", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${st.level} · ${app.chat.voice.title}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Готово" else "Звук") }
                    TextButton(onClick = { summary = true }) { Text("Итог") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).navigationBarsPadding().imePadding()) {
            if (showSettings) {
                Box(Modifier.padding(horizontal = 16.dp)) { VoiceSettings(mac, compact = true) }
                HorizontalDivider()
            }
            if (st.topic.goals.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    st.topic.goals.forEach { g ->
                        val done = g.id in st.goalsDone
                        Text(
                            (if (done) "✓ " else "○ ") + g.ru,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(st.messages, key = { it.key }) { m ->
                    if (m.mine) MyBubble(m) else PartnerBubble(m)
                }
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (st.error.isNotBlank()) {
                            Text(st.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { app.chat.retry() }, enabled = !st.busy) { Text("Повторить") }
                        }
                        if (st.ended) {
                            Text("Сцена закончилась 🎉", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { summary = true }) { Text("Итог разговора") }
                        }
                    }
                }
            }
            HintPanel(st, hintLevel, onInsert = { w -> input = (input.trimEnd() + " " + w).trimStart() })
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { hintLevel = if (hintLevel >= 3) 0 else hintLevel + 1 },
                    enabled = st.hints.isNotEmpty() || st.sample != null || st.topic.vocab.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(
                        when (hintLevel) {
                            0 -> "💡"
                            3 -> "✕"
                            else -> "💡+"
                        },
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(1000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ответ по-испански") },
                    maxLines = 4,
                )
                ChatMic(
                    bias = st.hints.map { it.es } + listOfNotNull(st.sample?.es),
                    enabled = !st.busy,
                    stopKey = sent,
                    onPartial = { input = it },
                    onFinal = { text ->
                        input = text
                        if (app.prefs.chatAutoSend) send(spoken = true)
                    },
                )
                FilledIconButton(onClick = { send(spoken = false) }, enabled = input.isNotBlank() && !st.busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Отправить")
                }
            }
        }
    }
    if (summary) SummaryDialog(st) { summary = false }
}

@Composable
private fun PartnerBubble(m: ChatMsg) {
    val chat = LecturaApp.instance.chat
    var open by rememberSaveable(m.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            modifier = Modifier.widthIn(max = 320.dp).clickable(enabled = !m.pending) { open = !open },
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    m.text.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (open && m.ru.isNotBlank()) {
                    Text(m.ru, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m.words.forEach { w ->
                        Text("${w.es} — ${w.ru}", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                    }
                }
                if (!m.pending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { chat.replay(m) }, modifier = Modifier.padding(0.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Послушать")
                        }
                        Text(
                            timing(m) + if (!open && m.ru.isNotBlank()) " · нажмите — перевод" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Сколько ждали: текст (первый токен) и озвучка Mac по фразам — для сравнения режимов. */
private fun timing(m: ChatMsg): String {
    fun sec(ms: Int) = String.format(Locale.US, "%.1f", ms / 1000f)
    val parts = mutableListOf<String>()
    if (m.firstMs > 0) parts += "текст ${sec(m.firstMs)} с"
    if (m.audioMs.isNotEmpty()) {
        parts += "голос Mac " + m.audioMs.joinToString(" + ") { sec(it) } + " с" + if (m.whisper) " (Whisper)" else ""
    }
    return parts.joinToString(" · ")
}

@Composable
private fun MyBubble(m: ChatMsg) {
    val chat = LecturaApp.instance.chat
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
        if (m.failed) {
            Text("не дошло до Mac", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        m.fix?.let { f ->
            Row(
                Modifier.widthIn(max = 320.dp).clickable { chat.speak(f.es) }.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text("✏️ ${f.es}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (f.ru.isNotBlank()) {
                        Text(f.ru, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Послушать", Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** Подсказка по ступеням: 1 — слова для ответа, 2 — + пример ответа, 3 — + все слова темы на уровне. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HintPanel(st: ChatState, level: Int, onInsert: (String) -> Unit) {
    if (level == 0) return
    val app = LecturaApp.instance
    val vocab by produceState<List<VocabEntry>>(emptyList(), st.topic.id, st.level, level >= 3) {
        if (level < 3) return@produceState
        val levels = when (st.level) {
            "A0", "A1" -> listOf("A1")
            "A2" -> listOf("A1", "A2")
            else -> listOf("A1", "A2", "B1")
        }
        val themes = st.topic.vocab.ifEmpty { listOf("basic") }
        val all = app.curriculum.vocab()
        value = levels.flatMap { l -> all[l]?.themes.orEmpty().filter { it.id in themes }.flatMap { it.words } }
            .distinctBy { it.l }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (st.hints.isNotEmpty()) {
                Text("Слова для ответа (нажмите — вставить):", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.hints.forEach { h ->
                        Surface(
                            onClick = { onInsert(h.es) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                                Text(h.es, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                if (h.ru.isNotBlank()) Text(h.ru, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } else if (st.busy) {
                Text("Подсказка появится вместе с ответом собеседника.", style = MaterialTheme.typography.bodySmall)
            }
            if (level >= 2) {
                st.sample?.let { s ->
                    Text("Пример ответа:", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onInsert(s.es) }) {
                            Text(s.es, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(s.ru, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { app.chat.speak(s.es) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Послушать") }
                    }
                }
            }
            if (level >= 3 && vocab.isNotEmpty()) {
                Text("Слова темы (${st.level}):", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    vocab.forEach { w ->
                        Text(
                            "${w.l} — ${w.ru}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable { onInsert(w.l) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Микрофон: распознанный текст попадает в поле ввода (его можно поправить перед отправкой).
 * A change of [stopKey] (a message was sent) ends the recording without a result: it must not keep writing
 * into the cleared field or record the partner's reply. The next answer starts with a new tap.
 */
@Composable
private fun ChatMic(
    bias: List<String>,
    enabled: Boolean,
    stopKey: Int,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = LecturaApp.instance
    val input = remember { SpeechInput(context) }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    val view = LocalView.current
    DisposableEffect(input) {
        input.onLevel = { level = it }
        onDispose { input.destroy() }
    }
    LaunchedEffect(stopKey) {
        if (listening) {
            input.cancel()
            listening = false
            level = 0f
        }
    }

    fun listen() {
        app.chat.stopAudio()
        SpeechTts.stopAll()
        listening = true
        input.manualStop = app.prefs.speechManualStop
        input.offline = app.prefs.speechOffline
        input.bias = bias.distinct().take(20)
        input.start { ev ->
            when (ev) {
                SpeechInput.Event.Ready -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                is SpeechInput.Event.Partial -> onPartial(ev.text)
                is SpeechInput.Event.Final -> {
                    listening = false
                    ev.alternatives.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onFinal)
                }
                is SpeechInput.Event.Error -> {
                    listening = false
                    Toast.makeText(context, ev.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else Toast.makeText(context, "Разрешите микрофон в настройках", Toast.LENGTH_LONG).show()
    }
    IconButton(
        onClick = {
            when {
                listening -> input.stop()
                !input.isAvailable() -> Toast.makeText(context, "Распознавание речи недоступно", Toast.LENGTH_SHORT).show()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        enabled = enabled || listening,
        colors = if (listening) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f + (level.coerceIn(0f, 10f) / 20f)),
            )
        } else IconButtonDefaults.iconButtonColors(),
    ) {
        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Закончить" else "Сказать")
    }
}

@Composable
private fun SummaryDialog(st: ChatState, onClose: () -> Unit) {
    val app = LecturaApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fixes = st.messages.mapNotNull { it.fix }.distinctBy { it.es.lowercase() }
    var saved by remember { mutableStateOf(false) }
    val said = st.messages.count { it.mine && !it.failed }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Итог разговора") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ваших реплик: $said")
                if (st.topic.goals.isNotEmpty()) {
                    Text("Цели: ${st.goalsDone.count { g -> st.topic.goals.any { it.id == g } }} из ${st.topic.goals.size}")
                    st.topic.goals.forEach { g -> Text((if (g.id in st.goalsDone) "✓ " else "○ ") + g.ru) }
                }
                if (fixes.isNotEmpty()) {
                    Text("Как сказать лучше:", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    fixes.forEach { f -> FixLine(f) }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (fixes.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val n = app.chat.saveFixes()
                                saved = true
                                Toast.makeText(context, "В повторение: +$n", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !saved,
                    ) { Text(if (saved) "Добавлено ✓" else "Фразы в повторение (${fixes.size})") }
                }
                Row {
                    TextButton(onClick = onClose) { Text("Продолжить") }
                    OutlinedButton(onClick = {
                        onClose()
                        app.chat.end()
                    }) { Text("Закончить") }
                }
            }
        },
    )
}

@Composable
private fun FixLine(f: ChatPair) {
    val chat = LecturaApp.instance.chat
    Column(Modifier.fillMaxWidth().clickable { chat.speak(f.es) }) {
        Text("• ${f.es}", style = MaterialTheme.typography.bodyMedium)
        if (f.ru.isNotBlank()) Text(f.ru, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
