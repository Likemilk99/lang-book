package app.lectura

import app.lectura.data.Converters
import app.lectura.data.Lexicon
import app.lectura.data.SavedWord
import app.lectura.data.WordCards
import app.lectura.data.WordExample
import app.lectura.data.allExamples
import app.lectura.data.form
import app.lectura.data.plusExample
import app.lectura.data.withExample
import app.lectura.model.GlossaryItem
import app.lectura.model.Sentence
import app.lectura.model.Token
import app.lectura.review.Direction
import app.lectura.review.ExerciseBuilder
import app.lectura.review.Pool
import app.lectura.review.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WordCardsTest {
    private val lexicon = Lexicon(mapOf("hablar" to "говорить", "mano" to "рука"))

    private fun sentence(ru: String, vararg tokens: Token) = Sentence(ru = ru, tokens = tokens.toList())

    /** Карточка, сохранённая до 0.14: ключ и слово — форма из текста, пример в sentence. */
    private fun legacy(
        id: Long,
        form: String,
        lemma: String,
        sentence: String,
        stage: Int = 0,
        intervalDays: Int = 0,
        createdAt: Long = id,
        kind: String = SavedWord.KIND_WORD,
    ) = SavedWord(
        id = id, key = form.lowercase(), word = form, lemma = lemma, ru = "ru-$form", grammar = "g-$form",
        sentence = sentence, sentenceRu = "", bookId = "b", createdAt = createdAt, due = 0,
        stage = stage, intervalDays = intervalDays, kind = kind,
    )

    @Test
    fun cardFromTokenUsesDictionaryForm() {
        val tk = Token("hablamos", l = "hablar", ru = "говорим", g = "presente, 1 л. мн. ч.")
        val s = sentence("Мы много говорим.", tk, Token("mucho", l = "mucho"), Token("."))
        val card = WordCards.fromToken(tk, s, "book", 5, lexicon)
        assertEquals("hablar", card.key)
        assertEquals("hablar", card.word)
        assertEquals("говорить", card.ru) // словарный перевод, а не «говорим»
        assertEquals(1, card.examples.size)
        assertEquals("hablamos", card.examples[0].form)
        assertEquals("говорим", card.examples[0].ru)
        assertEquals("hablamos mucho.", card.sentence)
        assertEquals("hablamos", card.form)
    }

    @Test
    fun withoutLexiconTranslationStaysContextual() {
        val tk = Token("manos", l = "mano", ru = "руки")
        val card = WordCards.fromToken(tk, sentence("", tk), "b", 0, Lexicon.EMPTY)
        assertEquals("руки", card.ru)
        assertEquals("mano", card.key)
    }

    @Test
    fun tokenWithoutLemmaKeepsLowercasedForm() {
        val tk = Token("Hola", ru = "привет")
        assertEquals("hola", WordCards.keyOf(tk))
        assertEquals("hola", WordCards.fromToken(tk, sentence("", tk), "b", 0, lexicon).word)
    }

    @Test
    fun accentsAndEnyeMakeDifferentCards() {
        assertNotEquals(WordCards.keyOf(Token("año", l = "año")), WordCards.keyOf(Token("ano", l = "ano")))
    }

    @Test
    fun glossaryWordKeyedByLemma() {
        val s = sentence("Он пошёл.", Token("Fue", l = "ir", ru = "пошёл", g = "pretérito"), Token("."))
        val card = WordCards.fromGlossary(GlossaryItem(l = "ir", ru = "идти", form = "fue"), s, "b", 0)
        assertEquals("ir", card.key)
        assertEquals("Fue", card.examples.single().form)
        assertEquals("pretérito", card.examples.single().g)
    }

    @Test
    fun plusExampleSkipsSameSentenceAndKeepsLegacyOne() {
        val old = legacy(1, "hablo", "hablar", "Hablo español.")
        assertNull(old.plusExample(WordExample("Hablo", "hablo español.")))
        val upd = old.plusExample(WordExample("hablamos", "Hablamos mucho."))!!
        assertEquals(listOf("hablo", "hablamos"), upd.allExamples.map { it.form })
    }

    @Test
    fun consolidateMergesFormsIntoOneCard() {
        val words = listOf(
            legacy(1, "hablo", "hablar", "Hablo español.", stage = 1, createdAt = 10),
            legacy(2, "hablamos", "hablar", "Hablamos mucho.", stage = 3, intervalDays = 7, createdAt = 20),
            legacy(3, "habla", "hablar", "Ella habla rápido.", stage = 2, createdAt = 30),
            legacy(4, "mesa", "mesa", "La mesa es grande."),
        )
        val plan = WordCards.consolidate(words, lexicon)
        assertEquals(setOf(1L, 3L), plan.delete.map { it.id }.toSet())
        val hablar = plan.update.single { it.id == 2L }
        assertEquals("hablar", hablar.key)
        assertEquals("hablar", hablar.word)
        assertEquals("говорить", hablar.ru)
        assertEquals(3, hablar.stage) // прогресс лучшей карточки
        assertEquals(7, hablar.intervalDays)
        assertEquals(10, hablar.createdAt) // самая ранняя
        assertEquals(listOf("hablo", "hablamos", "habla"), hablar.examples.map { it.form })
        // «mesa» уже по начальной форме — меняется только формат примеров
        val mesa = plan.update.single { it.id == 4L }
        assertEquals("mesa", mesa.key)
        assertEquals(1, mesa.examples.size)
    }

    @Test
    fun consolidateIsIdempotent() {
        val words = listOf(
            legacy(1, "hablo", "hablar", "Hablo español."),
            legacy(2, "hablamos", "hablar", "Hablamos mucho."),
        )
        val plan = WordCards.consolidate(words, lexicon)
        val after = words.filter { w -> plan.delete.none { it.id == w.id } }
            .map { w -> plan.update.firstOrNull { it.id == w.id } ?: w }
        assertTrue(WordCards.consolidate(after, lexicon).isEmpty)
    }

    @Test
    fun consolidateLeavesPhrasesAndAvoidsTheirKeys() {
        val phrase = legacy(1, "hola", "hola", "hola", kind = SavedWord.KIND_PHRASE)
        val word = legacy(2, "Hola", "hola", "¡Hola, Marta!").copy(key = "hola,")
        val plan = WordCards.consolidate(listOf(phrase, word), lexicon)
        assertTrue(plan.delete.isEmpty())
        assertTrue(plan.update.none { it.id == 1L })
        assertEquals("hola,", plan.update.single().key) // «hola» занят фразой — ключ прежний
    }

    @Test
    fun consolidateCanSwapKeys() {
        // «vino» (пришёл) → venir, «vinos» → vino: ключ одной карточки — бывший ключ другой.
        val plan = WordCards.consolidate(
            listOf(legacy(1, "vino", "venir", "Vino ayer."), legacy(2, "vinos", "vino", "Dos vinos.")),
            lexicon,
        )
        assertEquals(mapOf(1L to "venir", 2L to "vino"), plan.update.associate { it.id to it.key })
    }

    @Test
    fun examplesRoundTripThroughRoom() {
        val c = Converters()
        val ex = listOf(WordExample("año", "Un año.", "Год.", "год", "сущ., м. р.", "b", 3))
        assertEquals(ex, c.jsonToExamples(c.examplesToJson(ex)))
        assertEquals(emptyList<WordExample>(), c.jsonToExamples("[]"))
        assertEquals(emptyList<WordExample>(), c.jsonToExamples("broken"))
    }

    @Test
    fun exerciseTakesAnotherExampleAndClozeUsesItsForm() {
        val card = legacy(1, "hablo", "hablar", "Hablo español.")
            .plusExample(WordExample("hablamos", "Nosotros hablamos mucho.", g = "presente, 1 л. мн. ч."))!!
            .copy(word = "hablar", key = "hablar")
        val b = ExerciseBuilder(Pool.EMPTY, speechAvailable = false, direction = Direction.ES_RU, rnd = Random(1))
        repeat(5) {
            val shown = b.withNextExample(card)
            assertEquals("Nosotros hablamos mucho.", shown.sentence) // не тот, что был в прошлый раз
            assertEquals("hablamos", shown.form)
            assertEquals("presente, 1 л. мн. ч.", shown.grammar)
            assertEquals("hablamos", TextMatch.cloze(shown.sentence, shown.form)?.answer)
        }
        val back = b.withNextExample(card.withExample(card.allExamples[1]))
        assertEquals("Hablo español.", back.sentence)
    }
}
