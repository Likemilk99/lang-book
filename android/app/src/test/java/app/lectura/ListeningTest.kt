package app.lectura

import app.lectura.data.ListenItem
import app.lectura.data.Listening
import app.lectura.speech.SpeechMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ListeningTest {
    private val pool = (1..12).map { ListenItem("Frase número $it.", "Фраза $it", null) } +
        ListenItem("Frase número 1.", "Фраза 1", null) // повтор не должен попасть дважды

    @Test
    fun questionsHaveFourDistinctOptionsWithTheRightOne() {
        val qs = Listening.questions(pool, 10, Random(7))
        assertEquals(10, qs.size)
        assertEquals(10, qs.map { it.item.es }.toSet().size)
        qs.forEach { q ->
            assertEquals(4, q.options.size)
            assertEquals(4, q.options.toSet().size)
            assertEquals(q.item.ru, q.options[q.answer])
        }
    }

    @Test
    fun distractorsComeFromTheSameSituationFirst() {
        val cafe = (1..5).map { ListenItem("Café $it.", "Кафе $it", null, group = "cafe") }
        val bank = (1..5).map { ListenItem("Banco $it.", "Банк $it", null, group = "bank") }
        Listening.questions(cafe + bank, 10, Random(3)).forEach { q ->
            val group = if (q.item.group == "cafe") "Кафе" else "Банк"
            assertTrue(q.options.all { it.startsWith(group) })
        }
    }

    @Test
    fun smallPoolStillWorks() {
        val qs = Listening.questions(pool.take(2), 10, Random(1))
        assertEquals(2, qs.size)
        qs.forEach { assertEquals(it.item.ru, it.options[it.answer]) }
    }

    /** Диктант: видно, какие слова эталона пропущены; ударения и регистр не важны. */
    @Test
    fun dictationShowsMissedWords() {
        val r = SpeechMatch.compare("¿Me pone un café con leche?", listOf("me pone cafe con leche"))
        assertFalse(r.words.first { it.word.contains("un") }.matched)
        assertTrue(r.words.first { it.word.contains("café") }.matched)
        assertTrue(r.passed) // 5 из 6 слов
        assertFalse(SpeechMatch.compare("¿Me pone un café con leche?", listOf("me pone")).passed)
    }
}
