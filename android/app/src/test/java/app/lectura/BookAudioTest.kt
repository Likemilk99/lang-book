package app.lectura

import app.lectura.data.BookAudio
import app.lectura.model.Book
import app.lectura.model.Token
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookAudioTest {

    /** Значения посчитаны pipeline/audio.py (token_hash) — хэши обязаны совпадать, иначе озвучка не найдётся. */
    @Test
    fun tokenHashMatchesPipeline() {
        val a = listOf("—", "¡", "Hola", ",", "Miša", "!").map { Token(it) }
        assertEquals("e4a0954d69", BookAudio.tokenHash(a))
        assertEquals("80b16eea2a", BookAudio.tokenHash(listOf("Iván", "entra", ".").map { Token(it) }))
    }

    @Test
    fun bundledAudioPacksPointToRelease() {
        val json = Json { ignoreUnknownKeys = true }
        File("src/main/assets/books").listFiles { f -> f.extension == "json" }.orEmpty().forEach { f ->
            val book = json.decodeFromString(Book.serializer(), f.readText())
            val pack = book.audio ?: return@forEach
            assertTrue(f.name, pack.url.startsWith("https://github.com/") && pack.url.endsWith("/${book.id}.zip"))
            assertTrue(f.name, pack.size > 0 && pack.version.isNotBlank())
        }
    }
}
