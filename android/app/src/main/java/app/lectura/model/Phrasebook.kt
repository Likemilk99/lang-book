package app.lectura.model

import kotlinx.serialization.Serializable

/**
 * Разговорник: готовые фразы для устного общения по темам.
 * Файлы: assets/phrasebook/phrasebook_<L>.json (генерирует pipeline/phrasebook.py),
 * озвучка: assets/phrasebook/audio/<id>.m4a и <id>_<k>.m4a — k-й вариант шаблона.
 */
@Serializable
data class Phrasebook(
    /** "A0", "A1" */
    val level: String,
    val title: String,
    val intro: String = "",
    val themes: List<PhraseTheme>,
)

@Serializable
data class PhraseTheme(
    /** стабильный id: "a0-control" */
    val id: String,
    val emoji: String = "",
    val title: String,
    val phrases: List<Phrase>,
)

@Serializable
data class Phrase(
    /** стабильный id: "a0-control-03" (по нему фраза связана со словарём и озвучкой) */
    val id: String,
    val es: String,
    val ru: String,
    val note: String = "",
    /** Шаблон с одним пропуском «___» ("" — шаблона нет). */
    val pattern: String = "",
    /** Варианты подстановки; первый — сама фраза. */
    val slots: List<PhraseSlot> = emptyList(),
) {
    val hasPattern: Boolean get() = pattern.contains(GAP) && slots.size >= 2

    companion object {
        const val GAP = "___"
    }
}

@Serializable
data class PhraseSlot(
    /** Что подставляется в пропуск. */
    val fill: String,
    /** Вся фраза с подстановкой (== pattern с fill вместо ___). */
    val es: String,
    val ru: String,
)
