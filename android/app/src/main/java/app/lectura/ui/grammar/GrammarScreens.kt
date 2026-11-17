package app.lectura.ui.grammar

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

// Вкладка «Грамматика». Реализация разнесена по файлам пакета:
//  GrammarListScreen.kt — список тем, GrammarTopicContent.kt — страница темы,
//  GrammarPracticeContent.kt + GrammarPracticeVm.kt — сессия, GrammarLogic.kt — подбор и проверка.

/** Список тем: выбор уровня A1–B2, темы со статусом освоения. */
@Composable
fun GrammarScreen(onTopic: (String) -> Unit) {
    GrammarListContent(onTopic)
}

/**
 * Подробное объяснение темы + статистика + кнопка «Практиковать».
 * [onOpenTopic] — переход к связанной теме через навигацию; если не передан (null), связанные темы
 * и их практика открываются внутри этого экрана (системная «Назад» возвращает к предыдущей теме).
 */
@Composable
fun GrammarTopicScreen(
    topicId: String,
    onBack: () -> Unit,
    onPractice: () -> Unit,
    onOpenTopic: ((String) -> Unit)? = null,
) {
    // Внутренний стек открытых поверх темы экранов. Корень — topicId из навигации.
    val stack = remember(topicId) { mutableStateListOf<InnerEntry>() }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val top = stack.lastOrNull()
    when {
        top == null -> GrammarTopicContent(
            topicId = topicId,
            onBack = onBack,
            onPractice = onPractice,
            onOpenTopic = { id -> if (onOpenTopic != null) onOpenTopic(id) else stack.add(InnerEntry(id, practice = false)) },
        )
        top.practice -> GrammarPracticeContent(
            topicId = top.id,
            onBack = { stack.removeAt(stack.lastIndex) },
            vmKey = "grammar-practice:${top.id}:${top.nonce}",
        )
        else -> GrammarTopicContent(
            topicId = top.id,
            onBack = { stack.removeAt(stack.lastIndex) },
            onPractice = { stack.add(InnerEntry(top.id, practice = true)) },
            onOpenTopic = { id -> stack.add(InnerEntry(id, practice = false)) },
        )
    }
}

/** Экран внутри страницы темы; nonce — чтобы каждая открытая практика получала свой ViewModel. */
private data class InnerEntry(val id: String, val practice: Boolean, val nonce: Long = System.nanoTime())

/** Сессия упражнений по теме. */
@Composable
fun GrammarPracticeScreen(topicId: String, onBack: () -> Unit) {
    GrammarPracticeContent(topicId, onBack)
}
