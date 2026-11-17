package app.lectura.data

import kotlin.math.max
import kotlin.math.roundToInt

/** Упрощённый SM-2: оценки 0 = Снова, 1 = Трудно, 2 = Хорошо, 3 = Легко. */
object Srs {
    const val AGAIN = 0
    const val HARD = 1
    const val GOOD = 2
    const val EASY = 3

    private const val DAY = 24L * 60 * 60 * 1000
    private const val MINUTE = 60L * 1000

    fun schedule(w: SavedWord, grade: Int, now: Long = System.currentTimeMillis()): SavedWord {
        val (interval, ease) = next(w, grade)
        return when (grade) {
            AGAIN -> w.copy(
                intervalDays = 0, ease = ease, reps = 0, lapses = w.lapses + 1,
                due = now + MINUTE,
            )
            else -> w.copy(
                intervalDays = interval, ease = ease, reps = w.reps + 1,
                due = now + interval * DAY,
            )
        }
    }

    /** Подпись для кнопки: через сколько слово вернётся. */
    fun label(w: SavedWord, grade: Int): String {
        if (grade == AGAIN) return "<1 мин"
        val d = next(w, grade).first
        return when {
            d < 30 -> "$d дн"
            d < 365 -> "${(d / 30.0).roundToInt()} мес"
            else -> "${"%.1f".format(d / 365.0)} г"
        }
    }

    private fun next(w: SavedWord, grade: Int): Pair<Int, Double> {
        var ease = w.ease
        val interval = when (grade) {
            AGAIN -> {
                ease -= 0.2; 0
            }
            HARD -> {
                ease -= 0.15
                if (w.reps == 0) 1 else max(w.intervalDays + 1, (w.intervalDays * 1.2).roundToInt())
            }
            GOOD -> when (w.reps) {
                0 -> 1
                1 -> 3
                else -> max(w.intervalDays + 1, (w.intervalDays * w.ease).roundToInt())
            }
            else -> {
                ease += 0.15
                if (w.reps == 0) 4 else max(w.intervalDays + 2, (w.intervalDays * w.ease * 1.3).roundToInt())
            }
        }
        return interval to ease.coerceIn(1.3, 3.0)
    }
}
