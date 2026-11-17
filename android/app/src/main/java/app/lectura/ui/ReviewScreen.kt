package app.lectura.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.data.SavedWord
import app.lectura.data.Srs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReviewViewModel(private val app: LecturaApp) : ViewModel() {
    val queue = mutableStateListOf<SavedWord>()
    var revealed by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var loaded by mutableStateOf(false)
    var nextDue by mutableStateOf<Long?>(null)

    fun load() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        queue.clear()
        queue.addAll(app.db.words().due(now).shuffled())
        nextDue = app.db.words().nextDue(now)
        revealed = false
        loaded = true
    }

    fun grade(g: Int) {
        val w = queue.removeFirstOrNull() ?: return
        val upd = Srs.schedule(w, g)
        if (g == Srs.AGAIN) queue.add(upd)
        revealed = false
        done++
        viewModelScope.launch {
            app.db.words().update(upd)
            if (queue.isEmpty()) nextDue = app.db.words().nextDue(System.currentTimeMillis())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen() {
    val app = LecturaApp.instance
    val vm: ReviewViewModel = viewModel { ReviewViewModel(app) }
    LaunchedEffect(Unit) { vm.load() }
    val card = vm.queue.firstOrNull()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = { Text(if (card != null) "Повторение · осталось ${vm.queue.size}" else "Повторение") })
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (card == null) {
                if (!vm.loaded) return@Column
                Spacer(Modifier.height(48.dp))
                Text(if (vm.done > 0) "Готово! Повторено: ${vm.done} 🎉" else "Сейчас нечего повторять", style = MaterialTheme.typography.titleLarge)
                vm.nextDue?.let {
                    Text(
                        "Следующие слова: " + SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(it)),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                return@Column
            }

            Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled = !vm.revealed) { vm.revealed = true }) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(card.word, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { app.player.speakOnce(card.word) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Произнести")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        highlight(card.sentence, card.word),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (vm.revealed) {
                        HorizontalDivider(Modifier.padding(vertical = 20.dp))
                        Text(card.ru, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(
                            listOf(card.lemma, card.grammar).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            card.sentenceRu,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Spacer(Modifier.height(32.dp))
                        Text("Нажмите, чтобы увидеть перевод", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (vm.revealed) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GradeButton("Снова", Srs.label(card, Srs.AGAIN), Color(0xFFC62828), Modifier.weight(1f)) { vm.grade(Srs.AGAIN) }
                    GradeButton("Трудно", Srs.label(card, Srs.HARD), Color(0xFFEF6C00), Modifier.weight(1f)) { vm.grade(Srs.HARD) }
                    GradeButton("Хорошо", Srs.label(card, Srs.GOOD), Color(0xFF2E7D32), Modifier.weight(1f)) { vm.grade(Srs.GOOD) }
                    GradeButton("Легко", Srs.label(card, Srs.EASY), Color(0xFF1565C0), Modifier.weight(1f)) { vm.grade(Srs.EASY) }
                }
            } else {
                Button(onClick = { vm.revealed = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Показать ответ")
                }
            }
        }
    }
}

@Composable
private fun GradeButton(title: String, sub: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(sub, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun highlight(sentence: String, word: String) = buildAnnotatedString {
    val i = sentence.indexOf(word)
    if (i < 0) {
        append(sentence)
    } else {
        append(sentence.substring(0, i))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word) }
        append(sentence.substring(i + word.length))
    }
}
