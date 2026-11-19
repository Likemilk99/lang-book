package app.lectura.model

import kotlinx.serialization.Serializable

/**
 * Сценарии: жизненная ситуация целиком (assets/scenarios/scenarios.json, генерирует pipeline/scenarios.py).
 * Озвучка (pipeline/audio.py --scenarios): реплики — assets/scenarios/audio/<id>_<диалог>_<n>.m4a,
 * «понять ответ» — <id>_u<n>.m4a, вопросы — <id>_q<n>.m4a, образец ответа — <id>_q<n>a.m4a;
 * ключевые фразы — как у разговорника (assets/phrasebook/audio/<phraseId>.m4a).
 */
@Serializable
data class ScenarioFile(val scenarios: List<Scenario>)

@Serializable
data class Scenario(
    val id: String,
    /** "A0", "A1" */
    val level: String,
    val emoji: String = "",
    val title: String,
    /** Где вы и что нужно (по-русски). */
    val situation: String,
    /** «Могу …» — цель сценария. */
    val canDo: String,
    val them: ScenarioRole,
    val phrases: List<Phrase>,
    /** Два диалога: «a» — обычный, «b» — с неудобным поворотом. */
    val dialogs: List<ScenarioDialog>,
    val understand: List<UnderstandItem> = emptyList(),
    val prompts: List<AnswerPrompt> = emptyList(),
)

@Serializable
data class ScenarioRole(val role: String, val gender: String = "")

@Serializable
data class ScenarioDialog(val id: String, val title: String, val lines: List<ScenarioLine>)

@Serializable
data class ScenarioLine(
    /** "me" — вы, "them" — собеседник. */
    val who: String,
    val es: String,
    val ru: String,
) {
    val isMe: Boolean get() = who == ME

    companion object {
        const val ME = "me"
        const val THEM = "them"
    }
}

/** «Понять ответ»: что говорит собеседник (на слух) → что это значит / что делать. */
@Serializable
data class UnderstandItem(
    val es: String,
    val ru: String,
    val question: String,
    val options: List<String>,
    val answer: Int,
    val why: String = "",
)

/** Вопрос собеседника, на который отвечаете своими словами; [accepted] — допустимые ответы. */
@Serializable
data class AnswerPrompt(
    val es: String,
    val ru: String,
    val hint: String = "",
    val accepted: List<String>,
)
