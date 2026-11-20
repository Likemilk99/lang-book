package app.lectura

import app.lectura.speech.SpeechMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechMatchTest {
    @Test
    fun exactAndCaseAccentsPunctuation() {
        assertEquals(1f, SpeechMatch.compare("¿Qué tal?", listOf("que tal")).score)
        assertTrue(SpeechMatch.compare("Quería un café, por favor.", listOf("queria un cafe por favor")).passed)
    }

    @Test
    fun homophonesCountAsCorrect() {
        // Немая h, b = v, ll = y: распознаватель мог выбрать другое написание того же звучания.
        assertTrue(SpeechMatch.compare("Hola", listOf("ola")).passed)
        assertTrue(SpeechMatch.compare("Un vaso de agua", listOf("un baso de agua")).passed)
        assertTrue(SpeechMatch.compare("Me llamo Iván", listOf("me yamo Iván")).passed)
        assertEquals("chico", SpeechMatch.sound("chico"))
    }

    @Test
    fun differentWordsStillFail() {
        assertFalse(SpeechMatch.compare("Quiero un café", listOf("quiero un té")).passed)
        assertFalse(SpeechMatch.compare("pero", listOf("perro")).passed)
    }

    @Test
    fun bestOfAlternatives() {
        val r = SpeechMatch.compare("Tengo frío", listOf("tengo río", "tengo frío"))
        assertTrue(r.passed)
        assertEquals("tengo frío", r.heard)
    }
}
