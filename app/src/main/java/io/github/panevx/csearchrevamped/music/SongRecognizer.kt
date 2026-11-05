package io.github.panevx.csearchrevamped.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Song(
    val title: String,
    val artist: String,
    val album: String?,
    val releaseDate: String?,
    val link: String?,
    val artUrl: String?,
)

/** Looks up a fingerprint with Shazam's recognition service, the same way SongRec does. No account or key needed. */
object SongRecognizer {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    /** Returns null when the audio did not match any song. */
    fun identify(signature: SignatureGenerator): Song? {
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("geolocation", JSONObject().put("altitude", 300).put("latitude", 45).put("longitude", 2))
            .put(
                "signature", JSONObject()
                    .put("samplems", signature.sampleMs)
                    .put("timestamp", now)
                    .put("uri", signature.uri())
            )
            .put("timestamp", now)
            .put("timezone", TimeZone.getDefault().id)

        val locale = Locale.getDefault()
        val lang = locale.language.ifBlank { "en" }
        val country = locale.country.ifBlank { "US" }
        val url = "https://amp.shazam.com/discovery/v5/$lang/$country/android/-/tag/" +
            "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP2A.240805.005)")
            .header("Content-Language", "${lang}_$country")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Song search service error ${response.code}")
            val json = JSONObject(response.body?.string() ?: throw IOException("Empty response"))
            val track = json.optJSONObject("track") ?: return null
            var album: String? = null
            var released: String? = null
            val sections = track.optJSONArray("sections")
            for (i in 0 until (sections?.length() ?: 0)) {
                val metadata = sections!!.optJSONObject(i)?.optJSONArray("metadata") ?: continue
                for (j in 0 until metadata.length()) {
                    val m = metadata.optJSONObject(j) ?: continue
                    when (m.str("title")) {
                        "Album" -> album = album ?: m.str("text")
                        "Released" -> released = released ?: m.str("text")
                    }
                }
            }
            val images = track.optJSONObject("images")
            return Song(
                title = track.str("title") ?: "Unknown",
                artist = track.str("subtitle").orEmpty(),
                album = album,
                releaseDate = released,
                link = track.str("url"),
                artUrl = images?.str("coverarthq") ?: images?.str("coverart"),
            )
        }
    }

    fun loadImage(url: String): Bitmap? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use {
            BitmapFactory.decodeStream(it.body!!.byteStream())
        }
    }.getOrNull()

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
