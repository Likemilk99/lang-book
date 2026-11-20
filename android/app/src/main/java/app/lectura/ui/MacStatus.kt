package app.lectura.ui

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectura.LecturaApp
import app.lectura.data.MacLink
import app.lectura.data.MacLink.Status
import app.lectura.data.PersonalRepository

/** Держит связь с Mac, пока экран виден, и сообщает о пришедших переводах. */
@Composable
fun MacLinkWhileVisible() {
    val mac = LecturaApp.instance.mac
    val ctx = LocalContext.current
    LifecycleResumeEffect(Unit) {
        mac.acquire()
        onPauseOrDispose { mac.release() }
    }
    LaunchedEffect(Unit) {
        mac.arrived.collect { n -> Toast.makeText(ctx, "Пришли переводы с Mac: +$n фраз в повторение", Toast.LENGTH_LONG).show() }
    }
}

private val Green = Color(0xFF43A047)
private val Yellow = Color(0xFFFFB300)
private val Red = Color(0xFFE53935)
private val Grey = Color(0xFF9E9E9E)

/** Кружок: зелёный — Mac на связи, жёлтый — переводит, красный — не найден или нужен код, серый — ищу. */
@Composable
fun MacStatusDot(status: Status, size: Dp = 10.dp) {
    val color = when (status) {
        Status.IDLE -> Green
        Status.BUSY -> Yellow
        Status.OFFLINE, Status.NEED_CODE -> Red
        Status.SEARCHING -> Grey
    }
    val pulse = if (status == Status.BUSY || status == Status.SEARCHING) {
        rememberInfiniteTransition(label = "mac").animateFloat(
            1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
        ).value
    } else 1f
    Box(Modifier.size(size).alpha(pulse).background(color, CircleShape))
}

/** Короткая подпись к кружку. */
fun macStatusText(s: MacLink.State, pending: Int = 0): String = when (s.status) {
    Status.SEARCHING -> "Ищу Mac…"
    Status.OFFLINE -> "Mac не найден" + if (pending > 0) " · ждут перевода: $pending" else ""
    Status.NEED_CODE -> if (s.wrongCode) "Mac не принял код" else "Mac найден — нужен код"
    Status.IDLE -> "Mac на связи" + if (pending > 0) " · ждут: $pending" else ""
    Status.BUSY -> when (s.phase) {
        "готовлю голос" -> "Mac готовится"
        "озвучиваю" -> "Mac озвучивает"
        else -> "Mac переводит"
    } + if (s.queue > 0) " · в работе: ${s.queue}" else ""
}

/** Кружок с подписью; по нажатию — подробности и код. */
@Composable
fun MacStatusRow(pending: Int, modifier: Modifier = Modifier, clickable: Boolean = true) {
    val s by LecturaApp.instance.mac.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier.then(if (clickable) Modifier.clickable { open = true } else Modifier).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MacStatusDot(s.status)
        Text(macStatusText(s, pending), style = MaterialTheme.typography.labelLarge)
    }
    if (open) MacDialog(s) { open = false }
}

@Composable
private fun MacDialog(s: MacLink.State, onClose: () -> Unit) {
    val app = LecturaApp.instance
    var code by remember { mutableStateOf(app.prefs.macCode) }
    var address by remember { mutableStateOf(app.prefs.macAddress) }
    val addressOk = address.isBlank() || MacLink.parseAddress(address) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MacStatusDot(s.status, 14.dp)
                Text("Mac для «Моих фраз»")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (s.status) {
                        Status.SEARCHING -> "Ищу Mac в сети Wi-Fi…"
                        Status.OFFLINE -> "Mac не найден. Дома, в той же сети Wi-Fi, запустите на Mac в папке проекта:"
                        Status.NEED_CODE -> if (s.wrongCode) "Mac не принял код — введите тот, что показан в окне сервера." else "Mac найден. Введите код из окна сервера на Mac."
                        Status.IDLE -> "Mac на связи (${s.host}). Новые записи сразу уходят на перевод."
                        Status.BUSY -> "Mac работает: ${s.phase.ifBlank { "перевожу" }}" +
                            (if (s.current.isNotBlank()) " — «${s.current}»" else "") +
                            (if (s.queue > 1) ". В очереди ещё ${s.queue - 1}." else ".")
                    },
                )
                if (s.status == Status.OFFLINE || s.status == Status.SEARCHING) {
                    Text(PersonalRepository.MAC_COMMAND, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Записи подождут: переводы придут, когда Mac будет на связи. Без Wi-Fi можно по кабелю: python3 pipeline/personal.py",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.error.isNotBlank()) {
                    Text("Последняя ошибка на Mac: ${s.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Код с Mac") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trim() },
                    label = { Text("Адрес Mac (обычно не нужен)") },
                    placeholder = { Text("192.168.1.5:8765") },
                    supportingText = { Text(if (addressOk) "Пусто — найти автоматически" else "Например: 192.168.1.5:8765") },
                    isError = !addressOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.prefs.macCode = code
                app.prefs.macAddress = address
                app.mac.reconnect()
                onClose()
            }, enabled = addressOk) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}
