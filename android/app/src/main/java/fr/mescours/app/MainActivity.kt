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
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.mescours.app.model.Reward
import fr.mescours.app.ui.AppViewModel
import fr.mescours.app.ui.screens.AchievementsScreen
import fr.mescours.app.ui.screens.CaptureScreen
import fr.mescours.app.ui.screens.CourseScreen
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
    val reward by vm.reward.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // On efface le message seulement après l'avoir affiché : l'effacer avant relançait
    // cet effet et annulait aussitôt le snackbar (message invisible).
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long)
        vm.consumeMessage()
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    vm,
                    onOpenSubject = { nav.navigate("subject/${Uri.encode(it)}") },
                    onCapture = { nav.navigate("capture") },
                    onSettings = { nav.navigate("settings") },
                    onAchievements = { nav.navigate("achievements") },
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
            composable("achievements") { AchievementsScreen(vm, onBack = { nav.popBackStack() }) }
            composable("nlm-login") { NotebookLmLoginScreen(vm, onBack = { nav.popBackStack() }) }
            composable("subject/{subject}", listOf(navArgument("subject") { type = NavType.StringType })) { entry ->
                val subject = entry.arguments?.getString("subject").orEmpty()
                SubjectScreen(
                    vm, subject,
                    onBack = { nav.popBackStack() },
                    onOpenCourse = { nav.navigate("course/$it") },
                ) { courseId, themeId ->
                    nav.navigate("theme/$courseId/$themeId")
                }
            }
            composable("course/{courseId}") { entry ->
                val c = entry.arguments?.getString("courseId").orEmpty()
                CourseScreen(vm, c, onBack = { nav.popBackStack() }, onOpenTheme = { t -> nav.navigate("theme/$c/$t") })
            }
            composable("theme/{courseId}/{themeId}") { entry ->
                val c = entry.arguments?.getString("courseId").orEmpty()
                val t = entry.arguments?.getString("themeId").orEmpty()
                ThemeScreen(vm, c, t, onBack = { nav.popBackStack() },
                    onQuiz = { nav.navigate("quiz/$c/$t") },
                    onExercises = { nav.navigate("exercises/$c/$t") },
                    onOpenCourse = { nav.navigate("course/$c") })
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

        reward?.let { r ->
            if (busy == null) RewardDialog(r, onDismiss = vm::consumeReward)
        }

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

/** Fenêtre de récompense : XP gagnée, série de jours, passage de niveau et nouveaux badges. */
@Composable
private fun RewardDialog(r: Reward, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("Super !") } },
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (r.newBadges.isNotEmpty() || r.levelUp != null) "🎉" else "👏", fontSize = 48.sp)
                Text(r.headline, textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "+${r.xp} XP",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (r.streakIncreased && r.streak > 1) {
                    Text("🔥 ${r.streak} jours d'affilée !", fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp))
                }
                r.levelUp?.let {
                    Text("${it.emoji} Niveau ${it.number} : ${it.title} !", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp))
                }
                if (r.newBadges.isNotEmpty()) {
                    Text(
                        if (r.newBadges.size == 1) "Nouveau badge" else "Nouveaux badges",
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp),
                    )
                    r.newBadges.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp)
                                .background(Color(0xFFFFF4D6), RoundedCornerShape(16.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(b.emoji, fontSize = 28.sp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(b.title, fontWeight = FontWeight.Bold, color = Color(0xFF3B2F00))
                                Text(b.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5C4A00))
                            }
                        }
                    }
                }
            }
        },
    )
}
