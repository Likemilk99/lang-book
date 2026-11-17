package app.lectura.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectura.LecturaApp
import app.lectura.data.BookInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val app = LecturaApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by app.books.books.collectAsStateWithLifecycle()
    val progress by app.db.progress().all().collectAsState(initial = emptyList())
    var toDelete by remember { mutableStateOf<BookInfo?>(null) }
    var showData by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { app.books.refresh() } }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.books.import(uri) }
                .onSuccess { Toast.makeText(ctx, "Добавлено: $it", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Не получилось: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Книги") },
                actions = {
                    IconButton(onClick = { showData = true }) { Icon(Icons.Filled.Storage, "Данные и резервная копия") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Импорт") },
            )
        },
    ) { pad ->
        if (books.isEmpty()) {
            Column(Modifier.padding(pad).padding(24.dp)) {
                Text("Пока нет книг", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Подготовьте книгу на Mac:\npython3 pipeline/prepare_book.py --topic \"…\" --push\n\n" +
                        "или нажмите «Импорт» и выберите .json файл.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatsCard() }
            items(books, key = { it.id }) { b ->
                val p = progress.firstOrNull { it.bookId == b.id }
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onOpen(b.id) },
                        onLongClick = { toDelete = b },
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val meta = listOf(b.author, b.level, "${b.chapters} гл.").filter { it.isNotBlank() }
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                        if (p != null) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { (p.chapter + 1f) / b.chapters.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Глава ${p.chapter + 1} из ${b.chapters}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showData) DataDialog(onDismiss = { showData = false })

    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить «${b.title}»?") },
            text = { Text("Файл книги удалится с телефона. Сохранённые слова останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        app.books.delete(b)
                        app.db.progress().delete(b.id)
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
