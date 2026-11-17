package app.lectura.model

import kotlinx.serialization.Serializable

// КОНТРАКТ учебной программы (по структуре Plan Curricular del Instituto Cervantes / CEFR).
// Файлы: assets/curriculum/vocab_<LEVEL>.json и assets/curriculum/grammar_<LEVEL>.json, LEVEL = A1|A2|B1|B2.
// Списки и объяснения — собственные (сгенерированы конвейером pipeline/curriculum.py), не копия PCIC.
// Совместимость: новые поля — только с значениями по умолчанию; id тем и уровней не менять.

val CURRICULUM_LEVELS = listOf("A1", "A2", "B1", "B2")

/** Словарь уровня, сгруппированный по темам (аналог «nociones específicas»). */
@Serializable
data class VocabList(
    val level: String,
    val themes: List<VocabTheme>,
)

@Serializable
data class VocabTheme(
    /** латиница, стабильный id: "food", "home", "travel"… */
    val id: String,
    /** название по-русски */
    val title: String,
    val words: List<VocabEntry>,
)

@Serializable
data class VocabEntry(
    /** Начальная форма (лемма) на испанском, нижний регистр, без артикля: "casa", "tener", "bueno". */
    val l: String,
    val ru: String,
    /** noun | verb | adj | adv | prep | pron | det | conj | num | interj | expr */
    val pos: String = "",
    /** Для существительных: "m" / "f" */
    val g: String = "",
)

/** Грамматика уровня. */
@Serializable
data class GrammarLevel(
    val level: String,
    val topics: List<GrammarTopic>,
)

@Serializable
data class GrammarTopic(
    /** стабильный id: "a1-ser-estar" */
    val id: String,
    /** название по-русски */
    val title: String,
    /** название по-испански (как в учебниках) */
    val titleEs: String = "",
    /** 1–2 предложения по-русски: о чём тема и зачем она */
    val summary: String,
    /** Подробное объяснение по разделам */
    val sections: List<GrammarSection>,
    val examples: List<GrammarExample> = emptyList(),
    /** Типичные ошибки русскоязычных */
    val mistakes: List<GrammarMistake> = emptyList(),
    /** Банк упражнений (из него каждый раз берётся подборка) */
    val exercises: List<GrammarExercise> = emptyList(),
    /** id связанных тем */
    val related: List<String> = emptyList(),
)

@Serializable
data class GrammarSection(
    val title: String,
    /** Текст по-русски. Абзацы через пустую строку; **жирный** допускается. */
    val text: String,
    /** Необязательная таблица (первая строка — заголовок), например спряжение. */
    val table: List<List<String>> = emptyList(),
)

@Serializable
data class GrammarExample(
    val es: String,
    val ru: String,
    val note: String = "",
)

@Serializable
data class GrammarMistake(
    val wrong: String,
    val right: String,
    val why: String,
)

@Serializable
data class GrammarExercise(
    /**
     * choice    — sentence с пропуском "___", выбрать из options;
     * input     — sentence с пропуском "___", вписать (answer + accept);
     * order     — собрать предложение: answer — правильное предложение, слова перемешиваются в приложении;
     * transform — prompt: что сделать (по-русски), sentence: исходное предложение, answer: результат.
     */
    val type: String,
    val sentence: String,
    val answer: String,
    val options: List<String> = emptyList(),
    /** Другие правильные варианты ответа */
    val accept: List<String> = emptyList(),
    /** Подсказка/инструкция по-русски, например "(ser / estar)" или "Поставьте в прошедшее время" */
    val prompt: String = "",
    /** Перевод предложения на русский */
    val ru: String = "",
    /** Почему так (по-русски) — показывается после ответа */
    val explain: String = "",
    /** Леммы содержательных слов предложения — для подбора упражнений под словарь пользователя */
    val lemmas: List<String> = emptyList(),
)
