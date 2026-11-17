package app.lectura.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Повторение за диктором и ролевая игра по главе. Реализация: агент «speech».
// mode: "shadow" — повторять каждое предложение; "role" — произносить реплики выбранного персонажа.
@Composable
fun ShadowingScreen(bookId: String, chapter: Int, mode: String, onBack: () -> Unit) {
    Text("Shadowing: $bookId / $chapter / $mode")
}
