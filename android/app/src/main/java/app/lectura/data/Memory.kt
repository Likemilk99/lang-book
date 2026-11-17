package app.lectura.data

import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * Оценка памяти по словам. Кривая забывания как в FSRS: R(t) = (1 + t / (9·S))^-1,
 * где S — «стабильность» в днях (через S дней вероятность вспомнить ≈ 90%).
 * S берём из интервала SM-2: если слово стабильно держится N дней — S ≈ N.
 */
object Memory {
    private const val DAY = 24.0 * 60 * 60 * 1000

    enum class Level(val label: String) {
        NEW("Новые"),
        LEARNING("Учу"),
        KNOWN("Знаю"),
        STRONG("Крепко"),
    }

    fun level(w: SavedWord): Level = when {
        w.reps == 0 -> Level.NEW
        w.intervalDays < 7 -> Level.LEARNING
        w.intervalDays < 21 -> Level.KNOWN
        else -> Level.STRONG
    }

    private fun stabilityDays(w: SavedWord): Double = when {
        w.reps == 0 -> 0.25 // видели в книге, но ещё ни разу не вспоминали
        else -> max(w.intervalDays.toDouble(), 0.5)
    }

    private fun lastSeen(w: SavedWord): Long = when {
        w.lastReviewed > 0 -> w.lastReviewed
        w.reps > 0 -> w.due - (w.intervalDays * DAY).toLong()
        else -> w.createdAt
    }

    /** Вероятность вспомнить слово в момент [at] (0..1). */
    fun recall(w: SavedWord, at: Long = System.currentTimeMillis()): Double {
        val t = max(0.0, (at - lastSeen(w)) / DAY)
        return (1 + t / (9 * stabilityDays(w))).pow(-1.0)
    }

    data class Summary(
        val total: Int,
        /** Средняя вероятность вспомнить сейчас, % */
        val nowPct: Int,
        /** То же через 30 дней, если совсем не повторять, % */
        val in30Pct: Int,
        val byLevel: Map<Level, Int>,
    )

    fun summarize(words: List<SavedWord>, now: Long = System.currentTimeMillis()): Summary {
        if (words.isEmpty()) return Summary(0, 0, 0, Level.entries.associateWith { 0 })
        val in30 = now + (30 * DAY).toLong()
        return Summary(
            total = words.size,
            nowPct = (words.sumOf { recall(it, now) } * 100 / words.size).toInt(),
            in30Pct = (words.sumOf { recall(it, in30) } * 100 / words.size).toInt(),
            byLevel = Level.entries.associateWith { l -> words.count { level(it) == l } },
        )
    }

    /**
     * Слова для дополнительной практики: сначала те, что вы скорее всего уже подзабыли,
     * и «трудные» (много ошибок, низкая ступень). Слова, которые пора повторять по плану, сюда не берём —
     * они идут в обычное повторение. Только что отработанные слова отодвигаются в конец.
     */
    fun pickForPractice(words: List<SavedWord>, n: Int, now: Long = System.currentTimeMillis()): List<SavedWord> =
        words.filter { it.due > now }
            .map { w ->
                var score = (1 - recall(w, now)) +
                    0.15 * w.lapses.coerceAtMost(5) +
                    0.1 * (MAX_STAGE - w.stage) / MAX_STAGE +
                    Random.nextDouble(0.0, 0.15)
                if (now - w.lastReviewed < 20 * 60 * 1000) score *= 0.3
                w to score
            }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first }
            .shuffled()
}
