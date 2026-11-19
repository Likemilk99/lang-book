package app.lectura

import app.lectura.playback.LessonPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonTest {
    @Test
    fun pauseGrowsWithPhraseAndStaysInBounds() {
        val short = LessonPlayer.pauseFor("Hola.", slow = false)
        val long = LessonPlayer.pauseFor("¿Me puede decir dónde está la parada del autobús número doce?", slow = false)
        assertEquals(2000L, short)
        assertTrue(long > short)
        assertTrue(LessonPlayer.pauseFor("x".repeat(500), slow = true) <= 8000L)
        assertTrue(LessonPlayer.pauseFor("Quería un café con leche.", slow = true) > LessonPlayer.pauseFor("Quería un café con leche.", slow = false))
    }
}
