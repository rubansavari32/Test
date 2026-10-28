package com.rubansavari.budsspatial

import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates a pulsing noise source that orbits around the listener's head.
 *
 * - [Mode.SURROUND] renders genuine 5.1 PCM. When the phone's spatializer is on, Android
 *   turns those six channels into binaural audio for the buds (with head tracking if the
 *   buds expose a head tracker), which is exactly the path movies and Dolby content take.
 * - [Mode.BINAURAL] is a fallback for phones without a spatializer: the app itself applies
 *   simple interaural time/level differences and a "behind" low-pass, rendered as stereo.
 */
class SpatialTestTone {

    enum class Mode { SURROUND, BINAURAL }

    private val _azimuth = MutableStateFlow(0f)
    /** Current source direction in degrees: 0 = front, 90 = right, 180 = behind. */
    val azimuth: StateFlow<Float> = _azimuth.asStateFlow()

    private val _playing = MutableStateFlow<Mode?>(null)
    val playing: StateFlow<Mode?> = _playing.asStateFlow()

    /** Seconds per full orbit. */
    @Volatile var orbitSeconds = 8f

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(mode: Mode) {
        stop()
        running = true
        _playing.value = mode
        thread = Thread({ render(mode) }, "SpatialTestTone").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        _playing.value = null
    }

    private fun render(mode: Mode) {
        val channels = if (mode == Mode.SURROUND) 6 else 2
        val mask = if (mode == Mode.SURROUND) AudioFormat.CHANNEL_OUT_5POINT1 else AudioFormat.CHANNEL_OUT_STEREO
        val format = SpatialAudioMonitor.pcmFormat(mask, SAMPLE_RATE)
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_FLOAT)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(SpatialAudioMonitor.mediaAttributes())
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, BLOCK * channels * 4) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            running = false
            _playing.value = null
            return
        }

        val out = FloatArray(BLOCK * channels)
        val source = SourceGenerator()
        val binaural = BinauralPanner()
        var angle = 0.0

        track.play()
        while (running) {
            val step = 360.0 / (orbitSeconds * SAMPLE_RATE)
            for (i in 0 until BLOCK) {
                val s = source.next()
                if (mode == Mode.SURROUND) {
                    panSurround(s, angle, out, i * 6)
                } else {
                    binaural.process(s, angle, out, i * 2)
                }
                angle = (angle + step) % 360.0
            }
            _azimuth.value = angle.toFloat()
            track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
        }
        track.pause()
        track.flush()
        track.release()
    }

    /** Constant-power pairwise panning across the five full-range 5.1 speakers. */
    private fun panSurround(sample: Float, azimuth: Double, out: FloatArray, offset: Int) {
        out.fill(0f, offset, offset + 6)
        for (i in SPEAKERS.indices) {
            val (a0, ch0) = SPEAKERS[i]
            val (a1raw, ch1) = SPEAKERS[(i + 1) % SPEAKERS.size]
            val a1 = if (a1raw <= a0) a1raw + 360.0 else a1raw
            if (azimuth >= a0 && azimuth < a1) {
                val t = (azimuth - a0) / (a1 - a0) * (PI / 2)
                out[offset + ch0] = (sample * cos(t)).toFloat()
                out[offset + ch1] = (sample * sin(t)).toFloat()
                return
            }
        }
    }

    /** Short band-limited noise bursts: much easier to localise than a pure tone. */
    private class SourceGenerator {
        private val random = Random(42)
        private var lp = 0f
        private var hp = 0f
        private var n = 0

        fun next(): Float {
            val white = random.nextFloat() * 2f - 1f
            lp += 0.35f * (white - lp)           // gentle low-pass (~3 kHz)
            val band = lp - hp
            hp += 0.02f * (lp - hp)              // remove rumble
            val pos = n % PULSE_PERIOD
            n = (n + 1) % (PULSE_PERIOD * 1000)
            val env = when {
                pos < ATTACK -> pos.toFloat() / ATTACK
                pos < PULSE_ON -> 1f
                pos < PULSE_ON + ATTACK -> 1f - (pos - PULSE_ON).toFloat() / ATTACK
                else -> 0f
            }
            return band * env * 0.6f
        }
    }

    /** Minimal ITD/ILD head model for phones whose OS has no spatializer. */
    private class BinauralPanner {
        private val history = FloatArray(64)
        private var write = 0
        private var behindLp = 0f

        fun process(sample: Float, azimuthDeg: Double, out: FloatArray, offset: Int) {
            val az = azimuthDeg * PI / 180.0
            val side = sin(az)                       // -1 left .. +1 right
            val front = cos(az)                      // +1 front .. -1 behind

            // Sources behind the head sound duller; blend in a low-passed copy.
            behindLp += 0.15f * (sample - behindLp)
            val behind = ((1.0 - front) / 2.0).toFloat()
            val s = sample * (1f - 0.7f * behind) + behindLp * 0.7f * behind

            history[write] = s
            val delay = (abs(side) * MAX_ITD_SAMPLES).roundToInt()
            val delayed = history[(write - delay + history.size) % history.size]
            write = (write + 1) % history.size

            val near = (0.5 + 0.5 * abs(side)).toFloat()
            val far = (0.5 - 0.35 * abs(side)).toFloat()
            if (side >= 0) {
                out[offset] = delayed * far       // left ear: later and quieter
                out[offset + 1] = s * near
            } else {
                out[offset] = s * near
                out[offset + 1] = delayed * far
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 960                       // 20 ms
        const val PULSE_PERIOD = SAMPLE_RATE / 4    // four bursts per second
        const val PULSE_ON = SAMPLE_RATE / 7
        const val ATTACK = 240
        const val MAX_ITD_SAMPLES = 31              // ~0.65 ms at 48 kHz

        // (azimuth in degrees, interleaved channel index) for FC, FR, BR, BL, FL.
        // Android 5.1 order: FL=0, FR=1, FC=2, LFE=3, BL=4, BR=5.
        val SPEAKERS = listOf(0.0 to 2, 30.0 to 1, 110.0 to 5, 250.0 to 4, 330.0 to 0)
    }
}
