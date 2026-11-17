package app.lectura.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lectura.LecturaApp
import app.lectura.data.AppVersion
import app.lectura.data.DataInfo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DataDialog(onDismiss: () -> Unit) {
    val app = LecturaApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<DataInfo?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { info = app.userData.info() }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.userData.exportTo(uri) }
                .onSuccess { Toast.makeText(ctx, "Сохранено слов: $it", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.userData.importFrom(uri) }
                .onSuccess {
                    Toast.makeText(ctx, "Из копии ${it.fromVersion}: слов +${it.words}, книг ${it.books}", Toast.LENGTH_LONG).show()
                    reload++
                }
                .onFailure { Toast.makeText(ctx, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Данные") },
        text = {
            Column {
                info?.let { i ->
                    val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
                    Text("Версия приложения: ${i.appVersion}")
                    Text("Первая установка: ${AppVersion.nameOf(i.firstVersion)}")
                    Text(
                        "Последнее сохранение: " +
                            if (i.lastSaveAt > 0) "${AppVersion.nameOf(i.lastSaveVersion)} · ${fmt.format(Date(i.lastSaveAt))}" else "—",
                    )
                    Text("Слов в словаре: ${i.words} · книг с прогрессом: ${i.books}")
                    Text(
                        "Схема БД: v${i.dbVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Прогресс сохраняется при обновлении приложения автоматически. " +
                        "Резервная копия нужна, если приложение придётся удалить.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        exporter.launch("lectura-backup-$date.json")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сохранить резервную копию") }
                OutlinedButton(
                    onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Восстановить из копии") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
