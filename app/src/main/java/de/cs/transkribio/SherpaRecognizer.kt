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

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null

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
            threshold = 0.35f,           // More sensitive (was 0.4)
            minSilenceDuration = 0.15f,  // Much faster splits (was 0.3)
            minSpeechDuration = 0.08f,   // Catch very short utterances (was 0.1)
            windowSize = 512
        )

        val vadConfig = VadModelConfig(
            sileroVadModelConfig = sileroConfig,
            sampleRate = SAMPLE_RATE,
            numThreads = 2,
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
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
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
     * Process pending VAD segments and return transcribed text.
     * This is the heavy operation that does Whisper inference.
     */
    fun processSegments(): List<String> {
        if (!isInitialized.get()) return emptyList()

        val results = mutableListOf<String>()

        vadLock.withLock {
            val v = vad ?: return emptyList()

            while (!v.empty()) {
                val segment = v.front()
                val segmentSamples = segment.samples
                v.pop()

                // Release VAD lock during heavy inference
                vadLock.unlock()
                try {
                    val text = transcribeSegment(segmentSamples)
                    if (text.isNotEmpty()) {
                        results.add(text)
                    }
                } finally {
                    vadLock.lock()
                }
            }
        }

        return results
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

    fun flush(): String? {
        if (!isInitialized.get()) return null

        vadLock.withLock {
            vad?.flush()
        }

        val results = processSegments()
        return if (results.isNotEmpty()) results.joinToString(" ") else null
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
