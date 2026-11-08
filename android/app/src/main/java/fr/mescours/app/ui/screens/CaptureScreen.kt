package fr.mescours.app.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import fr.mescours.app.data.EngineType
import fr.mescours.app.model.SUBJECTS
import fr.mescours.app.ui.AppViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(vm: AppViewModel, onBack: () -> Unit, onDone: (subject: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by vm.library.collectAsState()
    val selectedId by vm.selectedChildId.collectAsState()
    val settings by vm.settings.collectAsState()
    val child = library.children.find { it.id == selectedId } ?: library.children.firstOrNull()

    // Les chemins sont sauvegardés pour survivre à une rotation pendant la prise de vue.
    var pagePaths by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var subject by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPhoto
        if (ok && path != null && File(path).length() > 0) pagePaths = pagePaths + path
        else path?.let { File(it).delete() }
        pendingPhoto = null
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris: List<Uri> ->
        scope.launch {
            val imported = uris.mapNotNull { vm.importPage(it)?.path }
            pagePaths = pagePaths + imported
        }
    }

    fun shoot() {
        val file = vm.newPageFile()
        pendingPhoto = file.path
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        takePicture.launch(uri)
    }

    fun leave() {
        vm.discardPages(pagePaths.map(::File))
        onBack()
    }
    BackHandler { leave() }

    Scaffold(topBar = { BackTopBar("Nouveau cours${child?.let { " · ${it.name}" } ?: ""}", ::leave) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExposedDropdownMenuBox(expanded, { expanded = it }) {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Matière") },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                )
                val suggestions = SUBJECTS.filter { subject.isBlank() || it.contains(subject, ignoreCase = true) }
                if (suggestions.isNotEmpty()) {
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        suggestions.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { subject = s; expanded = false })
                        }
                    }
                }
            }

            Text(
                "Photographiez chaque page de la leçon, bien à plat et éclairée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::shoot, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, null); Text("  Photo")
                }
                OutlinedButton(
                    onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoLibrary, null); Text("  Galerie")
                }
            }

            if (pagePaths.isEmpty()) {
                Card(Modifier.fillMaxWidth().height(200.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucune page pour l'instant", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text("${pagePaths.size} page(s)", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    itemsIndexed(pagePaths) { index, path ->
                        Box {
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(140.dp).height(200.dp).clip(RoundedCornerShape(12.dp)),
                            )
                            FilledIconButton(
                                onClick = { File(path).delete(); pagePaths = pagePaths - path },
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp),
                            ) { Icon(Icons.Default.Close, "Retirer", Modifier.size(18.dp)) }
                        }
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                Text(
                    "Moteur : " + if (settings.engine == EngineType.NOTEBOOKLM) "NotebookLM (votre compte)" else "Gemini",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val c = child ?: return@Button
                        val s = subject.trim()
                        vm.analyzeCourse(c, s, pagePaths.map(::File)) { onDone(s) }
                    },
                    enabled = child != null && subject.isNotBlank() && pagePaths.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, null); Text("  Créer les fiches par thème")
                }
            }
        }
    }
}
