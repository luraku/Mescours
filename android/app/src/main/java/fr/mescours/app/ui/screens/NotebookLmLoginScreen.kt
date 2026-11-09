package fr.mescours.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.mescours.app.ui.AppViewModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NOTEBOOKLM_URL = "https://notebooklm.google.com/"
/** Ouvre directement le formulaire de connexion Google, puis revient sur NotebookLM. */
private val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=" + Uri.encode(NOTEBOOKLM_URL)

/**
 * Connexion à NotebookLM *dans l'app* : l'utilisateur se connecte à son compte Google
 * dans une WebView, puis l'app transmet la session (cookies) à la passerelle Docker,
 * qui n'a pas de navigateur. Remplace `notebooklm login`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NotebookLmLoginScreen(vm: AppViewModel, onBack: () -> Unit) {
    var currentUrl by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    val onNotebookLm = Uri.parse(currentUrl.ifBlank { "about:blank" }).host == "notebooklm.google.com"

    Scaffold(topBar = {
        BackTopBar("Connexion NotebookLM", onBack) {
            IconButton(onClick = { error = null; webView?.reload() }) { Icon(Icons.Default.Refresh, "Recharger") }
            IconButton(onClick = {
                CookieManager.getInstance().removeAllCookies { webView?.loadUrl(LOGIN_URL) }
            }) { Icon(Icons.AutoMirrored.Filled.Logout, "Changer de compte") }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                if (onNotebookLm) "Connecté à NotebookLM. Envoyez la connexion à la passerelle."
                else "Connectez-vous au compte Google qui possède vos carnets NotebookLM.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                Uri.parse(currentUrl.ifBlank { LOGIN_URL }).host.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuse parfois la connexion depuis une « WebView » :
                        // on présente l'agent utilisateur du navigateur Chrome mobile.
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "")
                            .replace(Regex("Version/\\d+\\.\\d+ "), "")
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                currentUrl = url
                                error = null
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                currentUrl = url
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                if (request.isForMainFrame) {
                                    error = "Chargement impossible (${err.errorCode}) : ${err.description}. " +
                                        "Vérifiez la connexion Internet puis rechargez."
                                }
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                                if (request.isForMainFrame && resp.statusCode >= 400) {
                                    error = "Google a répondu ${resp.statusCode} sur ${request.url.host}."
                                }
                            }
                        }
                        loadUrl(LOGIN_URL)
                        webView = this
                    }
                },
            )
            Button(
                onClick = {
                    CookieManager.getInstance().flush()
                    vm.sendGoogleSession(collectGoogleCookies()) { onBack() }
                },
                enabled = onNotebookLm,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) {
                Icon(Icons.Default.CloudUpload, null)
                Text("  Envoyer la connexion à la passerelle")
            }
            TextButton(onClick = { showPaste = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("La page ne s'affiche pas ? Coller des cookies exportés")
            }
        }
    }

    if (showPaste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("Importer des cookies") },
            text = {
                Column {
                    Text(
                        "Sur un ordinateur, connectez-vous à notebooklm.google.com, exportez les cookies " +
                            "au format JSON (extension Cookie-Editor → Export → JSON), puis collez-les ici.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(text, { text = it }, label = { Text("JSON des cookies") },
                        modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    showPaste = false
                    vm.sendGoogleSession(text.trim()) { onBack() }
                }) { Text("Envoyer") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Annuler") } },
        )
    }
}

/**
 * Construit un `storage_state` Playwright à partir des cookies de la WebView.
 * CookieManager ne donne que nom=valeur par URL : les cookies visibles sur www.google.com
 * sont rattachés à `.google.com`, les autres à l'hôte où ils apparaissent
 * (ex. OSID sur notebooklm.google.com, LSID sur accounts.google.com).
 */
private fun collectGoogleCookies(): String {
    val cm = CookieManager.getInstance()
    fun read(url: String): Map<String, String> = cm.getCookie(url).orEmpty()
        .split(";").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

    val shared = read("https://www.google.com/")
    val cookies = buildList {
        shared.forEach { (n, v) -> add(cookie(n, v, ".google.com")) }
        listOf("notebooklm.google.com", "accounts.google.com").forEach { host ->
            read("https://$host/").forEach { (n, v) -> if (shared[n] != v) add(cookie(n, v, host)) }
        }
    }
    return buildJsonObject {
        put("cookies", JsonArray(cookies))
        put("origins", JsonArray(emptyList()))
    }.toString()
}

private fun cookie(name: String, value: String, domain: String) = buildJsonObject {
    put("name", name)
    put("value", value)
    put("domain", domain)
    put("path", "/")
    put("expires", -1)
    put("httpOnly", true)
    put("secure", true)
    put("sameSite", "Lax")
}
