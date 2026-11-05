package io.github.panevx.csearchrevamped.music

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Builds a Shazam-compatible audio fingerprint from 16 kHz mono 16-bit PCM.
 * Kotlin port of the generator in SongRec (https://github.com/marin-m/SongRec, GPL-3.0).
 */
class SignatureGenerator {

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FFT_SIZE = 2048
        const val BINS = FFT_SIZE / 2 + 1
        const val RING = 256
        const val CHUNK = 128

        val HANNING = FloatArray(FFT_SIZE) { i -> (0.5 - 0.5 * cos(2 * PI * (i + 1) / (FFT_SIZE + 1))).toFloat() }
        val NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        val OTHERS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
        val FORMER = intArrayOf(1, 3, 6)

        // Radix-2 FFT tables
        val COS = DoubleArray(FFT_SIZE / 2) { cos(-2 * PI * it / FFT_SIZE) }
        val SIN = DoubleArray(FFT_SIZE / 2) { sin(-2 * PI * it / FFT_SIZE) }
        val REVERSED = IntArray(FFT_SIZE) { Integer.reverse(it) ushr (32 - 11) }
    }

    private val samples = FloatArray(FFT_SIZE)
    private var samplesIndex = 0
    private val fftOutputs = Array(RING) { FloatArray(BINS) }
    private var fftIndex = 0
    private val spreadOutputs = Array(RING) { FloatArray(BINS) }
    private var spreadIndex = 0
    private var spreadWritten = 0
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)

    /** Peaks per frequency band, each entry is (fft pass number, magnitude, corrected bin). */
    private val bands = Array(4) { ArrayList<IntArray>() }
    private var numSamples = 0

    val sampleMs: Long get() = numSamples * 1000L / SAMPLE_RATE

    fun feed(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size) {
        numSamples += length
        var i = offset
        while (i + CHUNK <= offset + length) {
            for (j in 0 until CHUNK) samples[samplesIndex + j] = pcm[i + j].toFloat()
            samplesIndex = (samplesIndex + CHUNK) % FFT_SIZE
            doFft()
            doPeakSpreading()
            if (spreadWritten >= 46) doPeakRecognition()
            i += CHUNK
        }
    }

    private fun doFft() {
        for (i in 0 until FFT_SIZE) {
            val r = REVERSED[i]
            re[r] = (samples[(samplesIndex + i) % FFT_SIZE] * HANNING[i]).toDouble()
            im[r] = 0.0
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size / 2
            val step = FFT_SIZE / size
            var start = 0
            while (start < FFT_SIZE) {
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val wr = COS[k * step]
                    val wi = SIN[k * step]
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
        val out = fftOutputs[fftIndex]
        for (k in 0 until BINS) {
            out[k] = max(((re[k] * re[k] + im[k] * im[k]) / (1 shl 17)).toFloat(), 1e-10f)
        }
        fftIndex = (fftIndex + 1) % RING
    }

    private fun doPeakSpreading() {
        val spread = spreadOutputs[spreadIndex]
        System.arraycopy(fftOutputs[(fftIndex - 1 + RING) % RING], 0, spread, 0, BINS)
        for (p in 0..BINS - 3) spread[p] = max(spread[p], max(spread[p + 1], spread[p + 2]))
        for (f in FORMER) {
            val former = spreadOutputs[(spreadIndex - f + RING) % RING]
            for (p in 0 until BINS) former[p] = max(former[p], spread[p])
        }
        spreadIndex = (spreadIndex + 1) % RING
        spreadWritten++
    }

    private fun magnitude(v: Float) = ln(max(1f / 64, v)) * 1477.3f + 6144f

    private fun doPeakRecognition() {
        val fft46 = fftOutputs[(fftIndex - 46 + RING) % RING]
        val spread49 = spreadOutputs[(spreadIndex - 49 + RING) % RING]
        for (bin in 10 until 1015) {
            val v = fft46[bin]
            if (v < 1f / 64 || v < spread49[bin - 1]) continue

            var maxNeighbor = 0f
            for (o in NEIGHBORS) maxNeighbor = max(maxNeighbor, spread49[bin + o])
            if (v <= maxNeighbor) continue

            var maxOther = maxNeighbor
            for (o in OTHERS) maxOther = max(maxOther, spreadOutputs[(spreadIndex + o + RING) % RING][bin - 1])
            if (v <= maxOther) continue

            val peak = magnitude(v)
            val before = magnitude(fft46[bin - 1])
            val after = magnitude(fft46[bin + 1])
            val variation1 = peak * 2 - before - after
            val variation2 = (after - before) * 32 / variation1
            val correctedBin = bin * 64 + variation2
            val hz = correctedBin * (SAMPLE_RATE / 2f / 1024 / 64)
            val band = when (hz.toInt()) {
                in 250..519 -> 0
                in 520..1449 -> 1
                in 1450..3499 -> 2
                in 3500..5500 -> 3
                else -> continue
            }
            bands[band].add(intArrayOf(spreadWritten - 46, peak.toInt(), correctedBin.toInt()))
        }
    }

    fun encode(): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, peaks) in bands.withIndex()) {
            if (peaks.isEmpty()) continue
            val pb = ByteArrayOutputStream()
            var pass = 0
            for (p in peaks) {
                if (p[0] - pass >= 255) {
                    pb.write(0xff)
                    pb.writeLe(p[0], 4)
                    pass = p[0]
                }
                pb.write(p[0] - pass)
                pb.writeLe(p[1], 2)
                pb.writeLe(p[2], 2)
                pass = p[0]
            }
            val bytes = pb.toByteArray()
            contents.writeLe(0x60030040 + band, 4)
            contents.writeLe(bytes.size, 4)
            contents.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { contents.write(0) }
        }
        val body = contents.toByteArray()
        val buf = ByteBuffer.allocate(48 + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xcafe2580.toInt())
        buf.putInt(0) // crc32, filled in below
        buf.putInt(body.size + 8)
        buf.putInt(0x94119c00.toInt())
        repeat(3) { buf.putInt(0) }
        buf.putInt(3 shl 27) // 16 kHz sample rate id
        repeat(2) { buf.putInt(0) }
        buf.putInt((numSamples + SAMPLE_RATE * 0.24f).toInt())
        buf.putInt((15 shl 19) + 0x40000)
        buf.putInt(0x40000000)
        buf.putInt(body.size + 8)
        buf.put(body)
        val bytes = buf.array()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc.value.toInt())
        return bytes
    }

    fun uri(): String = "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(encode())

    private fun ByteArrayOutputStream.writeLe(value: Int, bytes: Int) {
        for (i in 0 until bytes) write((value ushr (8 * i)) and 0xff)
    }
}
