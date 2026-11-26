package app.lectura

import app.lectura.data.DailyStats
import app.lectura.data.GrammarProgress
import app.lectura.data.ProgressSnapshot
import app.lectura.data.SavedWord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProgressSnapshotTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun word(lemma: String, kind: String = SavedWord.KIND_WORD, lastReviewed: Long = now, interval: Int = 10) =
        SavedWord(
            key = lemma, word = lemma, lemma = lemma, ru = "ru", grammar = "", sentence = "", sentenceRu = "",
            bookId = "b", createdAt = now - 30 * day, due = now + day, intervalDays = interval, reps = 3,
            kind = kind, stage = 3, lastReviewed = lastReviewed,
        )

    private fun snapshot() = ProgressSnapshot.of(
        words = listOf(
            word("Tener"),
            word("garganta", lastReviewed = now - 60 * day, interval = 2),
            word("¿Qué tal?", kind = SavedWord.KIND_PHRASE),
        ),
        grammar = listOf(GrammarProgress("a1-ser-estar", "A1", sessions = 2, bestPct = 90, lastPct = 70, stage = 2)),
        days = (1..70).map { DailyStats(day = "2026-%02d-%02d".format(1 + it / 28, 1 + it % 28), reviews = it) },
        now = now,
        appVersion = 1601,
    )

    @Test
    fun wordsOnlyWithRecall() {
        val s = snapshot()
        assertEquals(listOf("tener", "garganta"), s.words.map { it.l })
        assertEquals(1, s.phrases)
        assertEquals(100, s.words[0].recall)
        assertTrue("forgotten word has low recall", s.words[1].recall < 30)
        assertEquals(ProgressSnapshot.DAYS, s.days.size)
    }

    /** Field names and the format are the contract with pipeline/learner.py (clean_snapshot). */
    @Test
    fun matchesServer() {
        val py = File("../../pipeline/learner.py").readText()
        assertTrue(py.contains("SNAPSHOT_FORMAT = ${ProgressSnapshot.FORMAT}"))
        val obj = Json.parseToJsonElement(Json.encodeToString(snapshot())).jsonObject
        assertEquals(ProgressSnapshot.FORMAT, obj["format"]!!.jsonPrimitive.int)
        val keys = obj.keys + listOf("words", "grammar", "days").flatMap { k -> obj[k]!!.jsonArray.first().jsonObject.keys }
        keys.forEach { assertTrue("learner.py must read \"$it\"", py.contains("\"$it\"")) }
        val server = File("../../pipeline/server.py").readText()
        assertTrue(server.contains("web.post(\"/progress\""))
        assertTrue(File("src/main/java/app/lectura/data/LiveChat.kt").readText().contains("\"/progress\""))
    }
}
