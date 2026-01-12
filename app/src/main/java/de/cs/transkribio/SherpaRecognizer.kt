package de.cs.transkribio

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object SherpaRecognizer {
    private const val TAG = "SherpaRecognizer"
    private const val SAMPLE_RATE = 16000
    // NEW: Minimum audio duration to process (2.0s is a sweet spot for Whisper Small)
    private const val MIN_BATCH_SECONDS = 2.0f
    private const val MIN_BATCH_SAMPLES = (MIN_BATCH_SECONDS * SAMPLE_RATE).toInt()
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    // NEW: Buffer to hold short VAD segments
    private var accumulatedSamples = FloatArray(0)
    private val isInitialized = AtomicBoolean(false)
    private val vadLock = ReentrantLock()
    private val recognizerLock = ReentrantLock()

    fun init(context: Context) {
        if (isInitialized.get()) return

        try {
            val modelDir = copyAssetsToInternal(context)
            initVad(modelDir)
            initRecognizer(modelDir)
            isInitialized.set(true)
            Log.d(TAG, "SherpaRecognizer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SherpaRecognizer", e)
            throw e
        }
    }

    private fun copyAssetsToInternal(context: Context): String {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        val assets = listOf(
            "small-encoder.int8.onnx",
            "small-decoder.int8.onnx",
            "small-tokens.txt",
            "silero_vad.onnx"
        )

        for (asset in assets) {
            val outFile = File(modelDir, asset)
            if (!outFile.exists()) {
                context.assets.open(asset).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Copied $asset to ${outFile.absolutePath}")
            }
        }

        return modelDir.absolutePath
    }

    private fun initVad(modelDir: String) {
        // Optimized VAD settings for faster response
        val sileroConfig = SileroVadModelConfig(
            model = "$modelDir/silero_vad.onnx",
            // 0.35f is okay, but 0.5f is often safer to avoid background noise triggering the decoder
            threshold = 0.4f,

            // 0.15f is very aggressive. 0.25f - 0.3f is safer for German to keep phrases together.
            minSilenceDuration = 0.25f,

            // 0.08f is too short (less than 100ms). 0.25f ensures you have actual speech content.
            minSpeechDuration = 0.25f,

            windowSize = 512
        )

        val vadConfig = VadModelConfig(
            sileroVadModelConfig = sileroConfig,
            sampleRate = SAMPLE_RATE,
            numThreads = 1, // VAD is light; 1 thread is usually enough and saves CPU
            debug = false
        )

        vad = Vad(assetManager = null, config = vadConfig)
        Log.d(TAG, "VAD initialized with optimized settings")
    }

    private fun initRecognizer(modelDir: String) {
        val whisperConfig = OfflineWhisperModelConfig(
            encoder = "$modelDir/small-encoder.int8.onnx",
            decoder = "$modelDir/small-decoder.int8.onnx",
            language = "de",
            task = "transcribe",
            tailPaddings = -1
        )

        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
            tokens = "$modelDir/small-tokens.txt",
            // Cap threads to avoid thermal throttling on Android.
            // 4 is usually the sweet spot for small models.
            numThreads = 4,
            debug = false,
            modelType = "whisper"
        )

        val featConfig = FeatureConfig(
            sampleRate = SAMPLE_RATE,
            featureDim = 80
        )

        val config = OfflineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        recognizer = OfflineRecognizer(config = config)
        Log.d(TAG, "Recognizer initialized with ${modelConfig.numThreads} threads")
    }

    /**
     * Feed audio samples to VAD. Non-blocking for VAD part.
     * Returns true if there are segments ready for transcription.
     */
    fun feedAudio(samples: FloatArray): Boolean {
        if (!isInitialized.get()) return false

        vadLock.withLock {
            vad?.acceptWaveform(samples)
            return vad?.empty() == false
        }
    }

    /**
     * Optimized: Accumulates segments until we reach MIN_BATCH_SAMPLES.
     * This prevents Whisper from processing tiny 0.2s clips which cause hallucinations.
     */
    fun processSegments(): List<String> {
        if (!isInitialized.get()) return emptyList()

        var samplesToProcess: FloatArray? = null

        // 1. Critical Section: Extract from VAD and manage Buffer
        vadLock.withLock {
            val v = vad ?: return emptyList()

            // If VAD has new segments, pop them all and append to our buffer
            if (!v.empty()) {
                val newSegments = mutableListOf<FloatArray>()
                var totalNewSamples = 0

                while (!v.empty()) {
                    val segment = v.front()
                    newSegments.add(segment.samples)
                    totalNewSamples += segment.samples.size
                    v.pop()
                }

                // Efficiently combine old buffer + new segments
                accumulatedSamples = mergeArrays(accumulatedSamples, newSegments, totalNewSamples)
            }

            // 2. Check if buffer is big enough to be worth transcribing
            if (accumulatedSamples.size >= MIN_BATCH_SAMPLES) {
                samplesToProcess = accumulatedSamples
                accumulatedSamples = FloatArray(0) // Clear buffer
            }
        }

        // 3. Heavy Lifting (Outside Lock)
        // Only run inference if we grabbed a batch
        if (samplesToProcess != null) {
            val text = transcribeSegment(samplesToProcess!!)
            if (text.isNotEmpty()) {
                return listOf(text)
            }
        }

        return emptyList()
    }

    /**
     * Forces processing of any remaining audio in the buffer (e.g., end of recording).
     */
    fun flush(): String? {
        if (!isInitialized.get()) return null

        var samplesToProcess: FloatArray? = null

        vadLock.withLock {
            val v = vad ?: return null
            v.flush() // Tell VAD no more audio is coming

            // Collect any final bits from VAD
            val newSegments = mutableListOf<FloatArray>()
            var totalNewSamples = 0
            while (!v.empty()) {
                val segment = v.front()
                newSegments.add(segment.samples)
                totalNewSamples += segment.samples.size
                v.pop()
            }

            // Combine with whatever was buffering
            val finalBatch = mergeArrays(accumulatedSamples, newSegments, totalNewSamples)
            accumulatedSamples = FloatArray(0) // Reset

            if (finalBatch.isNotEmpty()) {
                samplesToProcess = finalBatch
            }
        }

        if (samplesToProcess != null) {
            return transcribeSegment(samplesToProcess!!)
        }

        return null
    }

    // Helper to merge arrays without creating too many intermediate objects
    private fun mergeArrays(current: FloatArray, newSegments: List<FloatArray>, newSize: Int): FloatArray {
        if (newSegments.isEmpty()) return current

        val result = FloatArray(current.size + newSize)
        // Copy existing buffer
        System.arraycopy(current, 0, result, 0, current.size)

        // Append new segments
        var offset = current.size
        for (seg in newSegments) {
            System.arraycopy(seg, 0, result, offset, seg.size)
            offset += seg.size
        }
        return result
    }

    private fun transcribeSegment(samples: FloatArray): String {
        recognizerLock.withLock {
            val r = recognizer ?: return ""

            val stream = r.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            val result = r.getResult(stream)
            stream.release()

            val rawText = result.text.trim()
            if (rawText.isEmpty()) return ""

            // ALWAYS apply general cleanup (removes bracket tokens like [MOTOR, [MUSIK], etc.)
            var processedText = GermanTextProcessor.generalCleanup(rawText)
            if (processedText.isEmpty()) {
                Log.d(TAG, "Filtered (general cleanup): $rawText")
                return ""
            }

            // Check if German post-processing is enabled
            val germanPostProcessingEnabled = TranskribioApp.instance.settingsManager.postProcessingEnabled.value

            if (germanPostProcessingEnabled) {
                // Check if it's likely noise/false positive
                if (GermanTextProcessor.isNoise(processedText)) {
                    Log.d(TAG, "Filtered noise: $rawText")
                    return ""
                }

                // Apply German text post-processing
                processedText = GermanTextProcessor.processSegment(processedText)
                if (processedText.isNotEmpty()) {
                    Log.d(TAG, "Transcribed: $rawText -> $processedText")
                }
                return processedText
            } else {
                Log.d(TAG, "Transcribed: $rawText -> $processedText")
                return processedText
            }
        }
    }

    /**
     * Legacy method for compatibility - combines feed and process
     */
    fun processAudio(samples: FloatArray): String? {
        if (!isInitialized.get()) {
            Log.w(TAG, "Recognizer not initialized")
            return null
        }

        feedAudio(samples)
        val results = processSegments()
        return results.firstOrNull()
    }



    fun hasPendingSegments(): Boolean {
        if (!isInitialized.get()) return false
        vadLock.withLock {
            return vad?.empty() == false
        }
    }

    fun reset() {
        vadLock.withLock {
            vad?.clear()
            accumulatedSamples = FloatArray(0) // Clear our custom buffer too
        }
    }

    fun release() {
        vadLock.withLock {
            recognizerLock.withLock {
                vad?.release()
                recognizer?.release()
                vad = null
                recognizer = null
                isInitialized.set(false)
                Log.d(TAG, "SherpaRecognizer released")
            }
        }
    }
}
