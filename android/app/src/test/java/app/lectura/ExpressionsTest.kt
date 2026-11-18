package app.lectura

import app.lectura.data.Expression
import app.lectura.data.ExpressionIndex
import app.lectura.data.WordStatus
import app.lectura.model.Sentence
import app.lectura.model.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionsTest {
    private val idx = ExpressionIndex(
        listOf(
            Expression("otra vez", "снова", "A1"),
            Expression("darse cuenta", "заметить, понять", "B1"),
            Expression("fin de semana", "выходные", "A1"),
            Expression("de vez en cuando", "время от времени", "A2"),
            Expression("ni… ni", "ни… ни", "A2"),
            Expression("ir a", "собираться", "A1"),
            Expression("no sé", "не знаю", "A1"),
            Expression("es más", "более того", "B1"),
        ),
    )

    private fun s(vararg toks: Pair<String, String?>) = Sentence(tokens = toks.map { Token(it.first, it.second) })

    @Test
    fun findsExpressionByFormIgnoringCase() {
        val m = idx.find(s("Otra" to "otro", "vez" to "vez", "me" to "yo", "caí" to "caer", "." to null))
        assertEquals(1, m.size)
        assertEquals(0..1, m[0].range)
        assertEquals("снова", m[0].expr.ru)
    }

    @Test
    fun findsReflexiveVerbByLemma() {
        val m = idx.find(s("Ana" to "Ana", "se" to "se", "dio" to "dar", "cuenta" to "cuenta", "." to null))
        assertEquals(listOf(2..3), m.map { it.range })
    }

    @Test
    fun prefersLongerAndRequiresContiguous() {
        assertEquals(listOf(1..4), idx.find(s("Viene" to "venir", "de" to "de", "vez" to "vez", "en" to "en", "cuando" to "cuando")).map { it.range })
        assertTrue(idx.find(s("otra" to "otro", "," to null, "vez" to "vez")).isEmpty())
    }

    @Test
    fun lookupBySelectedText() {
        assertNotNull(idx.lookup("Otra vez,"))
        assertEquals(6, idx.size) // «ni… ni» и «es más» не ищем в тексте
    }

    @Test
    fun verbWithPrepositionNeedsInfinitive() {
        assertEquals(listOf(0..1), idx.find(s("Va" to "ir", "a" to "a", "comprar" to "comprar", "pan" to "pan")).map { it.range })
        assertTrue(idx.find(s("Va" to "ir", "a" to "a", "la" to "el", "tienda" to "tienda")).isEmpty())
        assertTrue(idx.find(s("Voy" to "ir", "a" to "a", "Javier" to "Javier")).isEmpty())
    }

    @Test
    fun accentsMatter() {
        assertTrue(idx.find(s("No" to "no", "se" to "se", "encuentra" to "encontrar")).isEmpty())
        assertEquals(1, idx.find(s("No" to "no", "sé" to "saber", "." to null)).size)
        assertTrue(idx.find(s("Es" to "ser", "más" to "más", "fuerte" to "fuerte")).isEmpty())
    }

    private val withWords = ExpressionIndex(
        listOf(Expression("otra vez", "снова", "A1"), Expression("a veces", "иногда", "A1"), Expression("tal vez", "возможно", "A2"), Expression("darse cuenta", "понять", "B2")),
        listOf(Expression("otro", "другой", "A1"), Expression("vez", "раз", "A1"), Expression("dar", "давать", "A1")),
    )

    @Test
    fun componentWordsAndOtherExpressions() {
        assertEquals("другой", withWords.word(Token("otra", "otro"))?.ru)
        assertEquals("A1", withWords.word(Token("vez", "vez"))?.level)
        assertEquals(null, withWords.word(Token("repente", "repente")))
        val otraVez = withWords.lookup("otra vez")!!
        assertEquals(listOf("a veces", "tal vez"), withWords.containing(Token("vez", "vez"), otraVez).map { it.es })
        assertEquals(listOf("darse cuenta"), withWords.containing(Token("di", "dar"), otraVez).map { it.es })
    }

    @Test
    fun savedPhrasesAreFoundInText() {
        val saved = mapOf("otra vez" to WordStatus.LEARNING, "darse cuenta" to WordStatus.KNOWN, "buenos días" to WordStatus.LEARNING)
        val sent = s("Otra" to "otro", "vez" to "vez", "me" to "yo", "di" to "dar", "cuenta" to "cuenta", "." to null)
        assertEquals(listOf(0..1 to WordStatus.LEARNING, 3..4 to WordStatus.KNOWN), withWords.savedRanges(sent, saved))
        // своя фраза не из словаря — по точным словам
        assertEquals(listOf(1..2 to WordStatus.LEARNING), withWords.savedRanges(s("¡" to null, "Buenos" to "bueno", "días" to "día", "!" to null), saved))
        assertTrue(withWords.savedRanges(s("otra" to "otro", "casa" to "casa"), saved).isEmpty())
        assertEquals("otra vez", ExpressionIndex.phraseKey("Otra vez,"))
    }
}
