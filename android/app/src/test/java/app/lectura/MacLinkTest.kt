package app.lectura

import app.lectura.data.InboxItem
import app.lectura.data.MacLink
import app.lectura.data.PersonalRepository
import app.lectura.model.Phrase
import app.lectura.model.PersonalResult
import app.lectura.ui.macStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MacLinkTest {
    @Test
    fun address() {
        assertEquals("192.168.1.5:8765", MacLink.parseAddress("192.168.1.5:8765"))
        assertEquals("192.168.1.5:8765", MacLink.parseAddress(" 192.168.1.5 "))
        assertEquals("10.0.2.2:9000", MacLink.parseAddress("http://10.0.2.2:9000/"))
        assertEquals("macbook.local:8765", MacLink.parseAddress("macbook.local"))
        assertNull(MacLink.parseAddress(""))
        assertNull(MacLink.parseAddress("192.168.1.5:порт"))
        assertNull(MacLink.parseAddress("192.168.1.5:99999"))
        assertNull(MacLink.parseAddress("a b"))
    }

    @Test
    fun clipNamesMatchPipeline() {
        val about = PersonalResult(5, "about", "name", "Меня зовут Иван", listOf(Phrase("me-5-1", "Me llamo Iván.", "")), question = "¿Cómo te llamas?")
        assertEquals(listOf("me-5-1", "q-name"), MacLink.clipNames(about))
        val phrase = PersonalResult(6, "phrase", "", "аптека", listOf(Phrase("me-6-1", "a", ""), Phrase("me-6-2", "b", "")))
        assertEquals(listOf("me-6-1", "me-6-2"), MacLink.clipNames(phrase))
        // Имена — те же, что сервер готов отдать (pipeline/server.py NAME_RE).
        val re = Regex("^[A-Za-z0-9_-]{1,80}$")
        (MacLink.clipNames(about) + MacLink.clipNames(phrase)).forEach { assert(re.matches(it)) { it } }
    }

    @Test
    fun mergeKeepsOldAndReplacesSameEntry() {
        val a = PersonalResult(1, "phrase", ru = "раз", phrases = listOf(Phrase("me-1-1", "uno", "")))
        val b = PersonalResult(2, "phrase", ru = "два", phrases = listOf(Phrase("me-2-1", "dos", "")))
        val b2 = b.copy(phrases = listOf(Phrase("me-2-1", "dos!", "")))
        val sameIdOtherText = PersonalResult(2, "phrase", ru = "другое", phrases = emptyList())
        val merged = PersonalRepository.merge(listOf(a, b), listOf(b2, sameIdOtherText))
        assertEquals(3, merged.size)
        assertEquals("dos!", merged.first { it.inboxId == 2L && it.ru == "два" }.phrases.single().es)
    }

    @Test
    fun onlyPendingEntriesGoToMac() {
        val items = listOf(
            InboxItem(id = 1, kind = InboxItem.ABOUT, ref = "name", ru = "Иван", createdAt = 0),
            InboxItem(id = 2, kind = InboxItem.PHRASE, ru = "готово", status = InboxItem.DONE, createdAt = 0),
        )
        val e = PersonalRepository.entriesOf(items).single()
        assertEquals(1L, e.id)
        assertEquals("¿Cómo te llamas?", e.question)
    }

    @Test
    fun statusText() {
        assertEquals("Mac на связи", macStatusText(MacLink.State(MacLink.Status.IDLE)))
        assertEquals("Mac не найден · ждут перевода: 2", macStatusText(MacLink.State(MacLink.Status.OFFLINE), 2))
        assertEquals("Mac озвучивает · в работе: 3", macStatusText(MacLink.State(MacLink.Status.BUSY, queue = 3, phase = "озвучиваю")))
        assertEquals("Mac не принял код", macStatusText(MacLink.State(MacLink.Status.NEED_CODE, wrongCode = true)))
    }

    /** Порт и тип сервиса в приложении и в pipeline/server.py совпадают. */
    @Test
    fun sameContractAsServer() {
        val py = File("../../pipeline/server.py").readText()
        assert("PORT = ${MacLink.PORT}" in py)
        assert("SERVICE = \"${MacLink.SERVICE}\"" in py)
        assert("\"${MacLink.HEADER}\"" in py)
    }
}
