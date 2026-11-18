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
class ExpressionIndex(expressions: List<Expression>) {
    private class Entry(val expr: Expression, val words: List<String>)

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

        fun from(vocab: Map<String, VocabList>): ExpressionIndex = ExpressionIndex(
            vocab.entries.flatMap { (level, list) ->
                list.themes.flatMap { t -> t.words.filter { ' ' in it.l.trim() }.map { Expression(it.l.trim(), it.ru, level) } }
            }.distinctBy { fold(it.es) },
        )
    }
}
