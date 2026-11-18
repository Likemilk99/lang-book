package app.lectura

import app.lectura.model.GrammarExercise
import app.lectura.review.TextMatch
import app.lectura.speech.SpanishNumbers
import app.lectura.speech.SpeechMatch
import app.lectura.ui.grammar.GrammarLogic
import app.lectura.ui.grammar.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {
    @Test
    fun numbersInWords() {
        assertEquals("dieciséis", SpanishNumbers.words(16))
        assertEquals("treinta y cinco", SpanishNumbers.words(35))
        assertEquals("ciento veintidós", SpanishNumbers.words(122))
        assertEquals("dos mil veinticuatro", SpanishNumbers.words(2024))
        assertEquals(listOf("uno", "un", "una"), SpanishNumbers.forms("1"))
        assertEquals(listOf("veintiuno", "veintiún", "veintiuna"), SpanishNumbers.forms("21"))
        assertEquals(listOf("once"), SpanishNumbers.forms("11"))
    }

    @Test
    fun typedDigitsMatchWords() {
        val acc = listOf("Tengo tres hermanos.")
        assertEquals(Verdict.RIGHT, GrammarLogic.check("tengo 3 hermanos", acc))
        assertEquals(Verdict.RIGHT, GrammarLogic.check("Tengo una hermana", listOf("tengo 1 hermana")))
        assertEquals(Verdict.RIGHT, GrammarLogic.check("tengo 1 hermana", listOf("Tengo una hermana.")))
        assertEquals(Verdict.RIGHT, GrammarLogic.check("Son las 2", listOf("son las dos")))
        assertEquals(Verdict.RIGHT, GrammarLogic.check("cuesta 1.000 euros", listOf("Cuesta mil euros.")))
        assertEquals(Verdict.ACCENT, GrammarLogic.check("tengo veintidos años", listOf("Tengo 22 años")))
        assertEquals(Verdict.WRONG, GrammarLogic.check("tengo 4 hermanos", acc))
    }

    @Test
    fun caseNeverMatters() {
        assertEquals(Verdict.RIGHT, GrammarLogic.check("me llamo ana", listOf("Me llamo Ana.")))
        assertEquals(Verdict.RIGHT, GrammarLogic.check("ÉL ES RUSO", listOf("Él es ruso.")))
        assertTrue(TextMatch.same("madrid", "Madrid"))
    }

    @Test
    fun wordDiffMarksWrongWords() {
        val d = TextMatch.wordDiff("Yo soy cansado", listOf("Yo estoy cansado."))
        assertEquals(listOf("Yo" to true, "soy" to false, "cansado" to true), d)
        assertEquals(listOf("3" to true), TextMatch.wordDiff("3", listOf("tres")))
    }

    @Test
    fun speechNumbersStillMatch() {
        assertEquals(1f, SpeechMatch.compare("Tengo dieciséis años.", listOf("tengo 16 años")).score)
    }

    @Test
    fun promptSpeechPausesAtGapAndDropsHints() {
        val e = GrammarExercise(type = "input", sentence = "Ayer (nosotros) ___ al mercado.", answer = "fuimos")
        assertEquals(listOf("Ayer ", " al mercado."), GrammarLogic.promptSpeech(e))
        assertEquals(emptyList<String>(), GrammarLogic.promptSpeech(e.copy(type = "order")))
    }
}
