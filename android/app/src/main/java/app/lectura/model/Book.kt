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
    /** Персонажи (для разных голосов в диалогах). */
    val characters: List<Character> = emptyList(),
    val chapters: List<Chapter>,
)

@Serializable
data class Character(
    val name: String,
    /** "m" / "f" */
    val gender: String = "",
)

@Serializable
data class Chapter(
    val title: String,
    val titleRu: String = "",
    val paragraphs: List<Paragraph>,
    /** Полезные разговорные фразы главы (речевые модели). */
    val phrases: List<PhraseItem> = emptyList(),
    /** Вопросы на понимание главы. */
    val questions: List<Question> = emptyList(),
    /** Упражнения «выбери уместный ответ в диалоге». */
    val replies: List<ReplyItem> = emptyList(),
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
    /** Кто говорит: имя персонажа для реплик диалога, null — авторский текст. */
    val sp: String? = null,
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
data class PhraseItem(
    /** Фраза на испанском, например "¿Me pones un café?" */
    val es: String,
    val ru: String,
    /** Короткое пояснение по-русски: когда и как так говорят. */
    val note: String = "",
    /** Шаблон с подстановкой, например "¿Me pones ___?" */
    val pattern: String = "",
)

@Serializable
data class Question(
    /** Вопрос на испанском. */
    val q: String,
    val ru: String = "",
    /** Варианты ответа на испанском. */
    val options: List<String>,
    val answer: Int,
)

@Serializable
data class ReplyItem(
    /** Реплика собеседника на испанском, например "¿Qué te pongo?" */
    val prompt: String,
    val promptRu: String = "",
    /** Варианты ответа (испанский), один уместный. */
    val options: List<String>,
    val answer: Int,
    /** Почему этот ответ уместен (по-русски). */
    val why: String = "",
)

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
