package app.lectura.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.data.InboxItem
import app.lectura.data.PersonalRepository
import app.lectura.model.ABOUT_QUESTIONS
import app.lectura.model.AboutQuestion
import app.lectura.model.PersonalResult
import app.lectura.playback.speakEs
import app.lectura.review.Good
import app.lectura.speech.MatchResult
import app.lectura.speech.SpeakButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Синхронизация с файлами от Mac при каждом показе экрана. */
@Composable
private fun SyncPersonalOnResume() {
    val app = LecturaApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            val n = runCatching { app.personal.sync() }.getOrDefault(0)
            if (n > 0) Toast.makeText(ctx, "Пришли переводы: +$n фраз в повторение", Toast.LENGTH_LONG).show()
        }
    }
}

/** Карточка «Мои фразы» во вкладке «Говорить». */
@Composable
fun MyPhrasesCard(onOpen: () -> Unit) {
    val inbox by LecturaApp.instance.db.inbox().all().collectAsState(initial = emptyList())
    SyncPersonalOnResume()
    val about = inbox.filter { it.kind == InboxItem.ABOUT }.map { it.ref }.toSet().size
    val phrases = inbox.count { it.kind == InboxItem.PHRASE }
    val pending = inbox.count { it.pending }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🙋 Мои фразы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Расскажите о себе и запишите, что хотите уметь сказать, — по-русски. Переводы придут в повторение.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Обо мне: $about из ${ABOUT_QUESTIONS.size} · «как сказать»: $phrases" + if (pending > 0) " · ждут перевода: $pending" else "",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPhrasesScreen(onBack: () -> Unit, onTrain: () -> Unit) {
    val app = LecturaApp.instance
    val scope = rememberCoroutineScope()
    val inbox by app.db.inbox().all().collectAsState(initial = emptyList())
    val results by app.personal.results.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    SyncPersonalOnResume()
    DisposableEffect(Unit) { onDispose { app.player.stopSpeech() } }
    val pending = inbox.count { it.pending }
    val trainable = trainableAbout(inbox, results)

    fun afterChange() = scope.launch { runCatching { app.personal.sync() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Мои фразы") },
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
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Как это работает", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("1. Пишите по-русски: ответы о себе или что хотите уметь сказать.")
                        Text("2. Подключите телефон к Mac и запустите:")
                        Text(PersonalRepository.MAC_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text("3. Откройте этот экран — фразы с озвучкой сами попадут в повторение.")
                        if (pending > 0) {
                            Text("⏳ Ждут перевода: $pending", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Обо мне") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Как сказать…") })
                }
            }
            if (tab == 0) {
                if (trainable.isNotEmpty()) {
                    item {
                        Button(onClick = onTrain, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text("🗣 Тренировка: расскажите о себе (${trainable.size})")
                        }
                    }
                }
                items(ABOUT_QUESTIONS, key = { it.id }) { q ->
                    val item = inbox.firstOrNull { it.kind == InboxItem.ABOUT && it.ref == q.id }
                    AboutCard(q, item, item?.let { i -> results.firstOrNull { it.matches(i.id, i.ru) } }) { ru ->
                        scope.launch {
                            app.userData.setAbout(q.id, ru)
                            afterChange()
                        }
                    }
                }
            } else {
                item {
                    AskCard { ru ->
                        scope.launch {
                            app.userData.addInbox(ru)
                            afterChange()
                        }
                    }
                }
                items(inbox.filter { it.kind == InboxItem.PHRASE }, key = { it.id }) { i ->
                    InboxCard(i, results.firstOrNull { it.matches(i.id, i.ru) }) {
                        scope.launch {
                            app.userData.deleteInbox(i.id)
                            afterChange()
                        }
                    }
                }
            }
        }
    }
}

/** Вопросы «Обо мне», на которые уже пришёл ответ по-испански. */
private fun trainableAbout(inbox: List<InboxItem>, results: List<PersonalResult>): List<Pair<AboutQuestion, PersonalResult>> {
    return ABOUT_QUESTIONS.mapNotNull { q ->
        val item = inbox.firstOrNull { it.kind == InboxItem.ABOUT && it.ref == q.id } ?: return@mapNotNull null
        val r = results.firstOrNull { it.matches(item.id, item.ru) && it.accepted.isNotEmpty() } ?: return@mapNotNull null
        q to r
    }
}

@Composable
private fun AboutCard(q: AboutQuestion, item: InboxItem?, result: PersonalResult?, onSave: (String) -> Unit) {
    var editing by remember(item?.id) { mutableStateOf(false) }
    var text by remember(item?.id) { mutableStateOf(item?.ru.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(q.es, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(q.ru, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { speakEs(q.es) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести") }
            }
            when {
                editing || item == null && text.isNotEmpty() -> {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(q.example) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onSave(text)
                            editing = false
                        }, enabled = text.isNotBlank()) { Text("Сохранить") }
                        TextButton(onClick = {
                            text = item?.ru.orEmpty()
                            editing = false
                        }) { Text("Отмена") }
                    }
                }
                item == null -> OutlinedButton(onClick = { editing = true }) { Text("Ответить по-русски") }
                else -> {
                    if (result != null) {
                        result.phrases.forEach { p -> PhraseLine(p.es, p.ru, p.id) }
                    } else {
                        Text(item.ru, style = MaterialTheme.typography.bodyMedium)
                        Text("⏳ Ждёт перевода на Mac", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { editing = true }, contentPadding = PaddingValues(0.dp)) { Text("Изменить ответ") }
                }
            }
        }
    }
}

@Composable
private fun PhraseLine(es: String, ru: String, id: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(es, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(ru, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { speakEs(es, id) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести") }
    }
}

@Composable
private fun AskCard(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Что хотите уметь сказать?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Например, то, что не получилось сказать сегодня: «у меня течёт кран», «можно перенести встречу на пятницу?»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("По-русски…") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                onAdd(text)
                text = ""
            }, enabled = text.isNotBlank()) { Text("Добавить") }
        }
    }
}

@Composable
private fun InboxCard(item: InboxItem, result: PersonalResult?, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("«${item.ru}»", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (result != null) {
                    result.phrases.forEach { p ->
                        PhraseLine(p.es, p.ru, p.id)
                        if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("⏳ Ждёт перевода на Mac", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Удалить") }
        }
    }
}

// ------------------------------------------------------------------ тренировка «Обо мне»

/** Вопросы о вас звучат голосом, вы отвечаете вслух; засчитывается ваш ответ или любой из допустимых. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutTrainScreen(onBack: () -> Unit) {
    val app = LecturaApp.instance
    val inbox by app.db.inbox().all().collectAsState(initial = null)
    val results by app.personal.results.collectAsStateWithLifecycle()
    val vm: ScenarioVm = viewModel(key = "about-train") { ScenarioVm() }
    DisposableEffect(Unit) { onDispose { vm.silence() } }
    val all = inbox ?: return Loading(Modifier)
    val items = remember(all.size, results.size) { trainableAbout(all, results).shuffled() }
    var index by remember { mutableIntStateOf(0) }
    var passed by remember { mutableIntStateOf(0) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Расскажите о себе")
                        Text("${minOf(index + 1, items.size)} из ${items.size}", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (index >= items.size) {
                Text("Готово! 🎉", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Ответов засчитано: $passed из ${items.size}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        index = 0
                        passed = 0
                    }, modifier = Modifier.weight(1f)) { Text("Ещё раз") }
                    Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Готово") }
                }
                return@Column
            }
            val (q, r) = items[index]
            val clip = app.personal.clip("q-${q.id}")
            var result by remember(index) { mutableStateOf<MatchResult?>(null) }
            var counted by remember(index) { mutableStateOf(false) }
            fun count() {
                if (!counted) {
                    counted = true
                    passed++
                }
            }
            LaunchedEffect(index) {
                delay(300)
                vm.say(clip, q.es)
            }
            Text(q.es, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(q.ru, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { vm.say(clip, q.es) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Text("  Ещё раз")
            }
            Spacer(Modifier.height(4.dp))
            key(index) {
                SpeakButton(expected = r.accepted.first(), accepted = r.accepted, modifier = Modifier.fillMaxWidth()) { res ->
                    result = res
                    if (res.passed) count()
                }
            }
            val res = result
            if (res != null && res.passed) Text("Отлично! ✓", color = Good, fontWeight = FontWeight.SemiBold)
            if (res != null && !res.passed) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ваш ответ:", style = MaterialTheme.typography.labelLarge)
                        r.phrases.forEach { p -> PhraseLine(p.es, p.ru, p.id) }
                        if (!counted) TextButton(onClick = { count() }) { Text("Я ответил правильно — засчитать") }
                    }
                }
            }
            if (res != null) {
                Button(onClick = { index++ }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Дальше") }
            } else {
                TextButton(onClick = { index++ }, modifier = Modifier.fillMaxWidth()) { Text("Пропустить ⏭") }
            }
        }
    }
}
