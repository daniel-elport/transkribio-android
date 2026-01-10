package de.cs.transkribio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

data class AudioData(
    val samples: FloatArray,
    val amplitude: Float,
    val amplitudes: List<Float>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioData
        return samples.contentEquals(other.samples) && amplitude == other.amplitude
    }

    override fun hashCode(): Int {
        return samples.contentHashCode() * 31 + amplitude.hashCode()
    }
}

class AudioRecorder {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WAVEFORM_BARS = 32

        // Smaller buffer = lower latency (100ms chunks)
        private const val BUFFER_SIZE_MS = 100
    }

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false

    // Pre-allocated buffers for zero-allocation audio processing
    private var shortBuffer: ShortArray? = null
    private var floatBuffer: FloatArray? = null

    private val bufferSize: Int by lazy {
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val targetSize = (SAMPLE_RATE * BUFFER_SIZE_MS) / 1000
        maxOf(minSize, targetSize)
    }

    @SuppressLint("MissingPermission")
    fun start(): Flow<AudioData> = flow {
        try {
            // Pre-allocate buffers
            val samplesPerRead = bufferSize / 2
            shortBuffer = ShortArray(samplesPerRead)
            floatBuffer = FloatArray(samplesPerRead)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2  // Double buffer for safety
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return@flow
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "Recording started with ${BUFFER_SIZE_MS}ms chunks")

            val localShortBuffer = shortBuffer!!
            val localFloatBuffer = floatBuffer!!

            while (coroutineContext.isActive && isRecording) {
                val readCount = audioRecord?.read(localShortBuffer, 0, localShortBuffer.size) ?: -1

                if (readCount > 0) {
                    // Fast inline conversion - no intermediate allocations
                    for (i in 0 until readCount) {
                        localFloatBuffer[i] = localShortBuffer[i] / 32768.0f
                    }

                    // Create output array with exact size
                    val samples = localFloatBuffer.copyOf(readCount)
                    val amplitude = calculateAmplitudeFast(samples)
                    val amplitudes = calculateWaveformBarsFast(samples)

                    emit(AudioData(samples, amplitude, amplitudes))
                } else if (readCount < 0) {
                    Log.e(TAG, "AudioRecord read error: $readCount")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
        } finally {
            stop()
        }
    }.flowOn(Dispatchers.IO)

    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            shortBuffer = null
            floatBuffer = null
            Log.d(TAG, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    // Optimized amplitude calculation - single pass
    private fun calculateAmplitudeFast(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0f
        for (i in samples.indices) {
            val v = samples[i]
            sum += if (v >= 0) v else -v
        }
        return (sum / samples.size).coerceIn(0f, 1f)
    }

    // Optimized waveform calculation - single pass with chunking
    private fun calculateWaveformBarsFast(samples: FloatArray): List<Float> {
        if (samples.isEmpty()) return List(WAVEFORM_BARS) { 0f }

        val chunkSize = samples.size / WAVEFORM_BARS
        if (chunkSize == 0) return List(WAVEFORM_BARS) { 0f }

        val result = FloatArray(WAVEFORM_BARS)
        var sampleIndex = 0

        for (bar in 0 until WAVEFORM_BARS) {
            var maxAmp = 0f
            val end = minOf(sampleIndex + chunkSize, samples.size)

            while (sampleIndex < end) {
                val v = samples[sampleIndex]
                val absVal = if (v >= 0) v else -v
                if (absVal > maxAmp) maxAmp = absVal
                sampleIndex++
            }

            result[bar] = (maxAmp * 2.5f).coerceIn(0f, 1f)
        }

        return result.toList()
    }
}
