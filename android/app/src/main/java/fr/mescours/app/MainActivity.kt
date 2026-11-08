package fr.mescours.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.mescours.app.ui.AppViewModel
import fr.mescours.app.ui.screens.CaptureScreen
import fr.mescours.app.ui.screens.ExercisesScreen
import fr.mescours.app.ui.screens.HomeScreen
import fr.mescours.app.ui.screens.NotebookLmLoginScreen
import fr.mescours.app.ui.screens.QuizScreen
import fr.mescours.app.ui.screens.SettingsScreen
import fr.mescours.app.ui.screens.SubjectScreen
import fr.mescours.app.ui.screens.ThemeScreen
import fr.mescours.app.ui.theme.MescoursTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MescoursTheme { App(vm) } }
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val nav = rememberNavController()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    vm,
                    onOpenSubject = { nav.navigate("subject/${Uri.encode(it)}") },
                    onCapture = { nav.navigate("capture") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("capture") {
                CaptureScreen(vm, onBack = { nav.popBackStack() }, onDone = { subject ->
                    nav.navigate("subject/${Uri.encode(subject)}") { popUpTo("home") }
                })
            }
            composable("settings") {
                SettingsScreen(vm, onBack = { nav.popBackStack() }, onNotebookLmLogin = { nav.navigate("nlm-login") })
            }
            composable("nlm-login") { NotebookLmLoginScreen(vm, onBack = { nav.popBackStack() }) }
            composable("subject/{subject}", listOf(navArgument("subject") { type = NavType.StringType })) { entry ->
                val subject = entry.arguments?.getString("subject").orEmpty()
                SubjectScreen(vm, subject, onBack = { nav.popBackStack() }) { courseId, themeId ->
                    nav.navigate("theme/$courseId/$themeId")
                }
            }
            composable("theme/{courseId}/{themeId}") { entry ->
                val c = entry.arguments?.getString("courseId").orEmpty()
                val t = entry.arguments?.getString("themeId").orEmpty()
                ThemeScreen(vm, c, t, onBack = { nav.popBackStack() },
                    onQuiz = { nav.navigate("quiz/$c/$t") },
                    onExercises = { nav.navigate("exercises/$c/$t") })
            }
            composable("quiz/{courseId}/{themeId}") { entry ->
                QuizScreen(vm, entry.arguments?.getString("courseId").orEmpty(),
                    entry.arguments?.getString("themeId").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("exercises/{courseId}/{themeId}") { entry ->
                ExercisesScreen(vm, entry.arguments?.getString("courseId").orEmpty(),
                    entry.arguments?.getString("themeId").orEmpty(), onBack = { nav.popBackStack() })
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))

        busy?.let { text ->
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                confirmButton = {},
                title = { Text("Un instant…") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        CircularProgressIndicator()
                        Text(text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                    }
                },
            )
        }
    }
}
