package app.lectura.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.data.DailyStats
import app.lectura.data.today
import java.time.LocalDate

/** Сегодняшняя активность + серия дней подряд. */
@Composable
fun StatsCard(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val stats by LecturaApp.instance.db.stats().recent(400).collectAsState(initial = emptyList())
    val t = stats.firstOrNull { it.day == today() } ?: DailyStats(today())
    val streak = streak(stats)
    val level by androidx.compose.runtime.produceState<app.lectura.data.LevelProgress?>(null, stats) {
        value = runCatching { app.lectura.data.ProgressService.compute().current }.getOrNull()
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Сегодня", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Упражнений: ${t.reviews}" + if (t.reviews > 0) " · верно ${t.correct * 100 / t.reviews}%" else "")
                Text("Новых слов: ${t.newWords} · прослушано предложений: ${t.sentencesRead}")
                if (t.speaking > 0) Text("Сказано вслух: ${t.speaking}")
                level?.let {
                    Text(
                        "Уровень ${it.level}: ${it.percent}% · мой прогресс ›",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔥 $streak", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(daysWord(streak), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun DailyStats.active() = reviews > 0 || sentencesRead > 0 || newWords > 0 || speaking > 0

/** Дней подряд с активностью; сегодняшний день ещё может быть пустым — серия от этого не рвётся. */
fun streak(stats: List<DailyStats>): Int {
    val active = stats.filter { it.active() }.map { it.day }.toSet()
    var day = LocalDate.now()
    if (day.toString() !in active) day = day.minusDays(1)
    var n = 0
    while (day.toString() in active) {
        n++
        day = day.minusDays(1)
    }
    return n
}

private fun daysWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "день подряд"
        m10 in 2..4 && m100 !in 12..14 -> "дня подряд"
        else -> "дней подряд"
    }
}
