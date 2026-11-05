package io.github.panevx.csearchrevamped

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Listens through the microphone and identifies the song that is playing. */
class MusicActivity : AppCompatActivity() {

    private companion object {
        const val LISTEN_SECONDS = 10
    }

    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var art: ImageView
    private lateinit var listenGroup: View
    private lateinit var links: View
    private lateinit var again: View
    private lateinit var pulse: ObjectAnimator

    private var recorder: MediaRecorder? = null
    private var job: Job? = null

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
        if (recorder != null) {
            job?.cancel()
            stopRecording()
            showMessage(getString(R.string.song_search), "")
        }
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun listen() {
        job?.cancel()
        listenGroup.isVisible = true
        art.isVisible = false
        links.isVisible = false
        again.isVisible = false
        status.setText(R.string.listening)
        pulse.start()

        job = lifecycleScope.launch {
            val file = File(cacheDir, "song.m4a")
            try {
                startRecording(file)
            } catch (e: Exception) {
                stopRecording()
                showMessage(getString(R.string.mic_error), e.message.orEmpty())
                return@launch
            }
            for (s in LISTEN_SECONDS downTo 1) {
                sub.text = getString(R.string.listening_sub, s)
                delay(1000)
            }
            stopRecording()
            status.setText(R.string.identifying)
            sub.text = ""

            val token = Prefs.auddToken(this@MusicActivity)
            val result = withContext(Dispatchers.IO) { runCatching { SongRecognizer.identify(file, token) } }
            file.delete()
            result.fold(
                onSuccess = { song ->
                    if (song == null) showMessage(getString(R.string.not_found), getString(R.string.not_found_sub))
                    else showSong(song)
                },
                onFailure = { e ->
                    val code = (e as? RecognitionException)?.code
                    val msg = if (code == 900 || code == 901) getString(R.string.audd_token_hint) else e.message.orEmpty()
                    showMessage(getString(R.string.not_found), msg)
                },
            )
        }
    }

    private fun startRecording(file: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        recorder = r
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioChannels(1)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
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
            song.artist.takeIf { it.isNotBlank() }, song.album, song.releaseDate?.take(4)
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
