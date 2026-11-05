package io.github.panevx.csearchrevamped

import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import io.github.panevx.csearchrevamped.tiles.CaptureTileService
import io.github.panevx.csearchrevamped.tiles.MusicTileService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.btn_step_service_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<android.view.View>(R.id.btn_step_assist_button).setOnClickListener {
            val tried = runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            if (tried.isFailure) startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }

        val tileCapture = findViewById<android.view.View>(R.id.btn_step_tile_button)
        val tileMusic = findViewById<android.view.View>(R.id.btn_step_tile_music_button)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tileCapture.setOnClickListener {
                requestTile(CaptureTileService::class.java, R.string.tile_capture, R.drawable.ic_tile)
            }
            tileMusic.setOnClickListener {
                requestTile(MusicTileService::class.java, R.string.tile_music, R.drawable.ic_music)
            }
        } else {
            tileCapture.isVisible = false
            tileMusic.isVisible = false
        }

        findViewById<android.view.View>(R.id.btn_try).setOnClickListener {
            val service = CaptureService.instance
            if (service == null) {
                Toast.makeText(this, R.string.enable_service_first, Toast.LENGTH_LONG).show()
            } else {
                moveTaskToBack(true)
                service.capture(700)
            }
        }
        findViewById<android.view.View>(R.id.btn_music).setOnClickListener {
            startActivity(Intent(this, MusicActivity::class.java))
        }

        findViewById<EditText>(R.id.targetLang).apply {
            setText(Prefs.targetLang(this@MainActivity))
            doAfterTextChanged { Prefs.setTargetLang(this@MainActivity, it.toString()) }
        }
    }

    override fun onResume() {
        super.onResume()
        setStatus(R.id.serviceStatus, isCaptureServiceEnabled(this))
        val assistant = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        setStatus(R.id.assistStatus, assistant)
    }

    private fun setStatus(id: Int, on: Boolean) {
        findViewById<TextView>(id).setText(if (on) R.string.status_on else R.string.status_off)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestTile(cls: Class<*>, label: Int, icon: Int) {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, cls),
            getString(label),
            Icon.createWithResource(this, icon),
            mainExecutor,
        ) { }
    }
}
