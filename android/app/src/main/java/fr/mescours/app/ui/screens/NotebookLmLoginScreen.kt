package fr.mescours.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val onNotebookLm = Uri.parse(currentUrl.ifBlank { "about:blank" }).host == "notebooklm.google.com"

    Scaffold(topBar = {
        BackTopBar("Connexion NotebookLM", onBack) {
            IconButton(onClick = {
                CookieManager.getInstance().removeAllCookies { webView?.loadUrl(NOTEBOOKLM_URL) }
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
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                currentUrl = url
                            }
                        }
                        loadUrl(NOTEBOOKLM_URL)
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
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Icon(Icons.Default.CloudUpload, null)
                Text("  Envoyer la connexion à la passerelle")
            }
        }
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
