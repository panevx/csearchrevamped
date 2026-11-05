package io.github.panevx.csearchrevamped

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import java.net.URLEncoder
import java.util.Locale

/** Hands bitmaps between the capture source and the activities in the same process. */
object ScreenshotHolder {
    @Volatile var screen: Bitmap? = null
    @Volatile var crop: Bitmap? = null
}

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun targetLang(c: Context): String =
        prefs(c).getString("target_lang", null)?.takeIf { it.isNotBlank() } ?: Locale.getDefault().language
    fun setTargetLang(c: Context, v: String) = prefs(c).edit().putString("target_lang", v.trim()).apply()

    fun wifiOnly(c: Context): Boolean = prefs(c).getBoolean("wifi_only", true)
    fun setWifiOnly(c: Context, v: Boolean) = prefs(c).edit().putBoolean("wifi_only", v).apply()

    fun autoSearch(c: Context): Boolean = prefs(c).getBoolean("auto_search", true)
    fun setAutoSearch(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto_search", v).apply()
}

object Links {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun translate(text: String, target: String) =
        "https://translate.google.com/?sl=auto&tl=${enc(target)}&text=${enc(text.take(4500))}&op=translate"
    fun search(q: String) = "https://www.google.com/search?q=${enc(q)}"
    fun youtube(q: String) = "https://m.youtube.com/results?search_query=${enc(q)}"
    fun spotify(q: String) = "https://open.spotify.com/search/${Uri.encode(q)}"
}

fun isCaptureServiceEnabled(c: Context): Boolean {
    val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(c, CaptureService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}
