package app.lectura.model

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val format: Int = 1,
    val id: String,
    val title: String,
    val author: String = "",
    val level: String = "",
    val lang: String = "es-ES",
    val source: String = "",
    val chapters: List<Chapter>,
)

@Serializable
data class Chapter(
    val title: String,
    val titleRu: String = "",
    val paragraphs: List<Paragraph>,
) {
    /** Все предложения главы подряд — индекс в этом списке и есть «номер предложения». */
    val sentences: List<Sentence> by lazy { paragraphs.flatMap { it.sentences } }
}

@Serializable
data class Paragraph(val sentences: List<Sentence>)

@Serializable
data class Sentence(
    val ru: String = "",
    val tokens: List<Token>,
    /** Необязательный путь к готовому аудио (на будущее, для дикторской озвучки). */
    val audio: String? = null,
) {
    /** Нужен ли пробел перед каждым токеном. */
    val spaces: List<Boolean> by lazy { computeSpaces(tokens) }
    val text: String by lazy {
        buildString {
            tokens.forEachIndexed { i, t ->
                if (spaces[i]) append(' ')
                append(t.t)
            }
        }
    }
}

@Serializable
data class Token(
    val t: String,
    val l: String? = null,
    val ru: String? = null,
    val g: String? = null,
) {
    val isWord: Boolean get() = l != null || t.any { it.isLetter() }
    val key: String get() = t.lowercase()
}

private val NO_SPACE_BEFORE = setOf(".", ",", ";", ":", "!", "?", ")", "»", "…", "”", "%", "...")
private val NO_SPACE_AFTER = setOf("¿", "¡", "(", "«", "“", "—", "–")

private fun computeSpaces(tokens: List<Token>): List<Boolean> {
    val res = ArrayList<Boolean>(tokens.size)
    var noSpaceNext = true
    var quoteOpen = false
    for (tk in tokens) {
        val t = tk.t
        var space = !noSpaceNext && t !in NO_SPACE_BEFORE
        noSpaceNext = t in NO_SPACE_AFTER
        if (t == "\"") {
            if (quoteOpen) {
                space = false
                quoteOpen = false
            } else {
                quoteOpen = true
                noSpaceNext = true
            }
        }
        res += space
    }
    return res
}
