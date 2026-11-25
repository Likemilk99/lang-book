package app.lectura.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectura.LecturaApp
import app.lectura.review.FinishedView
import app.lectura.review.ReviewViewModel
import app.lectura.review.SessionView
import app.lectura.review.SetupPanel

/** «Повторение»: смешанные упражнения с лестницей сложности (логика — в пакете app.lectura.review). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen() {
    val app = LecturaApp.instance
    val vm: ReviewViewModel = viewModel { ReviewViewModel(app) }
    // Незаконченную сессию при возврате на вкладку не сбрасываем.
    LaunchedEffect(Unit) { if (!vm.started || vm.current == null) vm.load() }
    DisposableEffect(Unit) { onDispose { app.player.stopSpeech() } }
    val task = vm.current
    LaunchedEffect(task?.id) { task?.let { vm.onAppear(it) } }
    val inSession = vm.started && task != null
    BackHandler(enabled = vm.practiceSetup && !inSession) { vm.closePracticeSetup() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val name = if ((vm.practice && inSession) || (vm.practiceSetup && !inSession)) "Практика" else "Повторение"
                        Text(if (inSession) "$name · осталось ${vm.remaining}" else name)
                    },
                )
                if (inSession) {
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !vm.loaded -> Unit
                task != null && vm.started -> SessionView(vm, task)
                vm.practiceSetup -> SetupPanel(vm, practice = true)
                vm.started || vm.due.isEmpty() -> FinishedView(vm, onAgain = { vm.load() })
                else -> SetupPanel(vm)
            }
        }
    }
}
