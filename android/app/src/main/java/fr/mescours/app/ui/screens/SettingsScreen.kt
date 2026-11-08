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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.mescours.app.data.EngineType
import fr.mescours.app.ui.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val initial = remember { vm.settings.value }
    var engine by remember { mutableStateOf(initial.engine) }
    var apiKey by remember { mutableStateOf(initial.geminiApiKey) }
    var model by remember { mutableStateOf(initial.geminiModel) }
    var bridgeUrl by remember { mutableStateOf(initial.bridgeUrl) }
    var bridgeToken by remember { mutableStateOf(initial.bridgeToken) }
    fun current() = initial.copy(engine = engine, geminiApiKey = apiKey, geminiModel = model,
        bridgeUrl = bridgeUrl, bridgeToken = bridgeToken)

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
                "Les cours créés avec NotebookLM gardent leur carnet : leurs quiz et exercices sont générés par " +
                    "NotebookLM tant que la passerelle répond, sinon par Gemini si une clé est renseignée.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Gemini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Clé gratuite sur aistudio.google.com → « Get API key ».", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Clé API Gemini") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Modèle") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Passerelle NotebookLM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Lancez la passerelle (dossier bridge/ du projet) sur un PC connecté à votre compte Google, " +
                    "puis indiquez son adresse, par ex. http://192.168.1.20:8765",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(bridgeUrl, { bridgeUrl = it }, label = { Text("Adresse de la passerelle") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bridgeToken, { bridgeToken = it }, label = { Text("Jeton d'accès (BRIDGE_TOKEN)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.testBridge(current()) }, enabled = bridgeUrl.isNotBlank()) {
                Text("Tester la passerelle")
            }

            Button(onClick = { vm.saveSettings(current()); onBack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Enregistrer")
            }
        }
    }
}
