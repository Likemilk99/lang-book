package app.lectura.review

import java.text.Normalizer

/** Сравнение и разбор испанского текста для упражнений. */
object TextMatch {
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s]")
    private val SPACES = Regex("\\s+")
    private val EDGE_PUNCT = charArrayOf('.', ',', ';', ':', '!', '?', '¡', '¿', '…', '"', '«', '»', ' ')

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Без регистра и пунктуации, пробелы схлопнуты. Ударения и ñ сохраняются. */
    fun norm(s: String): String = nfc(s).lowercase().replace(NON_WORD, " ").replace(SPACES, " ").trim()

    /** Как [norm], но ещё и без ударений (á → a, ñ → n, ü → u). */
    fun loose(s: String): String = Normalizer.normalize(norm(s), Normalizer.Form.NFD).replace(MARKS, "")

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
