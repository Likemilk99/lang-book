package app.lectura

import app.lectura.model.Sentence
import app.lectura.model.Token
import org.junit.Assert.assertEquals
import org.junit.Test

class SpacingTest {
    private fun s(vararg t: String) = Sentence(tokens = t.map { Token(it) }).text

    @Test
    fun dialogueDashes() {
        assertEquals("—Hola —dijo él—. Ven.", s("—", "Hola", "—", "dijo", "él", "—", ".", "Ven", "."))
        assertEquals("—Es muy rara —dice Ana.", s("—", "Es", "muy", "rara", "—", "dice", "Ana", "."))
        assertEquals("—Sí —dijo— y se fue.", s("—", "Sí", "—", "dijo", "—", "y", "se", "fue", "."))
    }

    @Test
    fun questionsAndQuotes() {
        assertEquals("¿Qué tal? «Bien», dijo.", s("¿", "Qué", "tal", "?", "«", "Bien", "»", ",", "dijo", "."))
    }
}
