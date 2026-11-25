package app.lectura

import app.lectura.data.LiveChat
import app.lectura.model.CHAT_LEVELS
import app.lectura.model.CHAT_TOPICS
import app.lectura.model.ChatReply
import app.lectura.model.ChatVoice
import app.lectura.model.VocabList
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveChatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun topicsAreConsistent() {
        assertEquals(CHAT_TOPICS.size, CHAT_TOPICS.map { it.id }.toSet().size)
        val themes = json.decodeFromString<VocabList>(File("src/main/assets/curriculum/vocab_A1.json").readText())
            .themes.map { it.id }.toSet()
        CHAT_TOPICS.forEach { t ->
            assertTrue(t.id, t.title.isNotBlank() && t.scene.isNotBlank() && t.role.isNotBlank())
            assertTrue(t.id, t.gender == "m" || t.gender == "f")
            assertTrue(t.id, t.goals.isNotEmpty())
            assertEquals(t.id, t.goals.size, t.goals.map { it.id }.toSet().size)
            // Ограничения сервера (pipeline/server.py clean_topic).
            assertTrue(t.id, t.scene.length <= 1500 && t.role.length <= 300 && t.goals.size <= 8)
            t.goals.forEach { g -> assertTrue(g.id, g.id.length <= 40 && g.ru.length <= 200) }
            t.vocab.forEach { v -> assertTrue("${t.id}: $v", v in themes) }
        }
    }

    @Test
    fun idMatchesServer() {
        val re = Regex("^[a-f0-9]{8,32}$") // pipeline/server.py CHAT_ID_RE
        repeat(20) { assertTrue(re.matches(LiveChat.newId())) }
    }

    @Test
    fun levelsMatchServer() {
        val py = File("../../pipeline/chat.py").readText()
        assertTrue(py.contains("LEVELS = (" + CHAT_LEVELS.joinToString(", ") { "\"$it\"" } + ")"))
    }

    @Test
    fun parsesServerReply() {
        val r = json.decodeFromString<ChatReply>(
            """{"chat":"abc","audio":true,"turn":2,"reply":"Vale. ¿Algo más?","sentences":2,"ru":"Хорошо. Что-то ещё?",
               "fix":{"es":"Quería un café.","ru":"вежливее"},"hints":[{"es":"nada más","ru":"больше ничего"}],
               "sample":{"es":"No, gracias.","ru":"Нет, спасибо."},"words":[],"goals_done":["drink"],"end":false,
               "firstMs":700,"totalMs":2500,"unknown":1}""",
        )
        assertEquals("Quería un café.", r.fix?.es)
        assertEquals(listOf("drink"), r.goals_done)
        assertEquals(2, r.sentences)
        val noFix = json.decodeFromString<ChatReply>("""{"reply":"Hola","fix":null}""")
        assertNull(noFix.fix)
    }

    @Test
    fun voiceKeys() {
        ChatVoice.entries.forEach { assertEquals(it, ChatVoice.of(it.key)) }
        assertEquals(ChatVoice.PHONE, ChatVoice.of("???"))
    }
}
