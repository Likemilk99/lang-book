package app.lectura.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.data.Memory
import app.lectura.data.SavedWord

/**
 * «Память словаря»: сколько слов вы вспомните прямо сейчас, прогноз на месяц без повторений,
 * распределение по уровням и график по дням.
 */
@Composable
fun MemoryCard(words: List<SavedWord>, modifier: Modifier = Modifier) {
    if (words.isEmpty()) return
    val sum = remember(words) { Memory.summarize(words) }
    val stats by LecturaApp.instance.db.stats().recent(30).collectAsState(initial = emptyList())
    val history = stats.filter { it.memoryPct >= 0 }.sortedBy { it.day }

    val colors = levelColors()
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Память словаря", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${sum.nowPct}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "слов вспомните сейчас",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Text(
                "Через месяц без повторений: ~${sum.in30Pct}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Полоса по уровням
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                Memory.Level.entries.forEach { l ->
                    val n = sum.byLevel[l] ?: 0
                    if (n > 0) Box(Modifier.weight(n.toFloat()).height(10.dp).background(colors.getValue(l)))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Memory.Level.entries.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.getValue(l)))
                        Spacer(Modifier.width(4.dp))
                        Text("${l.label} ${sum.byLevel[l] ?: 0}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (history.size >= 2) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Память по дням (${history.size} дн.)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MemoryChart(history.map { it.memoryPct }, Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun levelColors(): Map<Memory.Level, Color> {
    val c = MaterialTheme.colorScheme
    return mapOf(
        Memory.Level.NEW to c.outlineVariant,
        Memory.Level.LEARNING to c.tertiary,
        Memory.Level.KNOWN to c.secondary,
        Memory.Level.STRONG to c.primary,
    )
}

@Composable
private fun MemoryChart(values: List<Int>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // ориентиры 50% и 100%
        listOf(0.5f, 1f).forEach { f ->
            val y = h - f * h
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val step = if (values.size > 1) w / (values.size - 1) else w
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = h - (v.coerceIn(0, 100) / 100f) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 4f))
        val last = values.last()
        drawCircle(line, radius = 6f, center = Offset((values.size - 1) * step, h - last / 100f * h))
    }
}
