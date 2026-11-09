package fr.mescours.app.engine

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Session Google capturée dans la WebView de connexion, mise au format `storage_state`
 * Playwright attendu par la passerelle (notebooklm-py).
 */
object GoogleSession {
    private const val PSIDTS = "__Secure-1PSIDTS"
    private val HOSTS = listOf("notebook.google.com", "notebooklm.google.com", "accounts.google.com")

    data class Export(val json: String, val names: Set<String>)

    fun isSignedIn(): Boolean = read("https://www.google.com/").containsKey("SID")

    /**
     * Prépare l'export : si le cookie de fraîcheur `__Secure-1PSIDTS` manque, on le fait émettre
     * par Google (POST accounts.google.com/RotateCookies, comme le fait Chrome), puis on collecte.
     */
    suspend fun export(): Export {
        CookieManager.getInstance().flush()
        if (!read("https://www.google.com/").containsKey(PSIDTS)) {
            runCatching { rotateCookies() }
            CookieManager.getInstance().flush()
        }
        return collect()
    }

    private suspend fun rotateCookies() = withContext(Dispatchers.IO) {
        val cm = CookieManager.getInstance()
        // Pont entre OkHttp et le stockage de cookies de la WebView (redirections comprises).
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                read(url.toString()).map { (n, v) -> Cookie.Builder().name(n).value(v).domain(url.host).build() }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { c ->
                    val host = c.domain.removePrefix(".")
                    cm.setCookie("https://$host/", c.toString())
                }
            }
        }
        val http = OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://accounts.google.com/RotateCookies")
            .header("Origin", "https://accounts.google.com")
            .post("""[000,"-0000000000000000000"]""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { }
    }

    private fun read(url: String): Map<String, String> = CookieManager.getInstance().getCookie(url).orEmpty()
        .split(";").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

    /**
     * CookieManager ne donne que nom=valeur par URL : les cookies visibles sur www.google.com
     * sont rattachés à `.google.com`, les autres à l'hôte où ils apparaissent
     * (ex. OSID sur notebook.google.com, LSID sur accounts.google.com).
     */
    private fun collect(): Export {
        val shared = read("https://www.google.com/")
        val names = mutableSetOf<String>()
        val cookies = buildList {
            shared.forEach { (n, v) -> add(cookie(n, v, ".google.com")); names += n }
            HOSTS.forEach { host ->
                read("https://$host/").forEach { (n, v) ->
                    if (shared[n] != v) { add(cookie(n, v, host)); names += n }
                }
            }
        }
        val json = buildJsonObject {
            put("cookies", JsonArray(cookies))
            put("origins", JsonArray(emptyList()))
        }.toString()
        return Export(json, names)
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
}
