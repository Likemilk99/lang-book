package app.lectura.ui.grammar

import app.lectura.data.WordStatus
import app.lectura.model.GrammarExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GrammarLogicTest {
    private fun ex(type: String, lemmas: List<String> = emptyList(), answer: String = "es", sentence: String = "Él ___ ruso.") =
        GrammarExercise(type = type, sentence = sentence, answer = answer, options = listOf("es", "está"), lemmas = lemmas)

    @Test
    fun checkIgnoresCasePunctuationAndFlagsAccents() {
        assertEquals(Verdict.RIGHT, GrammarLogic.check(" Estoy! ", listOf("estoy")))
        assertEquals(Verdict.ACCENT, GrammarLogic.check("esta", listOf("está")))
        assertEquals(Verdict.WRONG, GrammarLogic.check("es", listOf("está")))
        assertEquals(Verdict.WRONG, GrammarLogic.check("  ", listOf("está")))
    }

    @Test
    fun inputAcceptsWholeSentence() {
        val e = ex("input", answer = "estoy", sentence = "Hoy yo ___ cansado.")
        assertEquals(Verdict.RIGHT, GrammarLogic.check("hoy yo estoy cansado", GrammarLogic.inputAccepted(e)))
        assertEquals("Hoy yo estoy cansado.", GrammarLogic.fullSentence(e))
    }

    @Test
    fun multipleGapsSplitAnswer() {
        assertEquals("Yo soy y tú eres.", GrammarLogic.filled("Yo ___ y tú ___.", "soy / eres"))
    }

    @Test
    fun orderTilesDropPunctuationAndCheck() {
        val e = ex("order", answer = "¿Dónde está Carmen?", sentence = "")
        val tiles = GrammarLogic.orderTiles(e.answer)
        assertEquals(listOf("Dónde", "está", "Carmen"), tiles)
        assertEquals(Verdict.RIGHT, GrammarLogic.checkOrder(tiles, e))
        assertEquals(Verdict.WRONG, GrammarLogic.checkOrder(tiles.reversed(), e))
        val sh = GrammarLogic.shuffledTiles(tiles, Random(1))
        assertTrue(sh != tiles && sh.sorted() == tiles.sorted())
    }

    @Test
    fun pickPrefersFamiliarAvoidsRecentAndMixesTypes() {
        val bank = List(12) { ex("choice", lemmas = listOf("casa")) } +
            List(12) { ex("input", lemmas = listOf("perro")) } +
            listOf(ex("transform", lemmas = listOf("casa", "piso")))
        val vocab = mapOf("casa" to WordStatus.LEARNING, "piso" to WordStatus.KNOWN)
        val recent = setOf("t#0", "t#1")
        val picked = GrammarLogic.pick("t", bank, vocab, recent, rnd = Random(7))
        assertEquals(10, picked.size)
        assertEquals(picked.size, picked.map { it.id }.distinct().size)
        assertTrue(picked.none { it.id in recent })
        assertTrue(picked.any { it.ex.type == "transform" })
        picked.windowed(3).forEach { w -> assertTrue(w.map { it.ex.type }.distinct().size > 1) }
    }

    @Test
    fun pickHandlesSmallAndBrokenBanks() {
        val bank = listOf(ex("choice"), GrammarExercise(type = "unknown", sentence = "x", answer = "y"), ex("order", answer = "Hola"))
        val picked = GrammarLogic.pick("t", bank, emptyMap(), emptySet())
        assertEquals(listOf("t#0"), picked.map { it.id })
        assertTrue(GrammarLogic.pick("t", emptyList(), emptyMap(), emptySet()).isEmpty())
    }
}
