package app.lectura.speech

import android.content.Context
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// КОНТРАКТ модуля речи. Сигнатуры менять нельзя — на них опираются упражнения.
// Реализация: агент «speech».

/** Распознавание испанской речи (es-ES), по возможности на устройстве (офлайн). */
class SpeechInput(private val context: Context) {
    sealed interface Event {
        data object Ready : Event
        data class Partial(val text: String) : Event
        data class Final(val alternatives: List<String>) : Event
        data class Error(val message: String) : Event
    }

    fun isAvailable(): Boolean = false

    /** Вызывать на главном потоке. Требует разрешения RECORD_AUDIO. */
    fun start(onEvent: (Event) -> Unit) {
        onEvent(Event.Error("not implemented"))
    }

    fun stop() {}
    fun destroy() {}
}

data class WordMatch(val word: String, val matched: Boolean)

data class MatchResult(
    /** Доля совпавших слов 0..1 */
    val score: Float,
    /** Ожидаемые слова с отметкой, совпали ли они */
    val words: List<WordMatch>,
    /** Что распознано (лучший вариант) */
    val heard: String,
) {
    val passed: Boolean get() = score >= PASS_SCORE

    companion object {
        const val PASS_SCORE = 0.8f
    }
}

object SpeechMatch {
    /** Сравнение без учёта регистра, ударений и пунктуации; берётся лучший из вариантов. */
    fun compare(expected: String, heardAlternatives: List<String>): MatchResult =
        MatchResult(0f, expected.split(" ").map { WordMatch(it, false) }, heardAlternatives.firstOrNull().orEmpty())
}

/**
 * Готовый элемент UI: кнопка микрофона, индикатор прослушивания, подсветка совпавших слов.
 * Сам запрашивает RECORD_AUDIO. onResult вызывается после окончательного распознавания.
 */
@Composable
fun SpeakButton(expected: String, modifier: Modifier = Modifier, onResult: (MatchResult) -> Unit) {
    OutlinedButton(onClick = { onResult(SpeechMatch.compare(expected, emptyList())) }, modifier = modifier) {
        Text("🎤 Сказать")
    }
}
