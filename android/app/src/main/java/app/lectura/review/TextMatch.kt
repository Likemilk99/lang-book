package app.lectura.review

import app.lectura.speech.SpanishNumbers
import java.text.Normalizer

/** Сравнение и разбор испанского текста для упражнений. */
object TextMatch {
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s]")
    private val SPACES = Regex("\\s+")
    private val EDGE_PUNCT = charArrayOf('.', ',', ';', ':', '!', '?', '¡', '¿', '…', '"', '«', '»', ' ')

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Слова без регистра и пунктуации (числа ещё цифрами). */
    private fun rawWords(s: String): List<String> =
        nfc(SpanishNumbers.joinThousands(s)).lowercase().replace(NON_WORD, " ").split(SPACES).filter { it.isNotEmpty() }

    /**
     * Без регистра и пунктуации, пробелы схлопнуты, числа — словами («3» → «tres»).
     * Ударения и ñ сохраняются.
     */
    fun norm(s: String): String = normVariants(s).first()

    /** Как [norm], но ещё и без ударений (á → a, ñ → n, ü → u). */
    fun loose(s: String): String = stripMarks(norm(s))

    private fun stripMarks(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")

    /**
     * Все прочтения текста: если в нём есть числа на 1 («1», «21»), то варианты uno/un/una.
     * Первый вариант — основной (то же, что [norm]).
     */
    fun normVariants(s: String): List<String> {
        var out = listOf("")
        for (w in rawWords(s)) {
            val forms = SpanishNumbers.forms(w) ?: listOf(w)
            out = out.flatMap { v -> forms.map { f -> if (v.isEmpty()) f else "$v $f" } }.take(MAX_VARIANTS)
        }
        return out
    }

    /** Совпадает ли ввод с одним из правильных ответов: без регистра, пунктуации, числа = слова. */
    fun same(typed: String, answer: String): Boolean {
        val a = normVariants(answer).toSet()
        return normVariants(typed).any { it in a }
    }

    /** То же, но без учёта ударений. */
    fun sameLoose(typed: String, answer: String): Boolean {
        val a = normVariants(answer).map(::stripMarks).toSet()
        return normVariants(typed).any { stripMarks(it) in a }
    }

    /**
     * Разбор ответа по словам: какие слова ввода есть в правильном ответе (по наибольшей общей
     * подпоследовательности, без регистра и ударений). Сравнивается с самым похожим из [answers].
     */
    fun wordDiff(typed: String, answers: List<String>): List<Pair<String, Boolean>> {
        val chunks = typed.trim().split(SPACES).filter { it.isNotEmpty() }
        val chunkWords = chunks.map { c -> loose(c).split(' ').filter { it.isNotEmpty() } }
        val flat = ArrayList<String>()
        val owner = ArrayList<Int>()
        chunkWords.forEachIndexed { i, ws -> ws.forEach { flat += it; owner += i } }
        var best = BooleanArray(flat.size)
        var bestCount = -1
        for (a in answers.filter { it.isNotBlank() }) {
            val exp = loose(a).split(' ').filter { it.isNotEmpty() }
            val flags = lcs(flat, exp)
            val c = flags.count { it }
            if (c > bestCount) {
                bestCount = c
                best = flags
            }
        }
        val ok = BooleanArray(chunks.size) { true }
        flat.indices.forEach { if (!best[it]) ok[owner[it]] = false }
        return chunks.mapIndexed { i, c -> c to ok[i] }
    }

    /** Какие слова [a] входят в наибольшую общую подпоследовательность с [b]. */
    private fun lcs(a: List<String>, b: List<String>): BooleanArray {
        val flags = BooleanArray(a.size)
        if (a.isEmpty() || b.isEmpty()) return flags
        fun eq(x: String, y: String) = x == y || (x in UNO && y in UNO)
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0) {
            dp[i][j] = if (eq(a[i], b[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                eq(a[i], b[j]) && dp[i][j] == dp[i + 1][j + 1] + 1 -> { flags[i] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return flags
    }

    private const val MAX_VARIANTS = 12
    private val UNO = setOf("un", "uno", "una")

    /** Ищет слово/фразу в предложении без учёта регистра, по границам слов. */
    fun cloze(sentence: String, item: String): Cloze? {
        val s = nfc(sentence)
        val variants = listOf(nfc(item).trim(), nfc(item).trim(*EDGE_PUNCT)).filter { it.isNotBlank() }.distinct()
        for (v in variants) {
            val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(v) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            val m = re.find(s) ?: continue
            return Cloze(s.substring(0, m.range.first), m.value, s.substring(m.range.last + 1))
        }
        return null
    }

    /** Слова предложения (знаки препинания остаются прилипшими к словам, отдельные знаки отбрасываются). */
    fun tiles(sentence: String): List<String> =
        nfc(sentence).split(SPACES).filter { t -> t.any { it.isLetterOrDigit() } }

    /** Класс грамматики для подбора похожих вариантов: «сущ.», «прил.», «presente»… */
    fun grammarClass(g: String?): String =
        g.orEmpty().substringBefore(',').substringBefore('(').trim().lowercase()

    /** Если ответ с заглавной (начало предложения) — варианты тоже с заглавной. */
    fun matchCase(option: String, like: String): String {
        val first = like.firstOrNull { it.isLetter() } ?: return option
        return if (first.isUpperCase()) option.replaceFirstChar { it.uppercase() } else option
    }

    /** Форма слова без «заглавной из-за начала предложения» (имена собственные не трогаем). */
    fun plainForm(form: String, lemma: String?): String =
        if (lemma?.firstOrNull()?.isUpperCase() == true) form else form.lowercase()
}
