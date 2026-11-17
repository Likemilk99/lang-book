package app.lectura.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Вопросы на понимание главы (Chapter.questions). Реализация: агент «exercises».
@Composable
fun QuestionsScreen(bookId: String, chapter: Int, onBack: () -> Unit) {
    Text("Вопросы: $bookId / $chapter")
}
