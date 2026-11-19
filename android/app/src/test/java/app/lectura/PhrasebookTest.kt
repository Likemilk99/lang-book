package app.lectura

import app.lectura.data.SavedWord
import app.lectura.model.Phrase
import app.lectura.model.PhraseSlot
import app.lectura.model.Phrasebook
import app.lectura.review.BookToken
import app.lectura.review.Exercise
import app.lectura.review.ExerciseBuilder
import app.lectura.review.ExerciseType
import app.lectura.review.Pool
import app.lectura.review.Direction
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Разговорники из assets (формат, шаблоны, озвучка) и упражнения по фразам. */
class PhrasebookTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File("src/main/assets/phrasebook")

    private fun books(): List<Phrasebook> {
        val files = dir.listFiles { f -> f.name.startsWith("phrasebook_") && f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("нет разговорников в ${dir.absolutePath}", files.isNotEmpty())
        return files.map { json.decodeFromString(Phrasebook.serializer(), it.readText()) }
    }

    @Test
    fun levelsA0andA1Present() {
        assertEquals(listOf("A0", "A1"), books().map { it.level })
    }

    @Test
    fun phrasesAreConsistent() {
        val errors = mutableListOf<String>()
        val ids = HashSet<String>()
        val texts = HashSet<String>()
        for (b in books()) {
            if (b.themes.isEmpty()) errors += "${b.level}: нет тем"
            for (t in b.themes) {
                if (t.phrases.size < 8) errors += "${t.id}: мало фраз (${t.phrases.size})"
                for (p in t.phrases) {
                    if (!ids.add(p.id)) errors += "повтор id ${p.id}"
                    if (!texts.add(p.es.lowercase().trim())) errors += "повтор фразы «${p.es}»"
                    if (p.es.isBlank() || p.ru.isBlank()) errors += "${p.id}: пустая фраза или перевод"
                    if (p.es.contains(Phrase.GAP)) errors += "${p.id}: пропуск в самой фразе"
                    if (p.pattern.isNotBlank()) {
                        if (!p.hasPattern) errors += "${p.id}: шаблон без вариантов"
                        if (p.slots.firstOrNull()?.es != p.es) errors += "${p.id}: первый вариант ≠ фраза"
                        p.slots.forEach { s ->
                            if (s.es != p.pattern.replaceFirst(Phrase.GAP, s.fill)) errors += "${p.id}: вариант «${s.es}» не по шаблону"
                            if (s.ru.isBlank()) errors += "${p.id}: вариант без перевода"
                        }
                    }
                }
            }
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }

    /** Каждая фраза и каждый вариант шаблона озвучены (файлы — pipeline/audio.py --phrasebook). */
    @Test
    fun everyPhraseAndVariantHasAudio() {
        val audio = File(dir, "audio")
        val missing = mutableListOf<String>()
        for (b in books()) for (t in b.themes) for (p in t.phrases) {
            if (!File(audio, "${p.id}.m4a").exists()) missing += p.id
            p.slots.forEachIndexed { k, s ->
                if (k > 0 && s.es != p.es && !File(audio, "${p.id}_$k.m4a").exists()) missing += "${p.id}_$k"
            }
        }
        if (missing.isNotEmpty()) fail("нет озвучки (${missing.size}): ${missing.take(20)}")
    }

    // ------------------------------------------------------------ упражнения

    private val phrase = Phrase(
        id = "a0-asking-01", es = "Quería un café.", ru = "Я бы хотел кофе.", pattern = "Quería ___.",
        slots = listOf(
            PhraseSlot("un café", "Quería un café.", "Я бы хотел кофе."),
            PhraseSlot("una botella de agua", "Quería una botella de agua.", "Я бы хотел бутылку воды."),
        ),
    )

    private fun saved(stage: Int, ref: String = phrase.id) = SavedWord(
        id = 1, key = "quería un café.", word = phrase.es, lemma = phrase.es, ru = phrase.ru, grammar = "разговорник",
        sentence = phrase.es, sentenceRu = phrase.ru, bookId = "phrasebook", createdAt = 0, due = 0,
        kind = SavedWord.KIND_PHRASE, stage = stage, ref = ref,
    )

    private fun builder(speech: Boolean, seed: Int = 1) = ExerciseBuilder(
        Pool(
            items = listOf(saved(0)),
            tokens = listOf(BookToken("casa", "дом", "сущ."), BookToken("perro", "собака", "сущ."), BookToken("gato", "кот", "сущ.")),
            replies = emptyList(),
            phrases = mapOf(phrase.id to phrase),
        ),
        speechAvailable = speech, direction = Direction.ES_RU, rnd = Random(seed),
    )

    @Test
    fun speakingFromTheFirstStage() {
        val b = builder(speech = true)
        assertTrue(ExerciseType.LISTEN_REPEAT in b.ladder(0, phrase = false))
        assertTrue(ExerciseType.SAY_ES in b.ladder(3, phrase = false))
        // У фраз речь вдвое чаще.
        assertEquals(2, b.ladder(0, phrase = true).count { it == ExerciseType.LISTEN_REPEAT })
        assertEquals(1, b.ladder(0, phrase = false).count { it == ExerciseType.LISTEN_REPEAT })
    }

    @Test
    fun patternSayUsesAnotherVariant() {
        val seen = (1..40).map { builder(speech = true, seed = it).forWord(saved(4), null, null, cardsOnly = false) }
        val ps = seen.filterIsInstance<Exercise.PatternSay>()
        assertTrue("PatternSay ни разу не выпал", ps.isNotEmpty())
        ps.forEach {
            assertEquals("Quería una botella de agua.", it.variantEs)
            assertEquals(1, it.variant)
        }
        // Пропуск на всё предложение (фраза = пример) не строится.
        assertFalse(seen.any { it is Exercise.ClozeInput || it is Exercise.ClozeChoice })
    }

    @Test
    fun noSpeechNoSpeakingExercises() {
        val all = (1..40).map { builder(speech = false, seed = it).forWord(saved(it % 7), null, null, cardsOnly = false) }
        assertFalse(all.any { it is Exercise.ListenRepeat || it is Exercise.SayEs || it is Exercise.PatternSay })
    }

    @Test
    fun noPatternWithoutPhrasebookRef() {
        val all = (1..40).map { builder(speech = true, seed = it).forWord(saved(5, ref = ""), null, null, cardsOnly = false) }
        assertNull(all.firstOrNull { it is Exercise.PatternSay })
    }
}
