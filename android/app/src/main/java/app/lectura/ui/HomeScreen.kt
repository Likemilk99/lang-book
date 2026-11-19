package app.lectura.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectura.LecturaApp
import app.lectura.data.DailyPlan
import app.lectura.data.LevelProgress
import app.lectura.data.PlanInput
import app.lectura.data.PlanStep
import app.lectura.data.ProgressService
import app.lectura.data.StepKind
import app.lectura.reminder.Reminder

/**
 * Главная: план «Сегодня» и входы в разделы (библиотека, грамматика).
 * [onRoute] получает маршрут шага плана или раздела: вкладку (`review`, `talk`) или экран (`reader/…`, `library`, …).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onRoute: (String) -> Unit, onProgress: () -> Unit) {
    val app = LecturaApp.instance
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val input by produceState<PlanInput?>(null, tick) { value = runCatching { DailyPlan.load(app) }.getOrNull() }
    val level by produceState<LevelProgress?>(null, tick) {
        value = runCatching { ProgressService.compute().current }.getOrNull()
    }
    val books by app.books.books.collectAsStateWithLifecycle()
    var showData by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var reminderOn by remember { mutableStateOf(app.prefs.reminderEnabled) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Lectura") },
                actions = {
                    IconButton(onClick = { showReminder = true }) {
                        Icon(if (reminderOn) Icons.Filled.Notifications else Icons.Filled.NotificationsOff, "Напоминание")
                    }
                    IconButton(onClick = { showData = true }) { Icon(Icons.Filled.Storage, "Данные и резервная копия") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatsCard(onClick = onProgress) }
            input?.let { inp -> item { TodayCard(DailyPlan.build(inp), onRoute) } }
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val book = input?.book
                    SectionTile(
                        emoji = "📚",
                        title = "Библиотека",
                        subtitle = book?.let { "${it.title}, гл. ${it.chapter + 1}" } ?: "Книг: ${books.size}",
                        modifier = Modifier.weight(1f),
                        onClick = { onRoute("library") },
                    )
                    SectionTile(
                        emoji = "📐",
                        title = "Грамматика",
                        subtitle = level?.let { "${it.level}: освоено ${it.grammarMastered} из ${it.grammarTotal} тем" } ?: " ",
                        modifier = Modifier.weight(1f),
                        onClick = { onRoute("grammar") },
                    )
                }
            }
        }
    }

    if (showData) DataDialog(onDismiss = { showData = false })
    if (showReminder) ReminderDialog(onDismiss = {
        showReminder = false
        reminderOn = app.prefs.reminderEnabled
    })
}

@Composable
private fun TodayCard(steps: List<PlanStep>, onRoute: (String) -> Unit) {
    val done = steps.count { it.done }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("План на сегодня", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (done == steps.size) "Всё сделано 🎉" else "$done из ${steps.size}", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(
                progress = { done.toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            steps.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                StepRow(s) { onRoute(s.route) }
            }
        }
    }
}

@Composable
private fun StepRow(s: PlanStep, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (s.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (s.done) "Сделано" else "Не сделано",
                tint = if (s.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${emoji(s.kind)} ${s.title}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    s.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (s.goal > 0) {
                Text(
                    "${s.progress.coerceAtMost(s.goal)}/${s.goal}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun emoji(k: StepKind) = when (k) {
    StepKind.REVIEW -> "🔁"
    StepKind.LISTEN -> "🎧"
    StepKind.SPEAK -> "🗣"
    StepKind.GRAMMAR -> "📐"
}

@Composable
private fun SectionTile(emoji: String, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxHeight()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Настройка напоминания: вкл/выкл и время. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = LecturaApp.instance.prefs
    var enabled by remember { mutableStateOf(prefs.reminderEnabled) }
    val time = rememberTimePickerState(prefs.reminderMinutes / 60, prefs.reminderMinutes % 60, is24Hour = true)
    val canNotify = remember { Reminder.canNotify(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Напоминание") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Напомнить, если сегодня ещё не занимались", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    TimePicker(state = time)
                    if (!canNotify) {
                        Text(
                            "Уведомления для Lectura выключены — напоминание не появится.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                            )
                        }) { Text("Открыть настройки уведомлений") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.reminderEnabled = enabled
                prefs.reminderMinutes = time.hour * 60 + time.minute
                Reminder.schedule(ctx, reset = true)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
