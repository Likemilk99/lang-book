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
    /** Теги из фиксированного справочника (см. model/Tags.kt и pipeline TAGS). */
    val tags: List<String> = emptyList(),
    /** "adapted" — пересказ под уровень; "original" — текст произведения без изменений. */
    val kind: String = KIND_ADAPTED,
    /** Общий id произведения: связывает версии (адаптация A1, B1, оригинал). */
    val workId: String = "",
    /** Год первой публикации оригинала (для справки и проверки общественного достояния). */
    val year: Int = 0,
    /** Например "Общественное достояние (автор умер в 1870)". */
    val license: String = "",
    val chapters: List<Chapter>,
) {
    val isOriginal: Boolean get() = kind == KIND_ORIGINAL

    companion object {
        const val KIND_ADAPTED = "adapted"
        const val KIND_ORIGINAL = "original"
    }
}

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
    /** id грамматических тем учебной программы, которые отрабатываются в главе (model/Curriculum.kt). */
    val grammar: List<String> = emptyList(),
    /** Словарик перед чтением: ключевые слова главы выше начального уровня (особенно для оригиналов). */
    val glossary: List<GlossaryItem> = emptyList(),
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
data class GlossaryItem(
    /** Начальная форма */
    val l: String,
    val ru: String,
    /** Пояснение по-русски (контекст, устаревшее значение и т.п.) */
    val note: String = "",
    /** Форма, в которой слово встречается в тексте */
    val form: String = "",
)

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
    /** "rare" — редкое/книжное, "old" — устаревшее; такие слова не предлагаем учить. */
    val r: String? = null,
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
