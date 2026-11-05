package io.github.panevx.csearchrevamped.music

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manual check against a real recording. Run with
 * SIG_WAV=/path/16k-mono.wav [SIG_OUT=/path/out.txt] [SIG_RECOGNIZE=1] gradle testDebugUnitTest
 */
class SignatureGeneratorTest {
    @Test
    fun signatureFromWav() {
        val path = System.getenv("SIG_WAV")
        assumeTrue(path != null)
        val bytes = File(path!!).readBytes()
        // Skip to the "data" chunk of a PCM16 WAV file.
        var pos = 12
        while (String(bytes, pos, 4) != "data") pos += 8 + ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val data = ByteBuffer.wrap(bytes, pos + 8, bytes.size - pos - 8).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(minOf(data.remaining(), 16000 * 12)).also { data.get(it) }

        val gen = SignatureGenerator().apply { feed(pcm) }
        System.getenv("SIG_OUT")?.let { File(it).writeText(gen.uri()) }
        if (System.getenv("SIG_RECOGNIZE") == "1") println("RESULT: " + SongRecognizer.identify(gen))
    }
}
