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
import fr.mescours.app.data.SecureSessionStore
import fr.mescours.app.engine.EmbeddedNotebookLmEngine
import fr.mescours.app.engine.EngineException
import uniffi.nlm_core.NlmException
import fr.mescours.app.engine.GoogleSession
import fr.mescours.app.engine.PocketDockClient
import fr.mescours.app.engine.GeminiEngine
import fr.mescours.app.engine.NotebookLmBridgeEngine
import fr.mescours.app.engine.StudyEngine
import fr.mescours.app.model.Child
import fr.mescours.app.model.Course
import fr.mescours.app.model.Gamification
import fr.mescours.app.model.ProgressEvent
import fr.mescours.app.model.Reward
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
import java.time.LocalDate
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

    // ---------- Gamification ----------

    /** Récompense à afficher (XP gagnée, badges, niveau), null = rien. */
    private val _reward = MutableStateFlow<Reward?>(null)
    val reward: StateFlow<Reward?> = _reward.asStateFlow()

    fun consumeReward() { _reward.value = null }

    private suspend fun recordProgress(childId: String, event: ProgressEvent, showReward: Boolean = true) {
        val reward = repo.updateProgress(childId) { p, lib ->
            val subjects = lib.courses.filter { it.childId == childId }.map { it.subject }.distinct().size
            Gamification.apply(p, event, subjects, LocalDate.now().toEpochDay())
        }
        val name = library.value.children.find { it.id == childId }?.name.orEmpty()
        if (reward != null && showReward) _reward.value = reward.copy(childName = name)
    }

    fun recordQuiz(course: Course, theme: Theme, correct: Int, total: Int) = viewModelScope.launch {
        recordProgress(course.childId, ProgressEvent.QuizFinished(theme.id, correct, total))
    }

    fun recordExercise(course: Course, key: String) = viewModelScope.launch {
        recordProgress(course.childId, ProgressEvent.ExerciseDone(key))
    }

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

    private val sessionStore = SecureSessionStore(app)
    private val embedded = EmbeddedNotebookLmEngine(sessionStore, app.cacheDir)

    private fun gemini(s: AppSettings = _settings.value) = GeminiEngine(s.geminiApiKey, s.geminiModel)
    private fun bridge(s: AppSettings = _settings.value) = NotebookLmBridgeEngine(s.bridgeUrl, s.bridgeToken)

    private fun primaryEngine(): StudyEngine = when (_settings.value.engine) {
        EngineType.GEMINI -> gemini()
        EngineType.EMBEDDED -> embedded
        EngineType.NOTEBOOKLM -> bridge()
    }

    /** Cours avec carnet : NotebookLM (intégré ou passerelle) ; sinon Gemini. */
    private fun engineFor(course: Course): StudyEngine {
        val s = _settings.value
        if (course.notebookId == null) return gemini(s)
        return when {
            s.engine == EngineType.EMBEDDED && embedded.hasSession() -> embedded
            s.bridgeUrl.isNotBlank() && (s.engine == EngineType.NOTEBOOKLM || s.geminiApiKey.isBlank()) -> bridge(s)
            embedded.hasSession() && s.geminiApiKey.isBlank() -> embedded
            else -> gemini(s)
        }
    }

    /**
     * Si la passerelle est injoignable : on tente de la relancer via PocketDock (si configuré),
     * puis, en dernier recours, on bascule sur Gemini quand une clé existe.
     */
    private suspend fun <T> withFallback(primary: StudyEngine, block: suspend (StudyEngine) -> T): T {
        try {
            return block(primary)
        } catch (e: EngineException) {
            // Moteur intégré : bascule sur Gemini seulement en cas de panne réseau.
            if (primary is EmbeddedNotebookLmEngine) {
                if (e.cause !is NlmException.Network || _settings.value.geminiApiKey.isBlank()) throw e
                _message.value = "NotebookLM injoignable : bascule sur Gemini."
                return block(gemini())
            }
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

    /**
     * Installe (ou met à jour) la passerelle dans PocketDock : téléchargement de l'image
     * précompilée, remplacement du conteneur, démarrage, puis configuration de l'app.
     */
    fun installBridgeOnPocketDock(s: AppSettings) = viewModelScope.launch {
        _busy.value = "Connexion à PocketDock…"
        try {
            val pd = pocketDock(s)
            ensureEngineRunning(pd)
            _busy.value = "Téléchargement de l'image de la passerelle…\n(plusieurs minutes : la VM de PocketDock est émulée)"
            pd.pullImage(BridgeBundle.IMAGE)
            val token = s.bridgeToken.ifBlank { randomToken() }
            _busy.value = "Démarrage du conteneur de la passerelle…"
            pd.removeContainer(PocketDockClient.CONTAINER_NAME)
            runBridgeContainer(pd, token)
            val updated = s.copy(bridgeUrl = BridgeBundle.LOCAL_URL, bridgeToken = token)
            persistSettings(updated)
            _busy.value = "Attente de la passerelle…"
            waitForBridge(updated, timeoutMs = 300_000)
            _message.value = "Passerelle installée et démarrée. Connectez maintenant votre compte Google."
        } catch (e: Exception) {
            _message.value = e.message ?: "Erreur pendant l'installation"
        } finally {
            _busy.value = null
        }
    }

    /** Récupère l'adresse et le jeton d'une passerelle déjà déployée dans PocketDock. */
    fun adoptExistingBridge(s: AppSettings) = viewModelScope.launch {
        _busy.value = "Recherche de la passerelle dans PocketDock…"
        try {
            val pd = pocketDock(s)
            ensureEngineRunning(pd)
            val found = pd.findBridge()
                ?: throw EngineException("Aucun conteneur de passerelle trouvé dans PocketDock : utilisez « Installer la passerelle ».")
            val updated = s.copy(bridgeUrl = "http://127.0.0.1:${found.port}", bridgeToken = found.token)
            persistSettings(updated)
            _message.value = "Passerelle « ${found.name} » récupérée : ${updated.bridgeUrl}" +
                if (found.token.isBlank()) " (sans jeton)." else ", jeton enregistré."
        } catch (e: Exception) {
            _message.value = e.message ?: "Erreur pendant la recherche de la passerelle"
        } finally {
            _busy.value = null
        }
    }

    private suspend fun runBridgeContainer(pd: PocketDockClient, token: String) = pd.runContainer(
        image = BridgeBundle.IMAGE,
        name = PocketDockClient.CONTAINER_NAME,
        ports = listOf("${BridgeBundle.PORT}:${BridgeBundle.PORT}"),
        env = listOf("BRIDGE_TOKEN=$token"),
        volumes = listOf("mescours-data:/data"),
    )

    private suspend fun restartBridgeViaPocketDock() {
        val previous = _busy.value
        try {
            _busy.value = "Démarrage de la passerelle via PocketDock…"
            val pd = pocketDock()
            ensureEngineRunning(pd)
            if (!pd.startContainer(PocketDockClient.CONTAINER_NAME)) {
                runBridgeContainer(pd, _settings.value.bridgeToken)
            }
            waitForBridge(_settings.value, timeoutMs = 300_000)
        } finally {
            _busy.value = previous
        }
    }

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
    fun testEmbedded() = viewModelScope.launch {
        _busy.value = "Test de NotebookLM intégré…"
        _message.value = try {
            "✓ NotebookLM intégré : " + embedded.accountStatus()
        } catch (e: Exception) {
            "✗ " + (e.message ?: "Erreur inconnue")
        } finally {
            _busy.value = null
        }
    }

    fun disconnectEmbedded() {
        sessionStore.clear()
        embedded.reset()
        _message.value = "Session Google supprimée du téléphone."
    }

    fun hasEmbeddedSession() = embedded.hasSession()

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

    /** Envoie la session de la WebView (après avoir fait émettre __Secure-1PSIDTS si besoin). */
    fun sendWebViewSession(onSuccess: () -> Unit) = viewModelScope.launch {
        _busy.value = "Préparation de la session Google…"
        val export = try {
            GoogleSession.export()
        } catch (e: Exception) {
            _busy.value = null
            _message.value = "Lecture de la session Google impossible : ${e.message}"
            return@launch
        }
        if ("__Secure-1PSIDTS" !in export.names) {
            _busy.value = null
            _message.value = "Google n'a pas fourni le cookie __Secure-1PSIDTS à l'app " +
                "(cookies trouvés : ${export.names.sorted().joinToString(", ")}). " +
                "Rechargez NotebookLM puis réessayez, ou utilisez « Coller des cookies exportés »."
            return@launch
        }
        sendGoogleSession(export.json, onSuccess).join()
    }

    /**
     * Enregistre la session Google : toujours sur le téléphone (moteur intégré, chiffrée),
     * et aussi dans la passerelle si c'est le moteur choisi.
     */
    fun sendGoogleSession(storageStateJson: String, onSuccess: () -> Unit) = viewModelScope.launch {
        _busy.value = "Enregistrement de la connexion Google…"
        try {
            sessionStore.save(storageStateJson)
            embedded.reset()
            val s = _settings.value
            _message.value = if (s.engine == EngineType.NOTEBOOKLM && s.bridgeUrl.isNotBlank()) {
                "NotebookLM (passerelle) : " + bridge().importCookies(storageStateJson)
            } else {
                "NotebookLM intégré : " + embedded.accountStatus()
            }
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
                    usedEngine = if (engine is NotebookLmBridgeEngine || engine is EmbeddedNotebookLmEngine) "notebooklm" else "gemini"
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
                recordProgress(child.id, ProgressEvent.CourseAdded)
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

    // ---------- Résumé audio (NotebookLM) ----------

    private val audioPollers = mutableSetOf<String>()

    /** Le résumé audio n'existe que pour les cours qui ont un carnet NotebookLM. */
    fun audioAvailable(course: Course) = course.notebookId != null &&
        (embeddedAudio() || _settings.value.bridgeUrl.isNotBlank())

    /** Audio par la bibliothèque intégrée si ce moteur est choisi (ou s'il n'y a pas de passerelle). */
    private fun embeddedAudio() = embedded.hasSession() &&
        (_settings.value.engine == EngineType.EMBEDDED || _settings.value.bridgeUrl.isBlank())

    fun generateAudio(course: Course, theme: Theme, short: Boolean) = viewModelScope.launch {
        val grade = childOf(course)?.grade ?: ""
        try {
            val taskId = if (embeddedAudio()) embedded.startAudio(course, theme, grade, short)
            else bridge().startAudio(course, theme, grade, short)
            repo.updateTheme(course.id, theme.id) {
                it.audioPath?.let { p -> File(p).delete() }
                it.copy(audioTaskId = taskId, audioPath = null, audioError = null)
            }
            _message.value = "Résumé audio lancé : NotebookLM le prépare (souvent 5 à 10 minutes)."
            pollAudio(course.id, theme.id)
        } catch (e: Exception) {
            _message.value = e.message ?: "Impossible de lancer le résumé audio"
        }
    }

    /** Reprend le suivi d'une génération en cours (appelé à l'ouverture de la fiche). */
    fun resumeAudio(course: Course, theme: Theme) {
        if (theme.audioTaskId != null && theme.audioPath == null) pollAudio(course.id, theme.id)
    }

    private fun pollAudio(courseId: String, themeId: String) {
        if (!audioPollers.add(themeId)) return
        viewModelScope.launch {
            try {
                val deadline = System.currentTimeMillis() + 40 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    val course = library.value.courses.find { it.id == courseId } ?: return@launch
                    val theme = course.themes.find { it.id == themeId } ?: return@launch
                    val taskId = theme.audioTaskId ?: return@launch
                    val viaEmbedded = embeddedAudio()
                    // detail = URL du média (intégré, si prêt) ou message d'erreur.
                    val (status, detail) = runCatching {
                        if (viaEmbedded) embedded.audioState(course, taskId) else bridge().audioStatus(course, taskId)
                    }.getOrElse { "pending" to null } // réseau ou passerelle momentanément indisponible : on réessaie
                    val error = if (status == "failed") detail else null
                    when (status) {
                        "completed" -> {
                            val dest = File(getApplication<Application>().filesDir, "audio/$themeId.m4a")
                            if (viaEmbedded) {
                                embedded.downloadAudio(detail ?: throw EngineException("URL du résumé audio absente"), dest)
                            } else {
                                bridge().downloadAudio(course, taskId, dest)
                            }
                            repo.updateTheme(courseId, themeId) { it.copy(audioPath = dest.path, audioTaskId = null) }
                            _message.value = "🎧 Le résumé audio « ${theme.title} » est prêt !"
                            return@launch
                        }
                        "failed" -> {
                            repo.updateTheme(courseId, themeId) {
                                it.copy(audioTaskId = null, audioError = error ?: "génération échouée")
                            }
                            _message.value = "Le résumé audio a échoué : ${error ?: "erreur NotebookLM"}"
                            return@launch
                        }
                    }
                    delay(15_000)
                }
            } catch (e: Exception) {
                _message.value = e.message ?: "Erreur pendant le résumé audio"
            } finally {
                audioPollers.remove(themeId)
            }
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
