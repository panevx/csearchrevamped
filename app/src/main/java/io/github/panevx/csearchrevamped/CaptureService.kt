package io.github.panevx.csearchrevamped

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import io.github.panevx.csearchrevamped.translate.LiveTranslator

/**
 * Takes the screenshot that the overlay lets you circle. Uses the accessibility screenshot API
 * so there is no screen-recording consent dialog on every search.
 */
class CaptureService : AccessibilityService() {

    companion object {
        @Volatile var instance: CaptureService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var live: LiveTranslator? = null

    override fun onServiceConnected() {
        instance = this
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) = capture(150)
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) live?.onEvent(event)
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopLiveTranslate()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopLiveTranslate()
        instance = null
        super.onDestroy()
    }

    /** Translates the app on screen in place, following along as the user scrolls. */
    fun startLiveTranslate(delayMs: Long = 0) {
        stopLiveTranslate()
        handler.postDelayed({
            live = LiveTranslator(this) { live = null }.also { it.start() }
        }, delayMs)
    }

    fun stopLiveTranslate() {
        live?.stop()
        live = null
    }

    fun capture(delayMs: Long = 0, dismissShade: Boolean = false) {
        stopLiveTranslate()
        if (dismissShade) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            }
        }
        handler.postDelayed({ takeShot() }, delayMs)
    }

    private fun takeShot() {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                buffer.close()
                if (bmp == null) {
                    onFailure(-1)
                    return
                }
                ScreenshotHolder.screen = bmp
                startActivity(OverlayActivity.intent(this@CaptureService))
            }

            override fun onFailure(errorCode: Int) {
                Toast.makeText(
                    this@CaptureService, getString(R.string.capture_failed, errorCode), Toast.LENGTH_SHORT
                ).show()
            }
        })
    }
}
