package id.terjemah.korea

import android.content.Context

/** Pengaturan yang disimpan di HP. */
object Prefs {
    const val MODE_OFFLINE = "offline"
    const val MODE_GEMINI = "gemini"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun mode(c: Context): String = sp(c).getString("mode", MODE_GEMINI) ?: MODE_GEMINI
    fun geminiKey(c: Context): String = sp(c).getString("gemini_key", "") ?: ""
    fun geminiModel(c: Context): String =
        (sp(c).getString("gemini_model", DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL)
            .trim().ifBlank { DEFAULT_GEMINI_MODEL }

    fun save(c: Context, mode: String, geminiKey: String, geminiModel: String) {
        sp(c).edit()
            .putString("mode", mode)
            .putString("gemini_key", geminiKey.trim())
            .putString("gemini_model", geminiModel.trim())
            .apply()
    }
}
