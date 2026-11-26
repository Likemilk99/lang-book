package app.lectura.model

import kotlinx.serialization.Serializable

/**
 * «Живой разговор»: тема — это сцена, роль собеседника и цели. Сам разговор ведёт Mac
 * (pipeline/chat.py через pipeline/server.py); телефон присылает тему целиком, поэтому новые темы
 * добавляются только здесь.
 */
@Serializable
data class ChatGoal(val id: String, val ru: String)

@Serializable
data class ChatTopic(
    /** Стабильный id (хранится в Prefs как последняя тема). */
    val id: String,
    val emoji: String = "",
    /** Название по-русски. */
    val title: String,
    /** Сцена для модели (можно по-русски): где, когда, что происходит. */
    val scene: String,
    /** Кого играет собеседник. */
    val role: String,
    /** "m" / "f" — голос собеседника. */
    val gender: String = "f",
    /** Обращение на «usted» (иначе «tú», как обычно в Испании). */
    val usted: Boolean = false,
    val goals: List<ChatGoal> = emptyList(),
    /** Темы словаря уровня (VocabTheme.id) для подсказки «Слова темы». */
    val vocab: List<String> = emptyList(),
)

/** Как звучат реплики собеседника. */
enum class ChatVoice(val key: String, val title: String) {
    NONE("none", "Без звука"),
    PHONE("phone", "Голос телефона"),
    MAC("mac", "Голос Mac");

    companion object {
        fun of(key: String): ChatVoice = entries.firstOrNull { it.key == key } ?: PHONE
    }
}

val CHAT_LEVELS = listOf("A0", "A1", "A2", "B1")

/** Пара «испанский — русский» (подсказки, исправления, незнакомые слова). */
@Serializable
data class ChatPair(val es: String, val ru: String = "")

/** An unknown word of a partner's reply: dictionary form, its form in the reply, dictionary translation. */
@Serializable
data class ChatWord(val es: String, val ru: String = "", val form: String = "")

/** ❓ answer. «how»: ways to say it, a note, an example for this moment. «word»: a tapped word explained. */
@Serializable
data class AskAnswer(
    val options: List<ChatPair> = emptyList(),
    val note_ru: String = "",
    val example: ChatPair? = null,
    val word: WordInfo? = null,
)

/** A tapped word: dictionary form and translation, its meaning in this line, a short grammar note. */
@Serializable
data class WordInfo(val es: String, val ru: String = "", val form_ru: String = "", val note_ru: String = "")

/** A word of the pre-conversation brief; tag: "review" (the learner is forgetting it), "new" or "". */
@Serializable
data class BriefWord(val es: String, val ru: String = "", val tag: String = "")

/** «Перед разговором»: key words of the scene and reusable patterns («Me duele…»), picked by the Mac. */
@Serializable
data class ChatBrief(val words: List<BriefWord> = emptyList(), val phrases: List<ChatPair> = emptyList())

/** Ответ сервера на реплику (POST /chat/start и /chat/<id>/say). */
@Serializable
data class ChatReply(
    val chat: String = "",
    val turn: Int = 0,
    val reply: String = "",
    val ru: String = "",
    /** Как правильно сказать последнюю реплику ученика (null — всё хорошо). */
    val fix: ChatPair? = null,
    /** Single words for the next answer (hint step 1). */
    val hints: List<ChatPair> = emptyList(),
    /** Phrase pieces of 2–4 words (hint step 2); empty from an older server. */
    val chunks: List<ChatPair> = emptyList(),
    val sample: ChatPair? = null,
    val words: List<ChatWord> = emptyList(),
    val goals_done: List<String> = emptyList(),
    val end: Boolean = false,
    /** Cheat sheet before the conversation: only in the first turn of a newer Mac server. */
    val brief: ChatBrief? = null,
    /** Сколько предложений в реплике (столько клипов озвучки придёт с голосом Mac). */
    val sentences: Int = 0,
    /** Mac будет озвучивать эту реплику. */
    val audio: Boolean = false,
    val firstMs: Int = 0,
    val totalMs: Int = 0,
)

/** Свободная тема: сцену пишет сам ученик. */
fun customChatTopic(text: String) = ChatTopic(
    id = "custom",
    emoji = "✍️",
    title = text.trim().take(80),
    scene = text.trim(),
    role = "a person who naturally fits this situation in Spain",
    gender = "f",
)

val CHAT_TOPICS = listOf(
    ChatTopic(
        id = "cafe", emoji = "☕", title = "Бар: завтрак",
        scene = "A small bar-café in Madrid, weekday morning. The learner comes in for breakfast.",
        role = "Javi, the waiter, friendly and quick", gender = "m",
        goals = listOf(
            ChatGoal("drink", "заказать напиток"),
            ChatGoal("food", "заказать что-то поесть"),
            ChatGoal("pay", "спросить, сколько стоит, и заплатить"),
        ),
        vocab = listOf("food", "numbers", "shopping"),
    ),
    ChatTopic(
        id = "neighbour", emoji = "🏠", title = "Новая соседка",
        scene = "The staircase of the learner's apartment building. A neighbour sees the learner for the first time.",
        role = "Carmen, a chatty neighbour from the third floor", gender = "f",
        goals = listOf(
            ChatGoal("name", "представиться"),
            ChatGoal("from", "сказать, откуда ты и сколько живёшь в Испании"),
            ChatGoal("job", "рассказать, чем занимаешься"),
            ChatGoal("bye", "вежливо попрощаться"),
        ),
        vocab = listOf("identity", "family", "home"),
    ),
    ChatTopic(
        id = "market", emoji = "🍅", title = "Рынок: фрукты и овощи",
        scene = "A fruit and vegetable stall at the neighbourhood market.",
        role = "Paco, the greengrocer", gender = "m",
        goals = listOf(
            ChatGoal("ask", "попросить 2–3 продукта с количеством (кило, штуки)"),
            ChatGoal("price", "спросить цену"),
            ChatGoal("pay", "заплатить и попрощаться"),
        ),
        vocab = listOf("food", "numbers", "shopping"),
    ),
    ChatTopic(
        id = "pharmacy", emoji = "💊", title = "Аптека",
        scene = "A pharmacy. The learner does not feel well and needs something without a prescription.",
        role = "the pharmacist", gender = "f", usted = true,
        goals = listOf(
            ChatGoal("symptom", "объяснить, что болит и с какого дня"),
            ChatGoal("how", "спросить, как принимать лекарство"),
            ChatGoal("pay", "заплатить"),
        ),
        vocab = listOf("body-health", "time", "numbers"),
    ),
    ChatTopic(
        id = "doctor", emoji = "🩺", title = "У врача",
        scene = "A consultation at the health centre (centro de salud).",
        role = "the family doctor", gender = "f", usted = true,
        goals = listOf(
            ChatGoal("symptom", "описать симптомы"),
            ChatGoal("since", "сказать, сколько дней это длится"),
            ChatGoal("allergy", "ответить про аллергии и лекарства"),
            ChatGoal("advice", "понять рекомендацию и переспросить, если непонятно"),
        ),
        vocab = listOf("body-health", "time"),
    ),
    ChatTopic(
        id = "flat", emoji = "🔑", title = "Звонок по объявлению о квартире",
        scene = "A phone call about a flat for rent seen on Idealista.",
        role = "the owner of the flat", gender = "m", usted = true,
        goals = listOf(
            ChatGoal("free", "спросить, свободна ли квартира"),
            ChatGoal("price", "узнать цену и что входит (agua, luz, comunidad)"),
            ChatGoal("visit", "договориться о просмотре: день и время"),
        ),
        vocab = listOf("home", "numbers", "time"),
    ),
    ChatTopic(
        id = "repair", emoji = "🔧", title = "Сантехник: течёт кран",
        scene = "The learner calls a plumber because the kitchen tap is leaking.",
        role = "Luis, the plumber", gender = "m",
        goals = listOf(
            ChatGoal("problem", "объяснить проблему"),
            ChatGoal("address", "дать адрес"),
            ChatGoal("when", "договориться о времени"),
            ChatGoal("price", "спросить примерную цену"),
        ),
        vocab = listOf("home", "services", "time"),
    ),
    ChatTopic(
        id = "directions", emoji = "🗺️", title = "Спросить дорогу",
        scene = "A street in the city centre. The learner looks for the nearest metro station.",
        role = "a friendly older lady passing by", gender = "f", usted = true,
        goals = listOf(
            ChatGoal("ask", "вежливо спросить дорогу"),
            ChatGoal("check", "переспросить или уточнить (далеко? налево?)"),
            ChatGoal("thanks", "поблагодарить"),
        ),
        vocab = listOf("city", "travel"),
    ),
    ChatTopic(
        id = "train", emoji = "🚆", title = "Вокзал: билет на поезд",
        scene = "The ticket office at a Renfe train station.",
        role = "the ticket seller", gender = "m", usted = true,
        goals = listOf(
            ChatGoal("ticket", "купить билет: куда, когда, туда-обратно"),
            ChatGoal("time", "узнать время отправления и платформу"),
            ChatGoal("pay", "заплатить"),
        ),
        vocab = listOf("travel", "time", "numbers"),
    ),
    ChatTopic(
        id = "restaurant", emoji = "🍽️", title = "Ресторан: menú del día",
        scene = "A restaurant at lunchtime, menú del día with first course, second course and dessert.",
        role = "Marta, the waitress", gender = "f",
        goals = listOf(
            ChatGoal("first", "выбрать первое и второе"),
            ChatGoal("drink", "выбрать напиток"),
            ChatGoal("bill", "попросить счёт"),
        ),
        vocab = listOf("food", "numbers"),
    ),
    ChatTopic(
        id = "colleague", emoji = "💬", title = "Коллега: выходные",
        scene = "Monday morning, coffee break at work. A colleague asks about the weekend.",
        role = "Lucía, a colleague", gender = "f",
        goals = listOf(
            ChatGoal("weekend", "рассказать, что делал(а) в выходные"),
            ChatGoal("ask", "спросить о её выходных"),
            ChatGoal("plan", "предложить что-то сделать вместе"),
        ),
        vocab = listOf("leisure", "time", "feelings"),
    ),
    ChatTopic(
        id = "meetup", emoji = "📱", title = "Договориться о встрече",
        scene = "A chat with a Spanish friend about meeting this week.",
        role = "Dani, a Spanish friend", gender = "m",
        goals = listOf(
            ChatGoal("propose", "предложить встретиться и что делать"),
            ChatGoal("when", "договориться о дне и времени"),
            ChatGoal("where", "договориться о месте"),
        ),
        vocab = listOf("leisure", "time", "city"),
    ),
    ChatTopic(
        id = "padron", emoji = "🏛️", title = "Ayuntamiento: empadronamiento",
        scene = "The citizen service office of the town hall. The learner wants to register their address (empadronarse).",
        role = "the civil servant at the desk", gender = "m", usted = true,
        goals = listOf(
            ChatGoal("what", "объяснить, что нужно"),
            ChatGoal("docs", "ответить про документы (pasaporte, contrato de alquiler)"),
            ChatGoal("data", "назвать имя, адрес и телефон"),
        ),
        vocab = listOf("services", "identity", "numbers"),
    ),
)
