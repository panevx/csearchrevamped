package io.github.panevx.csearchrevamped.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.widget.Toast
import io.github.panevx.csearchrevamped.CaptureService
import io.github.panevx.csearchrevamped.MainActivity
import io.github.panevx.csearchrevamped.OverlayActivity
import io.github.panevx.csearchrevamped.R
import io.github.panevx.csearchrevamped.ScreenshotHolder

/** Registers the app as a digital assistant so long-pressing home starts a screen search. */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot != null) {
            ScreenshotHolder.screen = screenshot.copy(Bitmap.Config.ARGB_8888, false)
            startAssistantActivity(OverlayActivity.intent(context))
            hide()
            return
        }
        val service = CaptureService.instance
        if (service != null) {
            hide()
            service.capture(delayMs = 350)
        } else {
            Toast.makeText(context, R.string.no_screenshot, Toast.LENGTH_LONG).show()
            startAssistantActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
    }
}

/** A voice interaction service must name a recognizer; this app does not do speech input. */
class RecognitionStub : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
