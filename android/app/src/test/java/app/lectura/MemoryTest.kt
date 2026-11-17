package app.lectura

import app.lectura.data.Memory
import app.lectura.data.SavedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun word(id: Long, interval: Int, reps: Int, lastReviewedDaysAgo: Int, dueInDays: Int, lapses: Int = 0) = SavedWord(
        id = id, key = "w$id", word = "w$id", lemma = "w$id", ru = "", grammar = "", sentence = "", sentenceRu = "",
        bookId = "b", createdAt = now - 100 * day, due = now + dueInDays * day, intervalDays = interval, reps = reps,
        lapses = lapses, lastReviewed = now - lastReviewedDaysAgo * day,
    )

    @Test
    fun recallIsNinetyPercentAfterStabilityDays() {
        val w = word(1, interval = 10, reps = 3, lastReviewedDaysAgo = 10, dueInDays = 0)
        assertEquals(0.9, Memory.recall(w, now), 0.001)
        val fresh = word(2, interval = 10, reps = 3, lastReviewedDaysAgo = 0, dueInDays = 10)
        assertEquals(1.0, Memory.recall(fresh, now), 0.001)
    }

    @Test
    fun strongerWordsForgetSlower() {
        val weak = word(1, interval = 1, reps = 1, lastReviewedDaysAgo = 5, dueInDays = 1)
        val strong = word(2, interval = 30, reps = 5, lastReviewedDaysAgo = 5, dueInDays = 25)
        assertTrue(Memory.recall(strong, now) > Memory.recall(weak, now))
    }

    @Test
    fun practiceSkipsDueWordsAndPrefersForgotten() {
        val due = word(1, interval = 3, reps = 2, lastReviewedDaysAgo = 3, dueInDays = -1)
        val forgotten = word(2, interval = 2, reps = 2, lastReviewedDaysAgo = 1, dueInDays = 1, lapses = 3)
        val solid = word(3, interval = 60, reps = 6, lastReviewedDaysAgo = 1, dueInDays = 59)
        val picked = Memory.pickForPractice(listOf(due, forgotten, solid), n = 1, now = now)
        assertEquals(listOf(forgotten), picked)
        assertTrue(Memory.pickForPractice(listOf(due), n = 5, now = now).isEmpty())
    }
}
