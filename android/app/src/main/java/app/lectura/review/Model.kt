package app.lectura.review

import app.lectura.data.SavedWord
import app.lectura.data.Srs
import app.lectura.model.ReplyItem

enum class Direction(val key: String, val label: String) {
    ES_RU("es_ru", "Испанский → русский"),
    RU_ES("ru_es", "Русский → испанский"),
    MIXED("mixed", "Вперемешку"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: ES_RU
    }
}

enum class ReviewMode(val key: String, val label: String) {
    MIXED("mixed", "Смешанные упражнения (рекомендуется)"),
    CARDS("cards", "Только карточки"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: MIXED
    }
}

enum class ExerciseType(val label: String) {
    FLASH("Карточки"),
    SAY_ES("Сказать вслух"),
    CLOZE_CHOICE("Пропуск: выбор"),
    CLOZE_INPUT("Пропуск: ввод"),
    LISTEN_CHOICE("На слух"),
    BUILD_SENTENCE("Собрать фразу"),
    DIALOG_REPLY("Ответ в диалоге"),
    LISTEN_REPEAT("Послушать и повторить"),
    PATTERN_SAY("Сказать по шаблону"),
}

/** Результат автоматически проверяемого упражнения. */
enum class Outcome(val grade: Int, val stageDelta: Int, val correct: Boolean) {
    /** Верно с первой попытки. */
    CORRECT(Srs.GOOD, +1, true),

    /** Верно после подсказки / повтора или с ошибкой в ударении. */
    HARD(Srs.HARD, 0, true),

    /** Неверно или «Не знаю» — слово вернётся позже в этой же сессии. */
    WRONG(Srs.AGAIN, -1, false),
}

data class Answer(val outcome: Outcome, val note: String? = null)

/** Пропуск в предложении: before + answer + after == sentence. */
data class Cloze(val before: String, val answer: String, val after: String)

sealed interface Exercise {
    val type: ExerciseType

    /** Правильный ответ на испанском (для обратной связи). */
    val answerEs: String

    /** Перевод / пояснение к ответу. */
    val answerRu: String

    sealed interface ForWord : Exercise {
        val word: SavedWord
        override val answerRu: String get() = word.ru
    }

    data class Flash(override val word: SavedWord, val spanishFront: Boolean) : ForWord {
        override val type get() = ExerciseType.FLASH
        override val answerEs get() = word.word
    }

    data class SayEs(override val word: SavedWord) : ForWord {
        override val type get() = ExerciseType.SAY_ES
        override val answerEs get() = word.word
    }

    /** Послушать диктора и повторить вслух (испанский виден) — первое знакомство с произношением. */
    data class ListenRepeat(override val word: SavedWord) : ForWord {
        override val type get() = ExerciseType.LISTEN_REPEAT
        override val answerEs get() = word.word
    }

    /**
     * Шаблон фразы из разговорника с другой подстановкой: «Quería ___.» + «Я бы хотел бутылку воды.» →
     * сказать «Quería una botella de agua.». [variant] — номер варианта (для озвучки).
     */
    data class PatternSay(
        override val word: SavedWord,
        val pattern: String,
        val variant: Int,
        val variantEs: String,
        val variantRu: String,
    ) : ForWord {
        override val type get() = ExerciseType.PATTERN_SAY
        override val answerEs get() = variantEs
        override val answerRu get() = variantRu
    }

    data class ClozeChoice(override val word: SavedWord, val cloze: Cloze, val options: List<String>) : ForWord {
        override val type get() = ExerciseType.CLOZE_CHOICE
        override val answerEs get() = cloze.answer
        val answerIndex: Int get() = options.indexOf(cloze.answer)
    }

    data class ClozeInput(override val word: SavedWord, val cloze: Cloze) : ForWord {
        override val type get() = ExerciseType.CLOZE_INPUT
        override val answerEs get() = cloze.answer
    }

    data class ListenChoice(override val word: SavedWord, val options: List<String>) : ForWord {
        override val type get() = ExerciseType.LISTEN_CHOICE
        override val answerEs get() = word.word
        val answerIndex: Int get() = options.indexOf(word.ru)
    }

    /** tiles — перемешанные слова предложения, target — слова в правильном порядке. */
    data class BuildSentence(override val word: SavedWord, val target: List<String>, val tiles: List<String>) : ForWord {
        override val type get() = ExerciseType.BUILD_SENTENCE
        override val answerEs get() = word.sentence
        override val answerRu get() = word.sentenceRu
    }

    data class DialogReply(val reply: ReplyItem, val options: List<String>, val answerIndex: Int) : Exercise {
        override val type get() = ExerciseType.DIALOG_REPLY
        override val answerEs get() = options[answerIndex]
        override val answerRu get() = reply.why
    }
}

/** Элемент очереди сессии: тип упражнения выбирается, когда элемент доходит до показа. */
sealed interface Entry {
    /** avoid — тип, на котором была ошибка (повтор даём другим упражнением). */
    data class Word(val word: SavedWord, val avoid: ExerciseType? = null) : Entry
    data class Dialog(val reply: ReplyItem) : Entry
}

data class Task(val id: Int, val entry: Entry, val exercise: Exercise)

data class ExerciseResult(val type: ExerciseType, val correct: Boolean)
