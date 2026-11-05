package io.github.panevx.csearchrevamped

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.panevx.csearchrevamped.music.SignatureGenerator
import io.github.panevx.csearchrevamped.music.Song
import io.github.panevx.csearchrevamped.music.SongRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Listens through the microphone and identifies the song that is playing. */
class MusicActivity : AppCompatActivity() {

    private companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 12
        /** Try a lookup after this many seconds of audio; more audio helps quiet or noisy rooms. */
        val ATTEMPTS = intArrayOf(4, 8, 12)

        const val SHAZAM_PACKAGE = "com.shazam.android"
        const val GOOGLE_SONG_SEARCH = "com.google.android.googlequicksearchbox.MUSIC_SEARCH"
    }

    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var art: ImageView
    private lateinit var listenGroup: View
    private lateinit var links: View
    private lateinit var again: View
    private lateinit var pulse: ObjectAnimator

    private val audio = ShortArray(SAMPLE_RATE * MAX_SECONDS)
    @Volatile private var recorded = 0
    private var job: Job? = null
    private var recordJob: Job? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else showMessage(getString(R.string.mic_denied), "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)
        findViewById<View>(R.id.root).setOnClickListener { finish() }
        findViewById<View>(R.id.sheet).setOnClickListener { }
        status = findViewById(R.id.status)
        sub = findViewById(R.id.sub)
        art = findViewById(R.id.art)
        listenGroup = findViewById(R.id.listenGroup)
        links = findViewById(R.id.links)
        again = findViewById(R.id.again)
        again.setOnClickListener { listen() }
        setUpOtherApps()

        pulse = ObjectAnimator.ofPropertyValuesHolder(
            findViewById(R.id.pulse),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f),
        ).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) listen() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStop() {
        super.onStop()
        if (recordJob?.isActive == true) {
            stopListening()
            showMessage(getString(R.string.song_search), "")
        }
    }

    /** Offers Shazam and Google's song search as alternatives when those apps are installed. */
    private fun setUpOtherApps() {
        val shazam = packageManager.getLaunchIntentForPackage(SHAZAM_PACKAGE)
        val google = Intent(GOOGLE_SONG_SEARCH).takeIf { it.resolveActivity(packageManager) != null }
        findViewById<View>(R.id.otherApps).isVisible = shazam != null || google != null
        findViewById<View>(R.id.useShazam).apply {
            isVisible = shazam != null
            setOnClickListener { handOff(shazam!!) }
        }
        findViewById<View>(R.id.useGoogle).apply {
            isVisible = google != null
            setOnClickListener { handOff(google!!) }
        }
    }

    private fun handOff(intent: Intent) {
        // Free the microphone so the other app can use it.
        stopListening()
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    private fun stopListening() {
        job?.cancel()
        recordJob?.cancel()
    }

    private fun listen() {
        stopListening()
        listenGroup.isVisible = true
        art.isVisible = false
        links.isVisible = false
        again.isVisible = false
        status.setText(R.string.listening)
        sub.setText(R.string.listening_sub)
        pulse.start()

        recorded = 0
        recordJob = lifecycleScope.launch(Dispatchers.IO) { record() }
        job = lifecycleScope.launch {
            var lastError: String? = null
            for (seconds in ATTEMPTS) {
                while (recorded < seconds * SAMPLE_RATE) {
                    if (recordJob?.isActive != true) break
                    delay(100)
                }
                val length = recorded
                if (length < SAMPLE_RATE) {
                    showMessage(getString(R.string.mic_error), "")
                    return@launch
                }
                if (seconds == ATTEMPTS.last()) status.setText(R.string.identifying)
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val signature = SignatureGenerator().apply { feed(audio, 0, length) }
                        SongRecognizer.identify(signature)
                    }
                }
                val song = result.getOrNull()
                if (song != null) {
                    recordJob?.cancel()
                    showSong(song)
                    return@launch
                }
                lastError = result.exceptionOrNull()?.message
                if (recordJob?.isActive != true) break
            }
            recordJob?.cancel()
            if (lastError != null) showMessage(getString(R.string.not_found), lastError)
            else showMessage(getString(R.string.not_found), getString(R.string.not_found_sub))
        }
    }

    @SuppressLint("MissingPermission") // checked before listen() runs
    private suspend fun record() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE)
            )
        } catch (_: Exception) {
            return
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return
            recorder.startRecording()
            while (coroutineContext.isActive && recorded < audio.size) {
                val n = recorder.read(audio, recorded, minOf(SAMPLE_RATE / 10, audio.size - recorded))
                if (n < 0) break
                recorded += n
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun showMessage(title: String, detail: String) {
        pulse.cancel()
        status.text = title
        sub.text = detail
        again.isVisible = true
    }

    private fun showSong(song: Song) {
        pulse.cancel()
        listenGroup.isVisible = false
        status.text = song.title
        sub.text = listOfNotNull(
            song.artist.takeIf { it.isNotBlank() }, song.album, song.releaseDate
        ).joinToString(" · ")
        links.isVisible = true
        again.isVisible = true

        val query = "${song.artist} ${song.title}".trim()
        findViewById<View>(R.id.openSong).apply {
            isVisible = song.link != null
            setOnClickListener { open(song.link!!) }
        }
        findViewById<View>(R.id.youtube).setOnClickListener { open(Links.youtube(query)) }
        findViewById<View>(R.id.spotify).setOnClickListener { open(Links.spotify(query)) }

        song.artUrl?.let { url ->
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { SongRecognizer.loadImage(url) }
                if (bmp != null) {
                    art.setImageBitmap(bmp)
                    art.isVisible = true
                }
            }
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
