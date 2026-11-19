package app.lectura

import app.lectura.model.ABOUT_QUESTIONS
import app.lectura.model.InboxEntry
import app.lectura.model.InboxFile
import app.lectura.model.PersonalFile
import app.lectura.model.PersonalResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Мои фразы»: контракт файлов с pipeline/personal.py и привязка переводов к записям. */
class PersonalTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aboutQuestionsAreUnique() {
        assertEquals(ABOUT_QUESTIONS.size, ABOUT_QUESTIONS.map { it.id }.toSet().size)
        ABOUT_QUESTIONS.forEach { assertTrue(it.es, it.es.endsWith("?") || it.es.endsWith(".")) }
    }

    /** personal.py читает именно эти поля inbox.json. */
    @Test
    fun inboxFileHasFieldsThePipelineReads() {
        val text = json.encodeToString(InboxFile.serializer(), InboxFile(listOf(InboxEntry(7, "about", "name", "¿Cómo te llamas?", "Меня зовут Олег"))))
        val item = json.parseToJsonElement(text).jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("id", "kind", "ref", "question", "ru"), item.keys)
    }

    @Test
    fun personalFileFromPipelineDecodes() {
        val text = """{"items":[{"inboxId":3,"kind":"phrase","ref":"","ru":"течёт кран",
            "phrases":[{"id":"me-3-1","es":"El grifo gotea.","ru":"Кран течёт.","note":"x"}]}]}"""
        val r = json.decodeFromString(PersonalFile.serializer(), text).items.single()
        assertEquals("me-3-1", r.phrases.single().id)
        assertTrue(r.matches(3, "течёт кран"))
    }

    /** После переустановки id записей начинаются заново — старый перевод не должен приклеиться. */
    @Test
    fun resultMatchesOnlySameIdAndText() {
        val r = PersonalResult(inboxId = 1, kind = "about", ref = "name", ru = "Меня зовут Алексей")
        assertTrue(r.matches(1, "Меня зовут Алексей"))
        assertFalse(r.matches(1, "Меня зовут Олег"))
        assertFalse(r.matches(2, "Меня зовут Алексей"))
        // Старые результаты без текста — по id.
        assertTrue(PersonalResult(inboxId = 5, kind = "phrase").matches(5, "что угодно"))
    }
}
