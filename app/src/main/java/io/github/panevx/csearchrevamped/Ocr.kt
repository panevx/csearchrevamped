package io.github.panevx.csearchrevamped

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

object Ocr {
    /** Runs every script recognizer and keeps the one that read the most characters. */
    suspend fun read(bitmap: Bitmap): String = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        val clients = listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
        )
        try {
            clients.map { client ->
                async { runCatching { client.process(image).await().text }.getOrDefault("") }
            }.awaitAll().maxByOrNull { text -> text.count { !it.isWhitespace() } }.orEmpty().trim()
        } finally {
            clients.forEach { it.close() }
        }
    }
}
