package fr.mescours.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.mescours.app.data.AppSettings
import fr.mescours.app.data.EngineType
import fr.mescours.app.data.LibraryRepository
import fr.mescours.app.data.SettingsStore
import fr.mescours.app.engine.BridgeBundle
import fr.mescours.app.engine.EngineException
import fr.mescours.app.engine.PocketDockClient
import fr.mescours.app.engine.GeminiEngine
import fr.mescours.app.engine.NotebookLmBridgeEngine
import fr.mescours.app.engine.StudyEngine
import fr.mescours.app.model.Child
import fr.mescours.app.model.Course
import fr.mescours.app.model.Theme
import fr.mescours.app.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LibraryRepository(app)
    private val settingsStore = SettingsStore(app)
    private val pagesDir = File(app.filesDir, "pages").apply { mkdirs() }

    val library = repo.library

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _selectedChildId = MutableStateFlow(library.value.children.firstOrNull()?.id)
    val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

    /** Message affiché pendant un traitement long (null = rien en cours). */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun selectChild(id: String) { _selectedChildId.value = id }

    fun childOf(course: Course): Child? = library.value.children.find { it.id == course.childId }

    fun addChild(name: String, grade: String) = viewModelScope.launch {
        val child = Child(name = name.trim(), grade = grade)
        repo.addChild(child)
        _selectedChildId.value = child.id
    }

    fun deleteChild(id: String) = viewModelScope.launch {
        repo.deleteChild(id)
        if (_selectedChildId.value == id) _selectedChildId.value = library.value.children.firstOrNull()?.id
    }

    fun deleteCourse(id: String) = viewModelScope.launch { repo.deleteCourse(id) }

    fun saveSettings(s: AppSettings) {
        persistSettings(s)
        _message.value = "Réglages enregistrés"
    }

    private fun persistSettings(s: AppSettings) {
        settingsStore.save(s)
        _settings.value = settingsStore.load()
    }

    // ---------- Photos ----------

    fun newPageFile(): File = File(pagesDir, "${newId()}.jpg")

    suspend fun importPage(uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dest = newPageFile()
            getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest
        }.getOrNull()
    }

    fun discardPages(pages: List<File>) { pages.forEach { it.delete() } }

    // ---------- Moteurs ----------

    private fun gemini(s: AppSettings = _settings.value) = GeminiEngine(s.geminiApiKey, s.geminiModel)
    private fun bridge(s: AppSettings = _settings.value) = NotebookLmBridgeEngine(s.bridgeUrl, s.bridgeToken)

    private fun primaryEngine(): StudyEngine =
        if (_settings.value.engine == EngineType.NOTEBOOKLM) bridge() else gemini()

    /** NotebookLM pour les cours qui ont un carnet ; Gemini sinon. */
    private fun engineFor(course: Course): StudyEngine {
        val s = _settings.value
        return if (course.notebookId != null && s.bridgeUrl.isNotBlank() &&
            (s.engine == EngineType.NOTEBOOKLM || s.geminiApiKey.isBlank())
        ) bridge(s) else gemini(s)
    }

    /**
     * Si la passerelle est injoignable : on tente de la relancer via PocketDock (si configuré),
     * puis, en dernier recours, on bascule sur Gemini quand une clé existe.
     */
    private suspend fun <T> withFallback(primary: StudyEngine, block: suspend (StudyEngine) -> T): T {
        try {
            return block(primary)
        } catch (e: EngineException) {
            if (primary !is NotebookLmBridgeEngine || e.cause !is java.io.IOException) throw e
            val restarted = canPilotLocalBridge() && runCatching { restartBridgeViaPocketDock() }.isSuccess
            if (restarted) {
                try {
                    return block(bridge())
                } catch (e2: EngineException) {
                    if (e2.cause !is java.io.IOException || _settings.value.geminiApiKey.isBlank()) throw e2
                }
            } else if (_settings.value.geminiApiKey.isBlank()) {
                throw e
            }
        }
        _message.value = "Passerelle NotebookLM injoignable : bascule sur Gemini."
        return block(gemini())
    }

    // ---------- PocketDock (Docker sur le téléphone) ----------

    private fun pocketDock(s: AppSettings = _settings.value) = PocketDockClient(s.pocketDockUrl, s.pocketDockToken)

    private fun canPilotLocalBridge(): Boolean {
        val s = _settings.value
        val host = runCatching { Uri.parse(s.bridgeUrl).host }.getOrNull()
        return s.pocketDockToken.isNotBlank() && (host == "127.0.0.1" || host == "localhost")
    }

    fun testPocketDock(s: AppSettings) = viewModelScope.launch {
        _busy.value = "Connexion à PocketDock…"
        _message.value = try {
            "PocketDock : " + pocketDock(s).status().summary
        } catch (e: Exception) {
            e.message ?: "Erreur inconnue"
        } finally {
            _busy.value = null
        }
    }

    /** Envoie la stack Compose de la passerelle à PocketDock, la construit, la démarre et configure l'app. */
    fun installBridgeOnPocketDock(s: AppSettings) = viewModelScope.launch {
        _busy.value = "Connexion à PocketDock…"
        try {
            val pd = pocketDock(s)
            ensureEngineRunning(pd)
            val token = s.bridgeToken.ifBlank { randomToken() }
            _busy.value = "Envoi de la passerelle à PocketDock…"
            val archive = withContext(Dispatchers.IO) { BridgeBundle.archive(getApplication(), token) }
            pd.uploadCompose(PocketDockClient.STACK_NAME, archive)
            // 1) Télécharger l'image précompilée ; 2) en secours seulement, la construire dans la VM.
            val pulled = runCatching {
                awaitJob(pd, pd.composePull(PocketDockClient.STACK_NAME), "Téléchargement de l'image de la passerelle…", 30)
            }.isSuccess
            if (pulled) {
                runStack(pd, build = false, "Démarrage de la passerelle…")
            } else {
                runStack(pd, build = true,
                    "Image précompilée indisponible : construction dans PocketDock (très long, VM émulée)…")
            }
            val updated = s.copy(bridgeUrl = BridgeBundle.LOCAL_URL, bridgeToken = token)
            persistSettings(updated)
            waitForBridge(updated, timeoutMs = 180_000)
            _message.value = "Passerelle installée et démarrée. Connectez maintenant votre compte Google."
        } catch (e: Exception) {
            _message.value = e.message ?: "Erreur pendant l'installation"
        } finally {
            _busy.value = null
        }
    }

    private suspend fun restartBridgeViaPocketDock() {
        val previous = _busy.value
        try {
            _busy.value = "Démarrage de la passerelle via PocketDock…"
            val pd = pocketDock()
            ensureEngineRunning(pd)
            runStack(pd, build = false, "Démarrage de la passerelle…")
            waitForBridge(_settings.value, timeoutMs = 120_000)
        } finally {
            _busy.value = previous
        }
    }

    /** Démarre la VM Docker de PocketDock si besoin et attend l'état RUNNING (démarrage lent : VM émulée). */
    private suspend fun ensureEngineRunning(pd: PocketDockClient) {
        var st = pd.status()
        if (st.running) return
        val deadline = System.currentTimeMillis() + 15 * 60_000
        var startRequested = false
        while (System.currentTimeMillis() < deadline) {
            if (st.running) return
            if (st.state == "ERROR" && startRequested) {
                throw EngineException("Le moteur PocketDock est en erreur : ${st.message}")
            }
            if (!startRequested && !st.starting && st.state != "STOPPING") {
                pd.startEngine()
                startRequested = true
            }
            val progress = st.progress?.let { " ($it %)" }.orEmpty()
            _busy.value = "Démarrage du moteur PocketDock$progress…" +
                st.message.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            delay(3_000)
            st = pd.status()
        }
        throw EngineException("Le moteur PocketDock n'a pas démarré à temps (${st.summary}).")
    }

    private suspend fun runStack(pd: PocketDockClient, build: Boolean, label: String) =
        awaitJob(pd, pd.composeUp(PocketDockClient.STACK_NAME, build), label, if (build) 90 else 30)

    private suspend fun awaitJob(pd: PocketDockClient, jobId: String, label: String, timeoutMinutes: Int) {
        val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val job = pd.job(jobId)
            val tail = job.output.lines().filter { it.isNotBlank() }.takeLast(5).joinToString("\n")
            _busy.value = if (tail.isBlank()) label else "$label\n\n$tail"
            if (job.done) {
                if (job.failed) throw EngineException("Échec de la stack PocketDock" +
                    job.error?.let { " ($it)" }.orEmpty() + " :\n" +
                    job.output.lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n"))
                return
            }
            delay(2_000)
        }
        throw EngineException("La stack PocketDock n'a pas fini de démarrer à temps.")
    }

    private suspend fun waitForBridge(s: AppSettings, timeoutMs: Long) {
        val engine = bridge(s)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (engine.ping()) return
            delay(2_000)
        }
        throw EngineException("La passerelle ne répond pas sur ${s.bridgeUrl} après démarrage.")
    }

    private fun randomToken(): String =
        ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Diagnostic en trois temps : joignable (/ping), jeton accepté, session NotebookLM (/health). */
    fun testBridge(s: AppSettings) = viewModelScope.launch {
        _busy.value = "Test de la passerelle…"
        val engine = NotebookLmBridgeEngine(s.bridgeUrl, s.bridgeToken)
        _message.value = try {
            if (!engine.ping()) {
                "✗ Passerelle injoignable sur ${s.bridgeUrl} (adresse ou port faux, ou conteneur arrêté)."
            } else {
                try {
                    "✓ Passerelle joignable, jeton accepté, " + engine.health()
                } catch (e: EngineException) {
                    val msg = e.message.orEmpty()
                    when {
                        "401" in msg -> "✓ Passerelle joignable, mais ✗ jeton refusé : saisissez la valeur BRIDGE_TOKEN du conteneur."
                        "Session NotebookLM" in msg ->
                            "✓ Passerelle joignable, jeton accepté. ✗ Compte Google pas encore connecté : « Connecter mon compte Google »."
                        else -> "✓ Passerelle joignable, jeton accepté. ✗ $msg"
                    }
                }
            }
        } catch (e: Exception) {
            e.message ?: "Erreur inconnue"
        } finally {
            _busy.value = null
        }
    }

    fun sendGoogleSession(storageStateJson: String, onSuccess: () -> Unit) = viewModelScope.launch {
        _busy.value = "Envoi de la connexion Google à la passerelle…"
        try {
            _message.value = "NotebookLM : " + bridge().importCookies(storageStateJson)
            onSuccess()
        } catch (e: Exception) {
            _message.value = e.message ?: "Erreur pendant l'envoi de la connexion"
        } finally {
            _busy.value = null
        }
    }

    // ---------- Cas d'usage ----------

    fun analyzeCourse(child: Child, subject: String, pages: List<File>, onDone: (Course) -> Unit) =
        viewModelScope.launch {
            _busy.value = if (_settings.value.engine == EngineType.NOTEBOOKLM)
                "NotebookLM lit le cours et le découpe en thèmes…\n(cela peut prendre 1 à 3 minutes)"
            else "Gemini lit le cours et le découpe en thèmes…"
            try {
                var usedEngine = ""
                val structured = withFallback(primaryEngine()) { engine ->
                    usedEngine = if (engine is NotebookLmBridgeEngine) "notebooklm" else "gemini"
                    engine.structureCourse(pages, subject, child.grade)
                }
                val course = Course(
                    childId = child.id,
                    subject = subject,
                    title = structured.title.ifBlank { subject },
                    pagePaths = pages.map { it.path },
                    transcript = structured.transcript,
                    themes = structured.themes.map { it.copy(id = newId(), quiz = emptyList(), exercises = emptyList()) },
                    notebookId = structured.notebookId,
                    engine = usedEngine,
                )
                repo.addCourse(course)
                onDone(course)
            } catch (e: Exception) {
                _message.value = e.message ?: "Erreur pendant l'analyse"
            } finally {
                _busy.value = null
            }
        }

    fun generateQuiz(course: Course, theme: Theme, count: Int = 10) = viewModelScope.launch {
        val grade = childOf(course)?.grade ?: ""
        _busy.value = "Création du quiz « ${theme.title} »…"
        try {
            val questions = withFallback(engineFor(course)) { it.generateQuiz(course, theme, grade, count) }
            if (questions.isEmpty()) throw EngineException("Le quiz généré est vide, réessayez.")
            repo.updateTheme(course.id, theme.id) { it.copy(quiz = questions) }
        } catch (e: Exception) {
            _message.value = e.message ?: "Erreur pendant la création du quiz"
        } finally {
            _busy.value = null
        }
    }

    fun generateExercises(course: Course, theme: Theme, count: Int = 5) = viewModelScope.launch {
        val grade = childOf(course)?.grade ?: ""
        _busy.value = "Création des exercices « ${theme.title} »…"
        try {
            val exercises = withFallback(engineFor(course)) { it.generateExercises(course, theme, grade, count) }
            if (exercises.isEmpty()) throw EngineException("Aucun exercice généré, réessayez.")
            repo.updateTheme(course.id, theme.id) { it.copy(exercises = exercises) }
        } catch (e: Exception) {
            _message.value = e.message ?: "Erreur pendant la création des exercices"
        } finally {
            _busy.value = null
        }
    }
}
