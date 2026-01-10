package de.cs.transkribio

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.cs.transkribio.data.Recording
import de.cs.transkribio.data.RecordingSegment
import de.cs.transkribio.data.RecordingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TranscriptionSegment(
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val speakerId: Int = -1,
    val startTimeSec: Float = 0f,
    val endTimeSec: Float = 0f
)

data class TranscriptionState(
    val isRecording: Boolean = false,
    val isInitializing: Boolean = false,
    val isProcessing: Boolean = false,
    val isDiarizing: Boolean = false,
    val partialText: String = "",
    val transcriptionHistory: List<TranscriptionSegment> = emptyList(),
    val error: String? = null,
    val audioAmplitude: Float = 0f,
    val waveformAmplitudes: List<Float> = List(32) { 0f },
    val speakerCount: Int = 0,
    val currentRecordingId: Long? = null,
    val currentRecordingName: String = "",
    val recordingDurationMs: Long = 0
)

class TranscriptionViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "TranscriptionViewModel"
    }

    private val _uiState = MutableStateFlow(TranscriptionState())
    val uiState: StateFlow<TranscriptionState> = _uiState.asStateFlow()

    private val repository: RecordingsRepository = TranskribioApp.instance.repository
    private val audioRecorder = AudioRecorder()

    // WakeLock to keep device awake during recording
    private var wakeLock: PowerManager.WakeLock? = null

    // Async pipeline channels
    private var audioChannel: Channel<FloatArray>? = null
    private var recordingJob: Job? = null
    private var processingJob: Job? = null

    // Audio buffer for speaker diarization
    private val recordedSamples = mutableListOf<FloatArray>()
    private var recordingStartTime: Long = 0L
    private var currentSampleCount: Int = 0

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isInitializing = true) }
            try {
                withContext(Dispatchers.IO) {
                    SherpaRecognizer.init(getApplication())
                    try {
                        SpeakerDiarizer.init(getApplication())
                        Log.d(TAG, "Speaker diarizer initialized")
                    } catch (e: Exception) {
                        Log.w(TAG, "Speaker diarization unavailable: ${e.message}")
                    }
                }
                _uiState.update { it.copy(isInitializing = false) }
                Log.d(TAG, "Recognizer initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize recognizer", e)
                _uiState.update {
                    it.copy(
                        isInitializing = false,
                        error = "Failed to initialize: ${e.message}"
                    )
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Transkribio::RecordingWakeLock"
            )
        }
        wakeLock?.acquire(60 * 60 * 1000L) // 1 hour max
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startNewRecording()
        }
    }

    fun startNewRecording(name: String? = null) {
        if (_uiState.value.isInitializing || _uiState.value.isRecording) {
            return
        }

        viewModelScope.launch {
            // Create new recording in database
            val recordingName = name ?: "Recording ${System.currentTimeMillis()}"
            val recording = Recording(
                name = recordingName,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val recordingId = repository.insert(recording)

            _uiState.update {
                it.copy(
                    currentRecordingId = recordingId,
                    currentRecordingName = recordingName,
                    transcriptionHistory = emptyList(),
                    speakerCount = 0
                )
            }

            startRecordingInternal()
        }
    }

    fun resumeRecording(recordingId: Long) {
        if (_uiState.value.isInitializing || _uiState.value.isRecording) {
            return
        }

        viewModelScope.launch {
            val recording = repository.getRecordingById(recordingId) ?: return@launch

            // Convert stored segments to TranscriptionSegments
            val existingSegments = recording.segments.map { segment ->
                TranscriptionSegment(
                    text = segment.text,
                    timestamp = segment.timestamp,
                    speakerId = segment.speakerId,
                    startTimeSec = segment.startTimeSec,
                    endTimeSec = segment.endTimeSec
                )
            }

            _uiState.update {
                it.copy(
                    currentRecordingId = recordingId,
                    currentRecordingName = recording.name,
                    transcriptionHistory = existingSegments,
                    speakerCount = recording.speakerCount,
                    recordingDurationMs = recording.durationMs
                )
            }

            startRecordingInternal()
        }
    }

    private fun startRecordingInternal() {
        if (_uiState.value.isInitializing) {
            Log.w(TAG, "Cannot start recording while initializing")
            return
        }

        // Acquire wake lock to keep device awake
        acquireWakeLock()

        SherpaRecognizer.reset()

        // Clear audio buffer for new recording session
        recordedSamples.clear()
        recordingStartTime = System.currentTimeMillis()
        currentSampleCount = 0

        // Create buffered channel for audio samples
        audioChannel = Channel(capacity = Channel.BUFFERED)

        _uiState.update {
            it.copy(
                isRecording = true,
                partialText = "",
                error = null,
                audioAmplitude = 0f,
                waveformAmplitudes = List(32) { 0f }
            )
        }

        // Start audio capture coroutine
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            audioRecorder.start()
                .catch { e ->
                    Log.e(TAG, "Recording error", e)
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            error = "Recording error: ${e.message}"
                        )
                    }
                }
                .collect { audioData ->
                    // Update waveform
                    _uiState.update {
                        it.copy(
                            audioAmplitude = audioData.amplitude,
                            waveformAmplitudes = audioData.amplitudes,
                            recordingDurationMs = it.recordingDurationMs +
                                (audioData.samples.size * 1000L / AudioRecorder.SAMPLE_RATE)
                        )
                    }

                    // Store samples for speaker diarization
                    recordedSamples.add(audioData.samples.copyOf())
                    currentSampleCount += audioData.samples.size

                    // Send samples to processing channel
                    audioChannel?.trySend(audioData.samples)
                }
        }

        // Start processing coroutine
        processingJob = viewModelScope.launch(Dispatchers.Default) {
            audioChannel?.consumeEach { samples ->
                processAudioAsync(samples)
            }
        }
    }

    private suspend fun processAudioAsync(samples: FloatArray) {
        val hasSegments = SherpaRecognizer.feedAudio(samples)

        if (hasSegments) {
            _uiState.update { it.copy(isProcessing = true) }

            val results = withContext(Dispatchers.Default) {
                SherpaRecognizer.processSegments()
            }

            if (results.isNotEmpty()) {
                _uiState.update { state ->
                    val newSegments = results.map { TranscriptionSegment(it) }
                    state.copy(
                        transcriptionHistory = state.transcriptionHistory + newSegments,
                        partialText = "",
                        isProcessing = false
                    )
                }
            } else {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun stopRecording() {
        // Release wake lock
        releaseWakeLock()

        // Stop audio capture
        recordingJob?.cancel()
        recordingJob = null
        audioRecorder.stop()

        // Close channel
        audioChannel?.close()
        audioChannel = null

        // Cancel processing
        processingJob?.cancel()
        processingJob = null

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }

            // Flush remaining audio
            withContext(Dispatchers.Default) {
                val finalResult = SherpaRecognizer.flush()
                if (finalResult != null) {
                    _uiState.update { state ->
                        state.copy(
                            transcriptionHistory = state.transcriptionHistory +
                                    TranscriptionSegment(finalResult)
                        )
                    }
                }
            }

            _uiState.update {
                it.copy(
                    isRecording = false,
                    isProcessing = false,
                    audioAmplitude = 0f,
                    waveformAmplitudes = List(32) { 0f }
                )
            }

            // Run speaker diarization
            if (SpeakerDiarizer.isReady() && recordedSamples.isNotEmpty()) {
                runSpeakerDiarization()
            }

            // Save recording to database
            saveCurrentRecording()
        }
    }

    private suspend fun runSpeakerDiarization() {
        _uiState.update { it.copy(isDiarizing = true) }

        try {
            val totalSamples = FloatArray(currentSampleCount)
            var offset = 0
            for (chunk in recordedSamples) {
                chunk.copyInto(totalSamples, offset)
                offset += chunk.size
            }

            Log.d(TAG, "Running diarization on ${totalSamples.size} samples")

            val diarizedSegments = SpeakerDiarizer.process(totalSamples)

            if (diarizedSegments.isNotEmpty()) {
                val updatedHistory = matchSpeakersToTranscription(diarizedSegments)
                val speakerCount = diarizedSegments.map { it.speakerId }.distinct().size

                _uiState.update { state ->
                    state.copy(
                        transcriptionHistory = updatedHistory,
                        speakerCount = speakerCount
                    )
                }

                Log.d(TAG, "Diarization complete: $speakerCount speakers")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Diarization failed", e)
        } finally {
            _uiState.update { it.copy(isDiarizing = false) }
            recordedSamples.clear()
        }
    }

    private fun matchSpeakersToTranscription(
        diarizedSegments: List<DiarizedSegment>
    ): List<TranscriptionSegment> {
        val history = _uiState.value.transcriptionHistory
        if (history.isEmpty() || diarizedSegments.isEmpty()) return history

        val totalDuration = currentSampleCount / 16000f

        return history.mapIndexed { index, segment ->
            val estimatedTime = (index.toFloat() / history.size) * totalDuration

            val matchingDiarization = diarizedSegments.find { diar ->
                estimatedTime >= diar.startTime && estimatedTime <= diar.endTime
            } ?: diarizedSegments.minByOrNull { diar ->
                minOf(
                    kotlin.math.abs(diar.startTime - estimatedTime),
                    kotlin.math.abs(diar.endTime - estimatedTime)
                )
            }

            segment.copy(
                speakerId = matchingDiarization?.speakerId ?: -1,
                startTimeSec = matchingDiarization?.startTime ?: 0f,
                endTimeSec = matchingDiarization?.endTime ?: 0f
            )
        }
    }

    private suspend fun saveCurrentRecording() {
        val state = _uiState.value
        val recordingId = state.currentRecordingId ?: return

        val segments = state.transcriptionHistory.map { segment ->
            RecordingSegment(
                text = segment.text,
                speakerId = segment.speakerId,
                startTimeSec = segment.startTimeSec,
                endTimeSec = segment.endTimeSec,
                timestamp = segment.timestamp
            )
        }

        val recording = Recording(
            id = recordingId,
            name = state.currentRecordingName,
            updatedAt = System.currentTimeMillis(),
            durationMs = state.recordingDurationMs,
            segments = segments,
            speakerCount = state.speakerCount,
            isComplete = true
        )

        repository.update(recording)
        Log.d(TAG, "Recording saved: ${recording.name}")
    }

    fun updateRecordingName(name: String) {
        viewModelScope.launch {
            _uiState.value.currentRecordingId?.let { id ->
                repository.updateName(id, name)
                _uiState.update { it.copy(currentRecordingName = name) }
            }
        }
    }

    fun clearTranscription() {
        _uiState.update {
            it.copy(
                transcriptionHistory = emptyList(),
                partialText = "",
                currentRecordingId = null,
                currentRecordingName = "",
                recordingDurationMs = 0,
                speakerCount = 0
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun loadRecording(recordingId: Long) {
        viewModelScope.launch {
            val recording = repository.getRecordingById(recordingId) ?: return@launch

            val segments = recording.segments.map { segment ->
                TranscriptionSegment(
                    text = segment.text,
                    timestamp = segment.timestamp,
                    speakerId = segment.speakerId,
                    startTimeSec = segment.startTimeSec,
                    endTimeSec = segment.endTimeSec
                )
            }

            _uiState.update {
                it.copy(
                    currentRecordingId = recordingId,
                    currentRecordingName = recording.name,
                    transcriptionHistory = segments,
                    speakerCount = recording.speakerCount,
                    recordingDurationMs = recording.durationMs
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        releaseWakeLock()
        recordingJob?.cancel()
        processingJob?.cancel()
        audioChannel?.close()
        audioRecorder.stop()
        recordedSamples.clear()
        SherpaRecognizer.release()
        SpeakerDiarizer.release()
    }
}
