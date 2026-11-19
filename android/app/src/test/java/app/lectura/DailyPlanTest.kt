package app.lectura

import app.lectura.data.CurrentBook
import app.lectura.data.DailyPlan
import app.lectura.data.DailyStats
import app.lectura.data.GrammarNext
import app.lectura.data.GrammarProgress
import app.lectura.data.PlanInput
import app.lectura.data.StepKind
import app.lectura.model.GrammarLevel
import app.lectura.model.GrammarTopic
import app.lectura.reminder.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class DailyPlanTest {
    private val book = CurrentBook(
        id = "a1-02", title = "Iván llega a Madrid", chapter = 3, chapterTitle = "El piso",
        speakChapter = 2, speakChapterTitle = "En el bar", speakHasDialogue = true,
    )

    private fun input(
        today: DailyStats = DailyStats("2026-11-19"),
        due: Int = 0,
        book: CurrentBook? = this.book,
        grammar: GrammarNext? = GrammarNext("a1-ser-estar", "Ser и estar", "A1", review = false),
        grammarDone: Boolean = false,
    ) = PlanInput(today, due, book, grammar, grammarDone)

    private fun step(kind: StepKind, inp: PlanInput) = DailyPlan.build(inp).first { it.kind == kind }

    @Test
    fun planHasFourStepsInOrder() {
        assertEquals(
            listOf(StepKind.REVIEW, StepKind.LISTEN, StepKind.SPEAK, StepKind.GRAMMAR),
            DailyPlan.build(input()).map { it.kind },
        )
    }

    @Test
    fun reviewDoneWhenNothingDueOrBatchFinished() {
        assertTrue(step(StepKind.REVIEW, input(due = 0)).done)
        assertFalse(step(StepKind.REVIEW, input(due = 7)).done)
        assertTrue(step(StepKind.REVIEW, input(due = 7, today = DailyStats("d", reviews = 20))).done)
        // Нечего повторять и ничего не делали — без счётчика.
        assertEquals(0, step(StepKind.REVIEW, input(due = 0)).goal)
        assertEquals("review", step(StepKind.REVIEW, input(due = 3)).route)
    }

    @Test
    fun listenAndSpeakFollowCurrentBook() {
        val listen = step(StepKind.LISTEN, input())
        assertEquals("reader/a1-02", listen.route)
        assertTrue(listen.detail.contains("гл. 4"))
        assertFalse(listen.done)
        assertTrue(step(StepKind.LISTEN, input(today = DailyStats("d", sentencesRead = 30))).done)

        assertEquals("speak/a1-02/2/role", step(StepKind.SPEAK, input()).route)
        val noDialogue = book.copy(speakHasDialogue = false)
        assertEquals("speak/a1-02/2/shadow", step(StepKind.SPEAK, input(book = noDialogue)).route)
        assertTrue(step(StepKind.SPEAK, input(today = DailyStats("d", speaking = 10))).done)
    }

    @Test
    fun withoutBookStepsLeadToLibraryAndPhrasebook() {
        assertEquals("library", step(StepKind.LISTEN, input(book = null)).route)
        assertEquals("phrasebook/A0", step(StepKind.SPEAK, input(book = null)).route)
    }

    @Test
    fun grammarStep() {
        assertEquals("grammar/a1-ser-estar", step(StepKind.GRAMMAR, input()).route)
        assertFalse(step(StepKind.GRAMMAR, input()).done)
        assertTrue(step(StepKind.GRAMMAR, input(grammarDone = true)).done)
        assertTrue(step(StepKind.GRAMMAR, input(grammar = null)).done)
    }

    private fun topic(id: String) = GrammarTopic(id = id, title = id, summary = "", sections = emptyList())

    @Test
    fun nextGrammarPrefersDueReviewThenFirstNewTopic() {
        val levels = mapOf(
            "A1" to GrammarLevel("A1", listOf(topic("a"), topic("b"), topic("c"))),
            "A2" to GrammarLevel("A2", listOf(topic("d"))),
        )
        val now = 1_000_000L
        // Ничего не начато — первая тема A1.
        assertEquals("a", DailyPlan.nextGrammar(levels, emptyMap(), now)?.id)
        // «a» начата и ещё не пора — следующая новая «b».
        val started = mapOf("a" to GrammarProgress("a", "A1", sessions = 1, due = now + 1))
        assertEquals(GrammarNext("b", "b", "A1", review = false), DailyPlan.nextGrammar(levels, started, now))
        // «c» пора повторить раньше «a» — повторение важнее новой темы.
        val due = mapOf(
            "a" to GrammarProgress("a", "A1", sessions = 1, due = now - 10),
            "c" to GrammarProgress("c", "A1", sessions = 2, due = now - 100),
        )
        assertEquals(GrammarNext("c", "c", "A1", review = true), DailyPlan.nextGrammar(levels, due, now))
        // Всё начато и ничего не пора — тем нет.
        val all = listOf("a", "b", "c", "d").associateWith { GrammarProgress(it, "A1", sessions = 1, due = now + 1) }
        assertNull(DailyPlan.nextGrammar(levels, all, now))
    }

    @Test
    fun reminderDelay() {
        val evening = 20 * 60
        assertEquals(Duration.ofHours(2), Reminder.delayUntil(evening, LocalDateTime.of(2026, 11, 19, 18, 0)))
        // Время уже прошло (и ровно сейчас) — завтра.
        assertEquals(Duration.ofHours(23), Reminder.delayUntil(evening, LocalDateTime.of(2026, 11, 19, 21, 0)))
        assertEquals(Duration.ofDays(1), Reminder.delayUntil(evening, LocalDateTime.of(2026, 11, 19, 20, 0)))
        assertEquals("9:05", Reminder.label(9 * 60 + 5))
    }

    @Test
    fun reminderOnlyWhenNoActivityToday() {
        assertNull(Reminder.message(DailyStats("d", reviews = 1), due = 5, streak = 3))
        val m = Reminder.message(DailyStats("d"), due = 5, streak = 3)
        assertNotNull(m)
        assertTrue(m!!.first.contains("3"))
        assertTrue(m.second.contains("5"))
        assertNotNull(Reminder.message(null, due = 0, streak = 0))
    }
}
