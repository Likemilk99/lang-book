package app.lectura.ui.grammar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.lectura.data.GrammarProgress
import app.lectura.data.MAX_STAGE
import app.lectura.review.Bad
import app.lectura.review.Good
import app.lectura.review.Warn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Статус темы для списка. */
enum class TopicStatus(val label: String) {
    NEW("Не начато"),
    LEARNING("Учу"),
    MASTERED("Освоено ✓"),
    DUE("Пора повторить"),
}

fun GrammarProgress?.isDue(now: Long): Boolean = this != null && sessions > 0 && due <= now

fun topicStatus(p: GrammarProgress?, now: Long): TopicStatus = when {
    p == null || p.sessions == 0 -> TopicStatus.NEW
    p.isDue(now) -> TopicStatus.DUE
    p.mastered -> TopicStatus.MASTERED
    else -> TopicStatus.LEARNING
}

/** Точность за всё время, % (null — ещё не практиковали). */
fun GrammarProgress?.accuracy(): Int? =
    if (this == null || attempts == 0) null else correct * 100 / attempts

@Composable
fun StatusChip(p: GrammarProgress?, now: Long) {
    val st = topicStatus(p, now)
    val color = when (st) {
        TopicStatus.NEW -> MaterialTheme.colorScheme.onSurfaceVariant
        TopicStatus.LEARNING -> MaterialTheme.colorScheme.primary
        TopicStatus.MASTERED -> Good
        TopicStatus.DUE -> Warn
    }
    val text = if (st == TopicStatus.LEARNING) {
        val s = (p?.stage ?: 0).coerceIn(0, GrammarProgress.MASTERED_STAGE)
        "Учу " + "●".repeat(s) + "○".repeat(GrammarProgress.MASTERED_STAGE - s)
    } else st.label
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Ступени 0..MAX_STAGE точками; закрашенные до ступени, освоено — зелёным. */
@Composable
fun StageDots(stage: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..MAX_STAGE) {
            val filled = i <= stage
            val c = when {
                !filled -> MaterialTheme.colorScheme.outlineVariant
                i >= GrammarProgress.MASTERED_STAGE && stage >= GrammarProgress.MASTERED_STAGE -> Good
                else -> MaterialTheme.colorScheme.primary
            }
            Box(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).background(c).padding(5.dp))
        }
    }
}

private fun dayStart(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** «сегодня», «завтра», «через 3 дня (21 ноября)». */
fun reviewDateText(due: Long, now: Long = System.currentTimeMillis()): String {
    if (due <= now) return "сегодня"
    val days = ((dayStart(due) - dayStart(now)) / (24L * 60 * 60 * 1000)).toInt()
    val date = SimpleDateFormat("d MMMM", Locale.forLanguageTag("ru")).format(Date(due))
    return when (days) {
        0 -> "сегодня"
        1 -> "завтра"
        else -> "через ${days} ${plural(days, "день", "дня", "дней")} ($date)"
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

private val BOLD = Regex("\\*\\*(.+?)\\*\\*")

/** Текст с **жирным**. */
fun richText(s: String, boldColor: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in BOLD.findAll(s)) {
        append(s.substring(last, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = boldColor)) { append(m.groupValues[1]) }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

/** Абзацы через пустую строку; строки «- …»/«• …» — пунктами списка. */
@Composable
fun RichParagraphs(text: String, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val paragraphs = text.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        paragraphs.forEach { p ->
            val lines = p.lines().joinToString("\n") { line ->
                val t = line.trimStart()
                if (t.startsWith("- ") || t.startsWith("* ")) "•  " + t.drop(2) else line
            }
            Text(richText(lines), style = style)
        }
    }
}

/**
 * Таблица-сетка: первая строка — заголовок (жирный, с фоном), ширина столбца — по самой широкой
 * ячейке (не больше 240dp, длинное переносится). Широкая таблица прокручивается по горизонтали.
 */
@Composable
fun GrammarTable(rows: List<List<String>>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    if (cols == 0) return
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.secondaryContainer
    val stripe = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, outline, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        Layout(
            content = {
                rows.forEachIndexed { r, row ->
                    for (c in 0 until cols) {
                        val bg = when {
                            r == 0 -> headerBg
                            r % 2 == 0 -> stripe
                            else -> Color.Transparent
                        }
                        Box(Modifier.background(bg).border(0.5.dp, outline).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                richText(row.getOrElse(c) { "" }),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (r == 0 || (c == 0 && rows.first().firstOrNull().isNullOrBlank())) FontWeight.Bold else null,
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val cap = 240.dp.roundToPx()
            val widths = IntArray(cols)
            for (r in rows.indices) for (c in 0 until cols) {
                val w = measurables[r * cols + c].maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(cap)
                if (w > widths[c]) widths[c] = w
            }
            val heights = IntArray(rows.size)
            for (r in rows.indices) for (c in 0 until cols) {
                val h = measurables[r * cols + c].minIntrinsicHeight(widths[c])
                if (h > heights[r]) heights[r] = h
            }
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixed(widths[i % cols], heights[i / cols]))
            }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (c in 0 until cols) {
                        placeables[r * cols + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}

/** Цвет для процента результата. */
fun pctColor(pct: Int): Color = when {
    pct >= 80 -> Good
    pct >= 60 -> Warn
    else -> Bad
}
