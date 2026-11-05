package io.github.panevx.csearchrevamped

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Song(
    val title: String,
    val artist: String,
    val album: String?,
    val releaseDate: String?,
    val link: String?,
    val artUrl: String?,
)

class RecognitionException(message: String, val code: Int) : IOException(message)

/** Identifies a recording with the AudD API (https://docs.audd.io). */
object SongRecognizer {
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    /** Returns null when the audio did not match any song. */
    fun identify(file: File, token: String): Song? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("return", "apple_music,spotify")
            .apply { if (token.isNotBlank()) addFormDataPart("api_token", token) }
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val request = Request.Builder().url("https://api.audd.io/").post(body).build()
        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: throw IOException("Empty response"))
            if (json.optString("status") != "success") {
                val error = json.optJSONObject("error")
                throw RecognitionException(
                    error?.str("error_message") ?: "Recognition failed",
                    error?.optInt("error_code") ?: 0,
                )
            }
            val r = json.optJSONObject("result") ?: return null
            val spotifyArt = r.optJSONObject("spotify")?.optJSONObject("album")
                ?.optJSONArray("images")?.optJSONObject(0)?.str("url")
            val appleArt = r.optJSONObject("apple_music")?.optJSONObject("artwork")?.str("url")
                ?.replace("{w}", "600")?.replace("{h}", "600")
            return Song(
                title = r.str("title") ?: "Unknown",
                artist = r.str("artist") ?: "",
                album = r.str("album"),
                releaseDate = r.str("release_date"),
                link = r.str("song_link"),
                artUrl = spotifyArt ?: appleArt,
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
