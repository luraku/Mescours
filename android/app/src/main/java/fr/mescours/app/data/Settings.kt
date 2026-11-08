package fr.mescours.app.data

import android.content.Context

enum class EngineType(val label: String) {
    GEMINI("Gemini API (Google AI Studio)"),
    NOTEBOOKLM("NotebookLM (via la passerelle sur votre PC)"),
}

data class AppSettings(
    val engine: EngineType = EngineType.GEMINI,
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val bridgeUrl: String = "",
    val bridgeToken: String = "",
) {
    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        engine = runCatching { EngineType.valueOf(prefs.getString("engine", null)!!) }
            .getOrDefault(EngineType.GEMINI),
        geminiApiKey = prefs.getString("geminiApiKey", "") ?: "",
        geminiModel = prefs.getString("geminiModel", null)?.takeIf { it.isNotBlank() }
            ?: AppSettings.DEFAULT_GEMINI_MODEL,
        bridgeUrl = prefs.getString("bridgeUrl", "") ?: "",
        bridgeToken = prefs.getString("bridgeToken", "") ?: "",
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("engine", s.engine.name)
            .putString("geminiApiKey", s.geminiApiKey.trim())
            .putString("geminiModel", s.geminiModel.trim())
            .putString("bridgeUrl", s.bridgeUrl.trim().trimEnd('/'))
            .putString("bridgeToken", s.bridgeToken.trim())
            .apply()
    }
}
