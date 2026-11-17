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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.lectura.ui.BookStatsScreen
import app.lectura.ui.LibraryScreen
import app.lectura.ui.ProgressScreen
import app.lectura.ui.QuestionsScreen
import app.lectura.ui.ShadowingScreen
import app.lectura.ui.ReaderScreen
import app.lectura.ui.ReviewScreen
import app.lectura.ui.VocabScreen
import app.lectura.ui.grammar.GrammarPracticeScreen
import app.lectura.ui.grammar.GrammarScreen
import app.lectura.ui.grammar.GrammarTopicScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Книги", Icons.AutoMirrored.Filled.MenuBook),
    Tab("vocab", "Словарь", Icons.Filled.Style),
    Tab("grammar", "Грамматика", Icons.Filled.Spellcheck),
    Tab("review", "Повторение", Icons.Filled.School),
)

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
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(pad)) {
            composable("library") {
                LibraryScreen(
                    onOpen = { nav.navigate("reader/$it") },
                    onBookStats = { nav.navigate("bookstats/$it") },
                    onProgress = { nav.navigate("progress") },
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
            composable("vocab") { VocabScreen(onReview = { nav.navigate("review") }) }
            composable("grammar") { GrammarScreen(onTopic = { nav.navigate("grammar/$it") }) }
            composable("grammar/{id}") { e ->
                val id = e.arguments?.getString("id").orEmpty()
                GrammarTopicScreen(id, onBack = { nav.popBackStack() }, onPractice = { nav.navigate("grammar/$id/practice") })
            }
            composable("grammar/{id}/practice") { e ->
                GrammarPracticeScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("review") { ReviewScreen() }
        }
    }
}
