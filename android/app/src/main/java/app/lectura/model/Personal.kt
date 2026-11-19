package app.lectura.model

import kotlinx.serialization.Serializable

/**
 * «Мои фразы» — обмен с pipeline/personal.py через files/personal/ на телефоне:
 * приложение пишет inbox.json (ждущие перевода), Mac возвращает personal.json + audio/<имя>.m4a.
 */
@Serializable
data class InboxFile(val items: List<InboxEntry>)

@Serializable
data class InboxEntry(
    val id: Long,
    /** "about" — ответ на вопрос «Обо мне», "phrase" — «как сказать…». */
    val kind: String,
    /** id вопроса «Обо мне» ("" для «Входящих»). */
    val ref: String = "",
    /** Вопрос по-испански (для «Обо мне»). */
    val question: String = "",
    /** Что написал пользователь, по-русски. */
    val ru: String,
)

@Serializable
data class PersonalFile(val items: List<PersonalResult> = emptyList())

@Serializable
data class PersonalResult(
    val inboxId: Long,
    val kind: String,
    val ref: String = "",
    /** Исходный текст записи — результат относится к записи, только если и id, и текст совпали. */
    val ru: String = "",
    /** Готовые фразы (id "me-<inboxId>-<n>"), озвучка — audio/<id>.m4a. */
    val phrases: List<Phrase> = emptyList(),
    /** «Обо мне»: вопрос по-испански (озвучка audio/q-<ref>.m4a) и допустимые устные ответы. */
    val question: String = "",
    val accepted: List<String> = emptyList(),
) {
    /** Это перевод именно этой записи (id мог повториться после переустановки приложения). */
    fun matches(id: Long, text: String): Boolean = inboxId == id && (ru.isEmpty() || ru == text)
}

/** Вопрос «Обо мне». */
data class AboutQuestion(val id: String, val ru: String, val es: String, val example: String)

/** Вопросы, которые чаще всего задают при знакомстве и в делах; ответы пишутся по-русски. */
val ABOUT_QUESTIONS = listOf(
    AboutQuestion("name", "Как вас зовут?", "¿Cómo te llamas?", "Меня зовут …"),
    AboutQuestion("from", "Откуда вы?", "¿De dónde eres?", "Я из России, из …"),
    AboutQuestion("live", "Где вы живёте?", "¿Dónde vives?", "Я живу в … , в районе …"),
    AboutQuestion("time_spain", "Сколько вы уже в Испании?", "¿Cuánto tiempo llevas en España?", "Я здесь … месяцев"),
    AboutQuestion("why_spain", "Почему вы в Испании?", "¿Por qué estás en España?", "Я переехал, потому что …"),
    AboutQuestion("job", "Чем вы занимаетесь?", "¿A qué te dedicas?", "Я работаю … / Я учусь …"),
    AboutQuestion("work_where", "Где вы работаете или учитесь?", "¿Dónde trabajas o estudias?", "Я работаю из дома / в компании …"),
    AboutQuestion("family", "Расскажите о своей семье", "Háblame de tu familia.", "У меня есть жена / сын …"),
    AboutQuestion("live_with", "С кем вы живёте?", "¿Con quién vives?", "Я живу с …"),
    AboutQuestion("age", "Сколько вам лет?", "¿Cuántos años tienes?", "Мне … лет"),
    AboutQuestion("languages", "Какие языки вы знаете?", "¿Qué idiomas hablas?", "Русский, немного английский …"),
    AboutQuestion("why_spanish", "Зачем вы учите испанский?", "¿Por qué aprendes español?", "Чтобы говорить с людьми, решать дела …"),
    AboutQuestion("hobbies", "Что вы любите делать в свободное время?", "¿Qué te gusta hacer en tu tiempo libre?", "Я люблю …"),
    AboutQuestion("weekend", "Что вы обычно делаете в выходные?", "¿Qué haces los fines de semana?", "Гуляю, хожу на пляж …"),
    AboutQuestion("food", "Что вы любите есть?", "¿Qué te gusta comer?", "Мне нравится …"),
    AboutQuestion("spain_like", "Что вам нравится в Испании?", "¿Qué te gusta de España?", "Мне нравится погода, люди …"),
    AboutQuestion("spain_hard", "Что для вас самое трудное здесь?", "¿Qué es lo más difícil para ti aquí?", "Язык, документы …"),
    AboutQuestion("health", "Есть ли у вас аллергия или болезни, о которых важно знать?", "¿Tienes alguna alergia o enfermedad?", "У меня аллергия на … / Нет"),
    AboutQuestion("plans", "Какие у вас планы на будущее?", "¿Qué planes tienes?", "Хочу остаться в Испании …"),
)
