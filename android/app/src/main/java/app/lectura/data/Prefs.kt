package app.lectura.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _fontSize = MutableStateFlow(sp.getFloat("fontSize", 21f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()
    fun setFontSize(v: Float) {
        val c = v.coerceIn(14f, 34f)
        _fontSize.value = c
        sp.edit().putFloat("fontSize", c).apply()
    }

    private val _showRu = MutableStateFlow(sp.getBoolean("showRu", false))
    val showRu: StateFlow<Boolean> = _showRu.asStateFlow()
    fun setShowRu(v: Boolean) {
        _showRu.value = v
        sp.edit().putBoolean("showRu", v).apply()
    }

    // Настройки тренировки
    var reviewAutoWord: Boolean
        get() = sp.getBoolean("reviewAutoWord", true)
        set(v) = sp.edit().putBoolean("reviewAutoWord", v).apply()
    /** Грамматика: читать предложение упражнения вслух при показе и правильный ответ после. */
    var grammarAutoSpeak: Boolean
        get() = sp.getBoolean("grammarAutoSpeak", true)
        set(v) = sp.edit().putBoolean("grammarAutoSpeak", v).apply()
    var reviewAutoExample: Boolean
        get() = sp.getBoolean("reviewAutoExample", false)
        set(v) = sp.edit().putBoolean("reviewAutoExample", v).apply()
    /** "es_ru" — испанское слово спереди, "ru_es" — русское спереди, "mixed" — вперемешку. */
    var reviewDirection: String
        get() = sp.getString("reviewDirection", "es_ru") ?: "es_ru"
        set(v) = sp.edit().putString("reviewDirection", v).apply()
    /** "mixed" — смешанные упражнения, "cards" — только карточки. */
    var reviewMode: String
        get() = sp.getString("reviewMode", "mixed") ?: "mixed"
        set(v) = sp.edit().putString("reviewMode", v).apply()

    /** Запись речи заканчивается по кнопке (true) или сама на паузе (false). */
    var speechManualStop: Boolean
        get() = sp.getBoolean("speechManualStop", true)
        set(v) = sp.edit().putBoolean("speechManualStop", v).apply()

    /** Последний выбранный уровень во вкладке «Грамматика» ("" — выбрать автоматически). */
    var grammarLevel: String
        get() = sp.getString("grammarLevel", "") ?: ""
        set(v) = sp.edit().putString("grammarLevel", v).apply()

    var rate: Float
        get() = sp.getFloat("rate", 0.8f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    /** Вечернее напоминание, если за день не было занятий. */
    var reminderEnabled: Boolean
        get() = sp.getBoolean("reminderEnabled", true)
        set(v) = sp.edit().putBoolean("reminderEnabled", v).apply()
    /** Время напоминания: минут от полуночи (по умолчанию 20:00). */
    var reminderMinutes: Int
        get() = sp.getInt("reminderMinutes", 20 * 60)
        set(v) = sp.edit().putInt("reminderMinutes", v.coerceIn(0, 24 * 60 - 1)).apply()

    fun bundledHash(name: String): String? = sp.getString("bundledHash:$name", null)
    fun setBundledHash(name: String, hash: String) = sp.edit().putString("bundledHash:$name", hash).apply()

    fun deletedBundled(): Set<String> = sp.getStringSet("deletedBundled", emptySet()).orEmpty()
    fun markBundledDeleted(name: String) {
        sp.edit().putStringSet("deletedBundled", deletedBundled() + name).apply()
    }
}
