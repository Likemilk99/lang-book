package app.lectura.ui.grammar

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Вкладка «Грамматика». Реализация: агент «grammar-ui».

/** Список тем: выбор уровня A1–B2, темы со статусом освоения. */
@Composable
fun GrammarScreen(onTopic: (String) -> Unit) {
    Text("Грамматика")
}

/** Подробное объяснение темы + статистика + кнопка «Практиковать». */
@Composable
fun GrammarTopicScreen(topicId: String, onBack: () -> Unit, onPractice: () -> Unit) {
    Text("Тема $topicId")
}

/** Сессия упражнений по теме. */
@Composable
fun GrammarPracticeScreen(topicId: String, onBack: () -> Unit) {
    Text("Практика $topicId")
}
