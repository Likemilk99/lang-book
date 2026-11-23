package app.lectura.data

import android.content.Context
import androidx.room.TypeConverter
import app.lectura.model.GlossaryItem
import app.lectura.model.Sentence
import app.lectura.model.Token
import app.lectura.model.VocabList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Пример употребления слова: предложение из книги и форма, в которой слово там стоит.
 * Карточка слова одна на начальную форму (hablar), а hablo / hablamos / habla — её примеры.
 */
@Serializable
data class WordExample(
    /** Форма в тексте: «hablamos». По ней строится пропуск в предложении. */
    val form: String,
    val sentence: String,
    val sentenceRu: String = "",
    /** Перевод формы в этом предложении: «говорим». */
    val ru: String = "",
    /** Грамматика формы: «presente, 1 л. мн. ч.». */
    val g: String = "",
    val bookId: String = "",
    val createdAt: Long = 0,
)

private val examplesJson = Json { ignoreUnknownKeys = true }
private val examplesSerializer = ListSerializer(WordExample.serializer())

class Converters {
    @TypeConverter
    fun examplesToJson(v: List<WordExample>): String = examplesJson.encodeToString(examplesSerializer, v)

    @TypeConverter
    fun jsonToExamples(s: String?): List<WordExample> =
        if (s.isNullOrBlank()) emptyList() else runCatching { examplesJson.decodeFromString(examplesSerializer, s) }.getOrDefault(emptyList())
}

/** Ключ карточки: начальная форма в нижнем регистре (ударения и ñ сохраняются: año ≠ ano). */
fun wordKey(s: String): String = s.trim().lowercase()

private fun sameSentence(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

/**
 * Все примеры карточки. У карточек, сохранённых до версии 0.14, пример один — в полях
 * sentence/sentenceRu, а форма из текста — в самом [SavedWord.word].
 */
val SavedWord.allExamples: List<WordExample>
    get() = examples.ifEmpty {
        if (sentence.isBlank()) emptyList()
        else listOf(WordExample(word, sentence, sentenceRu, ru, grammar, bookId, createdAt))
    }

/** Форма слова в текущем примере (sentence): «hablamos» для карточки hablar. */
val SavedWord.form: String
    get() = examples.firstOrNull { sameSentence(it.sentence, sentence) }?.form ?: word

/** Карточка с [ex] в роли текущего примера (так её видят упражнения). */
fun SavedWord.withExample(ex: WordExample): SavedWord =
    copy(sentence = ex.sentence, sentenceRu = ex.sentenceRu, grammar = ex.g.ifBlank { grammar })

/** Карточка с ещё одним примером; null — такое предложение у неё уже есть. */
fun SavedWord.plusExample(ex: WordExample): SavedWord? {
    val have = allExamples
    if (ex.sentence.isBlank() || have.any { sameSentence(it.sentence, ex.sentence) }) return null
    return copy(examples = have + ex)
}

/**
 * Словарный перевод начальной формы: «mano» → «рука» (а не «руку», как в контексте).
 * Источники: словарь учебной программы, затем assets/lemmas.json (pipeline/lemmas.py).
 */
class Lexicon(private val ru: Map<String, String>) {
    fun ru(lemma: String): String? = ru[wordKey(lemma)]?.takeIf { it.isNotBlank() }

    companion object {
        val EMPTY = Lexicon(emptyMap())

        fun of(vocab: Map<String, VocabList>, lemmas: Map<String, String>): Lexicon {
            val map = HashMap<String, String>()
            lemmas.forEach { (l, r) -> map[wordKey(l)] = r }
            // Словарь программы проверен вручную — он главнее; уровни по порядку, первое значение остаётся.
            val fromCurriculum = HashMap<String, String>()
            vocab.values.flatMap { it.themes }.flatMap { it.words }
                .filter { it.pos != "expr" && it.ru.isNotBlank() }
                .forEach { fromCurriculum.putIfAbsent(wordKey(it.l), it.ru) }
            map.putAll(fromCurriculum)
            return Lexicon(map)
        }
    }
}

/** Ленивая загрузка [Lexicon] (словарь программы + assets/lemmas.json). */
class LexiconRepository(private val context: Context, private val curriculum: CurriculumRepository) {
    private val mutex = Mutex()
    private var cached: Lexicon? = null

    suspend fun get(): Lexicon = mutex.withLock {
        cached ?: run {
            val lemmas = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("lemmas.json").use {
                        Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it.readBytes().decodeToString())
                    }
                }.getOrDefault(emptyMap())
            }
            Lexicon.of(runCatching { curriculum.vocab() }.getOrDefault(emptyMap()), lemmas)
        }.also { cached = it }
    }
}

object WordCards {
    /** Ключ карточки для слова из книги: по начальной форме, если она известна. */
    fun keyOf(tk: Token): String = wordKey(headword(tk))

    /** Карточка слова среди [cards] (ключ → карточка): по начальной форме, у старых карточек — по форме из текста. */
    fun find(cards: Map<String, SavedWord>, tk: Token): SavedWord? =
        cards[keyOf(tk)]?.takeIf { !it.isPhrase } ?: cards[tk.key]?.takeIf { !it.isPhrase }

    /** Заголовок карточки: начальная форма (hablar, levantarse, mano); без неё — форма из текста. */
    fun headword(tk: Token): String {
        val l = tk.l?.trim().orEmpty()
        return l.ifBlank { tk.t.trim().lowercase() }
    }

    /** Карточка слова из книги с предложением в роли первого примера. */
    fun fromToken(tk: Token, sentence: Sentence, bookId: String, now: Long, lexicon: Lexicon): SavedWord {
        val head = headword(tk)
        val ex = WordExample(tk.t, sentence.text, sentence.ru, tk.ru.orEmpty(), tk.g.orEmpty(), bookId, now)
        return SavedWord(
            key = wordKey(head), word = head, lemma = head,
            ru = lexicon.ru(head) ?: tk.ru.orEmpty(), grammar = tk.g.orEmpty(),
            sentence = ex.sentence, sentenceRu = ex.sentenceRu, bookId = bookId, createdAt = now, due = now,
            examples = listOf(ex),
        )
    }

    /** Слово из словарика главы; [src] — предложение главы, где оно встречается (если нашлось). */
    fun fromGlossary(item: GlossaryItem, src: Sentence?, bookId: String, now: Long): SavedWord {
        val form = item.form.ifBlank { item.l }
        val tk = src?.tokens?.firstOrNull { it.t.equals(form, ignoreCase = true) }
        val ex = src?.let { WordExample(tk?.t ?: form, it.text, it.ru, tk?.ru.orEmpty(), tk?.g.orEmpty(), bookId, now) }
        return SavedWord(
            key = wordKey(item.l), word = item.l.trim(), lemma = item.l.trim(), ru = item.ru, grammar = item.note,
            sentence = ex?.sentence.orEmpty(), sentenceRu = ex?.sentenceRu.orEmpty(), bookId = bookId,
            createdAt = now, due = now, examples = listOfNotNull(ex),
        )
    }

    /** Что изменить в базе, чтобы у каждого слова осталась одна карточка. */
    data class Consolidation(val update: List<SavedWord>, val delete: List<SavedWord>) {
        val isEmpty: Boolean get() = update.isEmpty() && delete.isEmpty()
    }

    /**
     * Склейка: карточки-слова с одной начальной формой (hablo, hablamos → hablar) становятся одной.
     * Остаётся самая продвинутая (ступень, затем интервал, затем число повторений) — её расписание
     * и ступень сохраняются; примеры всех карточек переходят к ней. Заголовок — начальная форма,
     * перевод — словарный (если он есть в [lexicon]). Фразы не трогаются.
     */
    fun consolidate(words: List<SavedWord>, lexicon: Lexicon): Consolidation {
        val phraseKeys = words.filter { it.isPhrase }.map { it.key }.toSet()
        val groups = words.filter { !it.isPhrase }.groupBy { wordKey(it.lemma.ifBlank { it.word }) }
        val best = groups.mapValues { (_, g) ->
            g.maxWith(compareBy<SavedWord>({ it.stage }, { it.intervalDays }, { it.reps }, { -it.createdAt }))
        }
        // Ключи: начальная форма; если её занимает фраза — прежний ключ карточки.
        val used = phraseKeys.toMutableSet()
        val keys = HashMap<String, String>()
        groups.keys.filter { it !in phraseKeys }.forEach { keys[it] = it; used += it }
        groups.keys.filter { it in phraseKeys }.forEach { target ->
            val own = best.getValue(target).key
            val k = if (own !in used) own else "$own#${best.getValue(target).id}"
            keys[target] = k
            used += k
        }

        val update = ArrayList<SavedWord>()
        val delete = ArrayList<SavedWord>()
        for ((target, g) in groups) {
            val b = best.getValue(target)
            val examples = g.sortedBy { it.createdAt }.flatMap { it.allExamples }
                .fold(emptyList<WordExample>()) { acc, ex -> if (acc.any { sameSentence(it.sentence, ex.sentence) }) acc else acc + ex }
            val head = b.lemma.trim().ifBlank { b.word.trim() }
            val first = examples.firstOrNull()
            val merged = b.copy(
                key = keys.getValue(target),
                word = head,
                lemma = head,
                ru = lexicon.ru(head) ?: b.ru,
                sentence = first?.sentence ?: b.sentence,
                sentenceRu = first?.sentenceRu ?: b.sentenceRu,
                grammar = first?.g?.ifBlank { null } ?: b.grammar,
                examples = examples,
                createdAt = g.minOf { it.createdAt },
                lastReviewed = g.maxOf { it.lastReviewed },
                practiceCount = g.maxOf { it.practiceCount },
            )
            if (merged != b) update += merged
            delete += g.filter { it.id != b.id }
        }
        return Consolidation(update, delete)
    }
}
