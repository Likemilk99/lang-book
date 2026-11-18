package app.lectura.data

import app.lectura.model.Sentence
import app.lectura.model.Token
import app.lectura.model.VocabList
import java.text.Normalizer

/** Устойчивое выражение из словаря учебной программы: «otra vez — снова», «darse cuenta — заметить». */
data class Expression(val es: String, val ru: String, val level: String)

/** Выражение, найденное в предложении: токены [start]..[end] включительно. */
data class ExpressionMatch(val start: Int, val end: Int, val expr: Expression) {
    val range: IntRange get() = start..end
}

/**
 * Находит в тексте выражения из нескольких слов (их ~1000 в словарях A1–B2). Слово выражения совпадает
 * с токеном по форме или по начальной форме; глагол на -se совпадает и без -se
 * («darse cuenta» ↔ «me di cuenta»: di → dar). Слова должны идти подряд.
 */
class ExpressionIndex(expressions: List<Expression>, words: List<Expression> = emptyList()) {
    private class Entry(val expr: Expression, val words: List<String>)

    /** Отдельные слова программы (начальная форма → слово с уровнем): «vez — раз · A1». */
    private val singles: Map<String, Expression> = words.associateBy { fold(it.es) }

    /** Первое слово → выражения, длинные раньше коротких. */
    private val byFirst: Map<String, List<Entry>> = expressions
        .mapNotNull { e ->
            val words = e.es.split(' ').map { fold(it) }.filter { it.isNotEmpty() }
            if (words.size < 2 || e.es.contains('…') || e.es.contains("...") || fold(e.es) in BLOCKED) null else Entry(e, words)
        }
        .groupBy { it.words.first() }
        .mapValues { (_, v) -> v.sortedByDescending { it.words.size } }

    private val byText: Map<String, Expression> = expressions.associateBy { fold(it.es) }

    val size: Int get() = byFirst.values.sumOf { it.size }

    /** Выражение, записанное ровно так (для выделенного вручную фрагмента). */
    fun lookup(text: String): Expression? = byText[fold(text)]

    /** Слово выражения как отдельное слово программы (по форме или начальной форме, «darse» → «dar»). */
    fun word(tk: Token): Expression? {
        val keys = listOfNotNull(tk.l?.let { fold(it) }, tk.l?.let { fold(it).removeSuffix("se") }, fold(tk.t))
        return keys.firstNotNullOfOrNull { singles[it] }
    }

    /** Другие выражения с этим словом («vez» → a veces, tal vez…), короткие и простые первыми. */
    fun containing(tk: Token, except: Expression, limit: Int = 4): List<Expression> {
        // + множественное число: «vez» → «a veces», «día» → «buenos días»
        val keys = forms(tk).flatMap { k -> listOf(k, k + "s", k + "es", if (k.endsWith("z")) k.dropLast(1) + "ces" else k) }.toSet()
        return byFirst.values.asSequence().flatten()
            .filter { e -> e.expr !== except && e.words.any { w -> w in keys || (w.endsWith("se") && w.dropLast(2) in keys) } }
            .map { it.expr }
            .sortedWith(compareBy({ it.level }, { it.es.length }))
            .take(limit)
            .toList()
    }

    /**
     * Сохранённые пользователем фразы в предложении — для подсветки в тексте. [saved] — карта
     * [phraseKey] сохранённой фразы (и её словарной формы) → статус. Выражение из словаря находим
     * в любой форме («me di cuenta» ← «darse cuenta»), свою фразу — по точному совпадению слов.
     */
    fun savedRanges(sentence: Sentence, saved: Map<String, WordStatus>): List<Pair<IntRange, WordStatus>> {
        if (saved.isEmpty()) return emptyList()
        val out = ArrayList<Pair<IntRange, WordStatus>>()
        for (m in find(sentence)) {
            val st = saved[phraseKey(m.expr.es)] ?: saved[phraseKey(phraseOf(sentence, m.range))] ?: continue
            out += m.range to st
        }
        val toks = sentence.tokens
        for ((key, st) in saved) {
            val ws = key.split(' ')
            if (ws.size < 2) continue
            var i = 0
            while (i < toks.size) {
                val r = matchWords(toks, i, ws)
                if (r != null && out.none { (o, _) -> o.first <= r.last && r.first <= o.last }) {
                    out += r to st
                    i = r.last + 1
                } else {
                    i++
                }
            }
        }
        return out
    }

    /** Слова [ws] подряд с токена [i] (знаки препинания между словами пропускаем). */
    private fun matchWords(toks: List<Token>, i: Int, ws: List<String>): IntRange? {
        if (!toks[i].isWord) return null
        var j = i
        var k = 0
        while (k < ws.size && j < toks.size) {
            val tk = toks[j]
            if (!tk.isWord) { j++; continue }
            if (fold(tk.t) != ws[k]) return null
            k++
            j++
        }
        return if (k == ws.size) i until j else null
    }

    /** Все выражения предложения; перекрывающиеся — оставляем более длинное (и более раннее). */
    fun find(sentence: Sentence): List<ExpressionMatch> {
        val toks = sentence.tokens
        val found = ArrayList<ExpressionMatch>()
        var i = 0
        while (i < toks.size) {
            val m = if (toks[i].isWord) matchAt(toks, i) else null
            if (m != null) {
                found += m
                i = m.end + 1
            } else {
                i++
            }
        }
        return found
    }

    private fun matchAt(toks: List<Token>, i: Int): ExpressionMatch? {
        val candidates = HashSet<String>()
        candidates += forms(toks[i])
        for (c in candidates) {
            for (e in byFirst[c].orEmpty()) {
                if (i + e.words.size > toks.size) continue
                var ok = true
                for (k in e.words.indices) {
                    val tk = toks[i + k]
                    if (!tk.isWord || !wordMatches(e.words[k], tk)) {
                        ok = false
                        break
                    }
                }
                if (!ok) continue
                // «ir a», «empezar a», «tener que»… — выражение только перед инфинитивом
                // («va a comprar» — собирается; «va a la tienda» — просто идёт).
                if (e.words.last() in PREPOSITIONS && isVerb(e.words.first()) && !isInfinitive(toks.getOrNull(i + e.words.size))) continue
                return ExpressionMatch(i, i + e.words.size - 1, e.expr)
            }
        }
        return null
    }

    /** Ключи, по которым токен может начинать выражение. */
    private fun forms(tk: Token): Set<String> {
        val out = HashSet<String>()
        out += fold(tk.t)
        tk.l?.let { l -> out += fold(l); out += fold(l) + "se" }
        return out
    }

    private fun wordMatches(w: String, tk: Token): Boolean {
        val t = fold(tk.t)
        if (w == t) return true
        val l = tk.l?.let { fold(it) } ?: return false
        if (w == l) return true
        // «darse» ↔ «dar», «ponerse» ↔ «poner»
        return w.endsWith("se") && w.length > 4 && w.dropLast(2) == l
    }

    private fun isVerb(w: String) = VERB_END.containsMatchIn(w)

    /** Инфинитив (возможно, с местоимением: «llamarme»): форма начинается с начальной формы на -ar/-er/-ir. */
    private fun isInfinitive(tk: Token?): Boolean {
        if (tk == null || !tk.isWord) return false
        if (tk.t.firstOrNull()?.isUpperCase() == true) return false // имена: «Javier»
        val t = fold(tk.t)
        val l = tk.l?.let { fold(it) }?.removeSuffix("se") ?: return false
        return INFINITIVE.matches(l) && t.startsWith(l)
    }

    companion object {
        private val PREPOSITIONS = setOf("a", "de", "en", "con", "que", "por", "para", "sobre")
        private val VERB_END = Regex("(ar|er|ir|ír)(se)?$")
        private val INFINITIVE = Regex(".*(ar|er|ir|ír)$")
        /** Выражения, которые почти всегда совпадают случайно («es más grande» — не «более того»). */
        private val BLOCKED = setOf("es más")

        /**
         * Нижний регистр, без знаков по краям. Ударения ВАЖНЫ («no sé» ≠ «no se»), кроме старого
         * написания предлога «á» у классиков.
         */
        fun fold(s: String): String {
            val f = Normalizer.normalize(s.lowercase().trim().trim('¿', '¡', '.', ',', '!', '?', ';', ':', '"', '«', '»'), Normalizer.Form.NFC)
            return if (f == "á") "a" else f
        }

        /** Текст фрагмента предложения, как он написан (с пробелами между словами). */
        fun phraseOf(sentence: Sentence, range: IntRange): String = buildString {
            for (i in range) {
                if (i != range.first && sentence.spaces[i]) append(' ')
                append(sentence.tokens[i].t)
            }
        }.trim().trimEnd(',', ';', ':')

        /** Ключ сохранённой фразы для подсветки: слова через пробел, без регистра и знаков. */
        fun phraseKey(s: String): String =
            s.split(' ').map { fold(it) }.filter { it.isNotEmpty() }.joinToString(" ")

        fun from(vocab: Map<String, VocabList>): ExpressionIndex {
            // Уровни идут A1→B2: слово берём с самого раннего уровня.
            val all = vocab.entries.sortedBy { it.key }.flatMap { (level, list) ->
                list.themes.flatMap { t -> t.words.map { Expression(it.l.trim(), it.ru, level) } }
            }
            val (multi, single) = all.partition { ' ' in it.es }
            return ExpressionIndex(multi.distinctBy { fold(it.es) }, single.distinctBy { fold(it.es) })
        }
    }
}
