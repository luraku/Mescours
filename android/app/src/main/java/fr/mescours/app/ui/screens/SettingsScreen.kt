package fr.mescours.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.dp
import fr.mescours.app.data.EngineType
import fr.mescours.app.ui.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onNotebookLmLogin: () -> Unit) {
    val initial = remember { vm.settings.value }
    var engine by remember { mutableStateOf(initial.engine) }
    var apiKey by remember { mutableStateOf(initial.geminiApiKey) }
    var model by remember { mutableStateOf(initial.geminiModel) }
    var bridgeUrl by remember { mutableStateOf(initial.bridgeUrl) }
    var bridgeToken by remember { mutableStateOf(initial.bridgeToken) }
    var pdUrl by remember { mutableStateOf(initial.pocketDockUrl) }
    var pdToken by remember { mutableStateOf(initial.pocketDockToken) }
    fun current() = initial.copy(engine = engine, geminiApiKey = apiKey, geminiModel = model,
        bridgeUrl = bridgeUrl, bridgeToken = bridgeToken, pocketDockUrl = pdUrl, pocketDockToken = pdToken)

    // L'installation via PocketDock renseigne elle-même l'adresse et le jeton de la passerelle.
    val saved by vm.settings.collectAsState()
    LaunchedEffect(saved.bridgeUrl, saved.bridgeToken) {
        bridgeUrl = saved.bridgeUrl
        bridgeToken = saved.bridgeToken
    }
    var showSecrets by remember { mutableStateOf(false) }
    val secretTransform = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()
    val eye: @Composable () -> Unit = {
        IconButton(onClick = { showSecrets = !showSecrets }) {
            Icon(if (showSecrets) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                if (showSecrets) "Masquer" else "Afficher")
        }
    }

    Scaffold(topBar = { BackTopBar("Réglages", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Moteur pour lire les nouveaux cours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            EngineType.entries.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = engine == e, onClick = { engine = e }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = engine == e, onClick = { engine = e })
                    Text(e.label)
                }
            }
            Text(
                "Les cours créés avec NotebookLM gardent leur carnet : leurs quiz, exercices et résumés audio " +
                    "sont générés par NotebookLM (intégré ou passerelle), sinon par Gemini si une clé est renseignée.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Gemini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Clé gratuite sur aistudio.google.com → « Get API key ».", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Clé API Gemini") }, singleLine = true, trailingIcon = eye,
                visualTransformation = secretTransform, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Modèle") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("NotebookLM intégré", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "L'app parle directement à votre NotebookLM, sans passerelle ni PocketDock. " +
                    "Votre session Google est chiffrée sur ce téléphone. " +
                    if (vm.hasEmbeddedSession()) "Session Google enregistrée." else "Aucune session Google enregistrée.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveSettings(current()); onNotebookLmLogin() }) { Text("Connecter mon compte Google") }
                OutlinedButton(onClick = { vm.testEmbedded() }, enabled = vm.hasEmbeddedSession()) { Text("Tester") }
            }
            if (vm.hasEmbeddedSession()) {
                TextButton(onClick = { vm.disconnectEmbedded() }) { Text("Oublier la session Google", color = MaterialTheme.colorScheme.error) }
            }

            HorizontalDivider()
            Text("PocketDock (Docker sur ce téléphone)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Jeton : dans PocketDock, onglet Moteur → « API de contrôle » → Copier. " +
                    "L'app installe alors la passerelle NotebookLM dans PocketDock et la redémarre si besoin.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(pdUrl, { pdUrl = it }, label = { Text("Adresse de l'API PocketDock") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pdToken, { pdToken = it }, label = { Text("Jeton PocketDock") }, singleLine = true, trailingIcon = eye,
                visualTransformation = secretTransform, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.testPocketDock(current()) }, enabled = pdToken.isNotBlank()) { Text("Tester") }
                Button(onClick = {
                    vm.saveSettings(current())
                    vm.installBridgeOnPocketDock(current())
                }, enabled = pdToken.isNotBlank()) { Text("Installer la passerelle") }
            }
            OutlinedButton(
                onClick = { vm.saveSettings(current()); vm.adoptExistingBridge(current()) },
                enabled = pdToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Récupérer la passerelle déjà installée (adresse + jeton)") }

            HorizontalDivider()
            Text("Passerelle NotebookLM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Lancez le conteneur Docker de la passerelle (dossier bridge/ du projet), puis indiquez son adresse : " +
                    "http://127.0.0.1:8765 s'il tourne sur ce téléphone (ex. PocketDock), sinon l'IP du PC ou du serveur.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(bridgeUrl, { bridgeUrl = it }, label = { Text("Adresse de la passerelle") }, placeholder = { Text("http://127.0.0.1:8765") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bridgeToken, { bridgeToken = it }, label = { Text("Jeton d'accès (BRIDGE_TOKEN)") }, singleLine = true, trailingIcon = eye,
                visualTransformation = secretTransform, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.testBridge(current()) }, enabled = bridgeUrl.isNotBlank()) {
                    Text("Tester")
                }
                Button(
                    onClick = { vm.saveSettings(current()); onNotebookLmLogin() },
                    enabled = bridgeUrl.isNotBlank(),
                ) { Text("Connecter mon compte Google") }
            }
            Text(
                "Connectez-vous à NotebookLM dans l'app : la session est transmise à la passerelle " +
                    "(à refaire si Google la fait expirer).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = { vm.saveSettings(current()); onBack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Enregistrer")
            }
        }
    }
}
