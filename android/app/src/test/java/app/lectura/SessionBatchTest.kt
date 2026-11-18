package app.lectura

import app.lectura.data.SavedWord
import app.lectura.review.SessionBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBatchTest {
    private fun w(id: Long, reviewed: Boolean, due: Long = id) = SavedWord(
        id = id, key = "w$id", word = "w$id", lemma = "w$id", ru = "", grammar = "", sentence = "", sentenceRu = "",
        bookId = "", createdAt = id, due = due, reps = if (reviewed) 1 else 0, stage = if (reviewed) 1 else 0,
    )

    @Test
    fun atMostTwentyAndOldestNewFirst() {
        val due = (1L..130L).map { w(it, reviewed = false) }
        val b = SessionBatch.pick(due.shuffled())
        assertEquals(20, b.size)
        assertEquals((1L..20L).toSet(), b.map { it.id }.toSet())
    }

    @Test
    fun reviewedFirstButSomeNew() {
        val due = (1L..30L).map { w(it, reviewed = true, due = 100 - it) } + (31L..60L).map { w(it, reviewed = false) }
        val b = SessionBatch.pick(due)
        assertEquals(20, b.size)
        assertEquals(SessionBatch.MIN_NEW, b.count { SessionBatch.isNew(it) })
        // самые «просроченные» из уже изученных — у них самый ранний due (id 30, 29, …)
        assertTrue(b.filter { !SessionBatch.isNew(it) }.all { it.id > 15 })
    }

    @Test
    fun smallListTakenWhole() {
        val due = listOf(w(1, true), w(2, false), w(3, false))
        assertEquals(3, SessionBatch.pick(due).size)
        assertEquals(20, SessionBatch.pick((1L..8L).map { w(it, true) } + (9L..40L).map { w(it, false) }).size)
    }
}
