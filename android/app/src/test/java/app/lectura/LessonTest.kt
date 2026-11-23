package app.lectura

import app.lectura.playback.LessonItem
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

    @Test
    fun playlistKeepsTopicOfEachPhrase() {
        val list = LessonPlayer.playlist(
            listOf(
                "В кафе" to listOf(LessonItem("Un café.", "Кофе."), LessonItem("La cuenta.", "Счёт.")),
                "Аптека" to listOf(LessonItem("Me duele.", "Болит.")),
            ),
        )
        assertEquals(listOf("В кафе", "В кафе", "Аптека"), list.map { it.topic })
        assertEquals(listOf("Un café.", "La cuenta.", "Me duele."), list.map { it.es })
    }

    @Test
    fun loopWrapsAroundOtherwiseStopsAtEdges() {
        assertEquals(0, LessonPlayer.step(2, +1, 3, loop = true))
        assertEquals(2, LessonPlayer.step(0, -1, 3, loop = true))
        assertEquals(2, LessonPlayer.step(2, +1, 3, loop = false))
        assertEquals(0, LessonPlayer.step(0, -1, 3, loop = false))
        assertEquals(1, LessonPlayer.step(1, 0, 3, loop = true))
    }
}
