package app.lectura.review

import app.lectura.data.SavedWord

/**
 * Один подход повторения — не больше [SIZE] слов и фраз вместе, чтобы его можно было закончить.
 * Сначала те, что уже учили и дольше всего ждут (их легче всего забыть), но не меньше [MIN_NEW]
 * новых, чтобы новые слова тоже двигались; новые — в порядке добавления.
 */
object SessionBatch {
    const val SIZE = 20
    const val MIN_NEW = 5

    fun isNew(w: SavedWord) = w.reps == 0 && w.stage == 0

    fun pick(due: List<SavedWord>, size: Int = SIZE): List<SavedWord> {
        val (fresh, old) = due.partition(::isNew)
        val reviewed = old.sortedBy { it.due }
        val newest = fresh.sortedBy { it.createdAt }
        var nNew = minOf(newest.size, maxOf(MIN_NEW, size - reviewed.size))
        val nOld = minOf(reviewed.size, size - nNew)
        nNew = minOf(newest.size, size - nOld)
        return (reviewed.take(nOld) + newest.take(nNew)).shuffled()
    }
}
