package app.lectura

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.lectura.ui.BookStatsScreen
import app.lectura.ui.AboutTrainScreen
import app.lectura.ui.HomeScreen
import app.lectura.ui.MyPhrasesScreen
import app.lectura.ui.PhraseThemeScreen
import app.lectura.ui.PhraseTrainScreen
import app.lectura.ui.PhrasebookScreen
import app.lectura.ui.ScenarioScreen
import app.lectura.ui.ScenarioStepScreen
import app.lectura.ui.LibraryScreen
import app.lectura.ui.ProgressScreen
import app.lectura.ui.QuestionsScreen
import app.lectura.ui.ShadowingScreen
import app.lectura.ui.TalkScreen
import app.lectura.ui.ReaderScreen
import app.lectura.ui.ReviewScreen
import app.lectura.ui.VocabScreen
import app.lectura.ui.grammar.GrammarPracticeScreen
import app.lectura.ui.grammar.GrammarScreen
import app.lectura.ui.grammar.GrammarTopicScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// Библиотека и грамматика — разделы главной (с кнопкой «Назад»), а не вкладки.
private val tabs = listOf(
    Tab("home", "Главная", Icons.Filled.Home),
    Tab("talk", "Говорить", Icons.Filled.RecordVoiceOver),
    Tab("review", "Повторение", Icons.Filled.School),
    Tab("vocab", "Словарь", Icons.Filled.Style),
)

/** Переход на вкладку: без накопления стека, с сохранением состояния вкладок. */
private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Маршрут из плана «Сегодня» и плиток главной: вкладка или обычный экран поверх. */
private fun NavHostController.openRoute(route: String) =
    if (tabs.any { it.route == route }) openTab(route) else navigate(route)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { LecturaTheme { AppNav() } }
    }
}

@Composable
fun LecturaTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Сколько слов ждёт повторения — бейдж на вкладке; пересчитываем при каждой смене экрана.
    val now = remember(route) { System.currentTimeMillis() }
    val due by LecturaApp.instance.db.words().dueCount(now).collectAsState(initial = 0)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.openTab(tab.route) },
                            icon = {
                                if (tab.route == "review" && due > 0) {
                                    BadgedBox(badge = { Badge { Text(if (due > 99) "99+" else "$due") } }) { Icon(tab.icon, null) }
                                } else {
                                    Icon(tab.icon, null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") {
                HomeScreen(onRoute = { nav.openRoute(it) }, onProgress = { nav.navigate("progress") })
            }
            composable("talk") { TalkScreen(onRoute = { nav.openRoute(it) }) }
            composable("mine") { MyPhrasesScreen(onBack = { nav.popBackStack() }, onTrain = { nav.navigate("mine/train") }) }
            composable("mine/train") { AboutTrainScreen(onBack = { nav.popBackStack() }) }
            composable("scenario/{id}") { e ->
                val id = e.arguments?.getString("id").orEmpty()
                ScenarioScreen(id, onBack = { nav.popBackStack() }, onStep = { nav.navigate("scenario/$id/${it.key}") })
            }
            composable("scenario/{id}/{step}") { e ->
                ScenarioStepScreen(
                    e.arguments?.getString("id").orEmpty(),
                    e.arguments?.getString("step").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("phrasebook/{level}") { e ->
                val level = e.arguments?.getString("level").orEmpty()
                PhrasebookScreen(level, onBack = { nav.popBackStack() }, onTheme = { nav.navigate("phrasebook/$level/$it") })
            }
            composable("phrasebook/{level}/{theme}") { e ->
                val level = e.arguments?.getString("level").orEmpty()
                val theme = e.arguments?.getString("theme").orEmpty()
                PhraseThemeScreen(
                    level, theme,
                    onBack = { nav.popBackStack() },
                    onTrain = { nav.navigate("phrasebook/$level/$theme/train") },
                )
            }
            composable("phrasebook/{level}/{theme}/train") { e ->
                PhraseTrainScreen(
                    e.arguments?.getString("level").orEmpty(),
                    e.arguments?.getString("theme").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("library") {
                LibraryScreen(
                    onOpen = { nav.navigate("reader/$it") },
                    onBack = { nav.popBackStack() },
                    onBookStats = { nav.navigate("bookstats/$it") },
                )
            }
            composable("progress") { ProgressScreen(onBack = { nav.popBackStack() }) }
            composable("bookstats/{id}") { e ->
                val id = e.arguments?.getString("id").orEmpty()
                val scope = rememberCoroutineScope()
                BookStatsScreen(
                    bookId = id,
                    onBack = { nav.popBackStack() },
                    onOpenChapter = { ch ->
                        scope.launch {
                            val app = LecturaApp.instance
                            val b = app.books.load(id)
                            app.player.open(b, ch, 0)
                            app.player.seekChapter(ch)
                            nav.navigate("reader/$id")
                        }
                    },
                )
            }
            composable("reader/{id}") { e ->
                ReaderScreen(
                    bookId = e.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onNavigate = { nav.navigate(it) },
                )
            }
            composable("questions/{id}/{ch}") { e ->
                QuestionsScreen(
                    bookId = e.arguments?.getString("id").orEmpty(),
                    chapter = e.arguments?.getString("ch")?.toIntOrNull() ?: 0,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("speak/{id}/{ch}/{mode}") { e ->
                ShadowingScreen(
                    bookId = e.arguments?.getString("id").orEmpty(),
                    chapter = e.arguments?.getString("ch")?.toIntOrNull() ?: 0,
                    mode = e.arguments?.getString("mode") ?: "shadow",
                    onBack = { nav.popBackStack() },
                )
            }
            composable("vocab") { VocabScreen(onReview = { nav.openTab("review") }) }
            composable("grammar") {
                GrammarScreen(onTopic = { nav.navigate("grammar/$it") }, onBack = { nav.popBackStack() })
            }
            composable("grammar/{id}") { e ->
                val id = e.arguments?.getString("id").orEmpty()
                GrammarTopicScreen(
                    id,
                    onBack = { nav.popBackStack() },
                    onPractice = { nav.navigate("grammar/$id/practice") },
                    onOpenTopic = { nav.navigate("grammar/$it") },
                )
            }
            composable("grammar/{id}/practice") { e ->
                GrammarPracticeScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("review") { ReviewScreen() }
        }
    }
}
