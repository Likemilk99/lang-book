package app.lectura.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectura.LecturaApp
import app.lectura.images.WordImageState
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Картинка к слову (Pixabay, скачана на устройство). Пока ищется — серый прямоугольник, если нет — нейтральная
 * заглушка; карточка работает в любом случае. Долгое нажатие — «Другая картинка» / «Убрать картинку»,
 * подпись «Pixabay · автор» открывает страницу картинки (атрибуция по правилам Pixabay).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordImageView(word: String, translation: String?, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val repo = LecturaApp.instance.wordImages
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by remember(word) { repo.image(word, translation) }
        .collectAsStateWithLifecycle(initialValue = WordImageState.Loading)
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    if (state is WordImageState.Hidden) return

    val shape = RoundedCornerShape(12.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(height).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is WordImageState.Ready -> AsyncImage(
                    model = s.file,
                    contentDescription = word,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(height),
                )
                is WordImageState.Error -> Placeholder(s.message)
                WordImageState.NotFound -> Placeholder(null)
                else -> Unit // Loading: просто серый фон
            }
            if (busy) Text("…", style = MaterialTheme.typography.titleLarge)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Другая картинка") }, onClick = {
                    menu = false
                    busy = true
                    scope.launch {
                        repo.next(word, translation)
                        busy = false
                    }
                })
                DropdownMenuItem(text = { Text("Убрать картинку") }, onClick = {
                    menu = false
                    scope.launch { repo.disable(word) }
                })
            }
        }
        (state as? WordImageState.Ready)?.let { s ->
            val label = "Pixabay" + (s.author?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp).clickable(enabled = !s.pageUrl.isNullOrBlank()) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.pageUrl))) }
                },
            )
        }
    }
}

@Composable
private fun Placeholder(message: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
        Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(36.dp))
        if (message != null) Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}
