package de.cs.transkribio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

data class DiarizedSegment(
    val speakerId: Int,
    val startTime: Float,
    val endTime: Float,
    val text: String = ""
)

object SpeakerDiarizer {
    private const val TAG = "SpeakerDiarizer"
    private const val SAMPLE_RATE = 16000

    private var diarizer: OfflineSpeakerDiarization? = null
    private val isInitialized = AtomicBoolean(false)

    suspend fun init(context: Context) {
        if (isInitialized.get()) return

        withContext(Dispatchers.IO) {
            try {
                val modelDir = copyModelsToInternal(context)
                initDiarizer(modelDir)
                isInitialized.set(true)
                Log.d(TAG, "SpeakerDiarizer initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SpeakerDiarizer", e)
                throw e
            }
        }
    }

    private fun copyModelsToInternal(context: Context): String {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        val assets = listOf(
            "pyannote-segmentation-3.0.onnx",
            "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
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

    private fun initDiarizer(modelDir: String) {
        val pyannoteConfig = OfflineSpeakerSegmentationPyannoteModelConfig(
            model = "$modelDir/pyannote-segmentation-3.0.onnx"
        )

        val segmentationConfig = OfflineSpeakerSegmentationModelConfig(
            pyannote = pyannoteConfig,
            numThreads = 2,
            debug = false
        )

        val embeddingConfig = SpeakerEmbeddingExtractorConfig(
            model = "$modelDir/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
            numThreads = 2,
            debug = false
        )

        val clusteringConfig = FastClusteringConfig(
            numClusters = -1,  // Auto-detect number of speakers
            threshold = 0.5f   // Clustering threshold
        )

        val config = OfflineSpeakerDiarizationConfig(
            segmentation = segmentationConfig,
            embedding = embeddingConfig,
            clustering = clusteringConfig,
            minDurationOn = 0.2f,    // Minimum speech duration
            minDurationOff = 0.5f    // Minimum silence duration
        )

        diarizer = OfflineSpeakerDiarization(config = config)
        Log.d(TAG, "Diarizer initialized with auto speaker detection")
    }

    /**
     * Process audio samples and return speaker-segmented results.
     * This is an offline operation - run after recording stops.
     */
    suspend fun process(samples: FloatArray): List<DiarizedSegment> {
        if (!isInitialized.get()) {
            Log.w(TAG, "SpeakerDiarizer not initialized")
            return emptyList()
        }

        return withContext(Dispatchers.Default) {
            try {
                val d = diarizer ?: return@withContext emptyList()

                val segments = d.process(samples)

                segments.map { segment ->
                    DiarizedSegment(
                        speakerId = segment.speaker,
                        startTime = segment.start,
                        endTime = segment.end
                    )
                }.also {
                    Log.d(TAG, "Diarization complete: ${it.size} segments, " +
                            "${it.map { s -> s.speakerId }.distinct().size} speakers")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Diarization failed", e)
                emptyList()
            }
        }
    }

    /**
     * Get the expected sample rate for audio input
     */
    fun getSampleRate(): Int = SAMPLE_RATE

    fun isReady(): Boolean = isInitialized.get()

    fun release() {
        diarizer?.release()
        diarizer = null
        isInitialized.set(false)
        Log.d(TAG, "SpeakerDiarizer released")
    }
}
