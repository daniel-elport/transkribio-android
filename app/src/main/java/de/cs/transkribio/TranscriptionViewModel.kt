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
    val timestamp: Long = System.currentTimeMillis()
)

data class TranscriptionState(
    val isRecording: Boolean = false,
    val isStartingRecording: Boolean = false,
    val isInitializing: Boolean = false,
    val isProcessing: Boolean = false,
    val partialText: String = "",
    val transcriptionHistory: List<TranscriptionSegment> = emptyList(),
    val error: String? = null,
    val audioAmplitude: Float = 0f,
    val waveformAmplitudes: List<Float> = List(32) { 0f },
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

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isInitializing = true) }
            try {
                withContext(Dispatchers.IO) {
                    SherpaRecognizer.init(getApplication())
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
        wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max
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
        if (_uiState.value.isInitializing || _uiState.value.isRecording || _uiState.value.isStartingRecording) {
            return
        }

        _uiState.update { it.copy(isStartingRecording = true) }

        viewModelScope.launch {
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
                    recordingDurationMs = 0
                )
            }

            startRecordingInternal()
        }
    }

    fun resumeRecording(recordingId: Long) {
        if (_uiState.value.isInitializing || _uiState.value.isRecording || _uiState.value.isStartingRecording) {
            return
        }

        _uiState.update { it.copy(isStartingRecording = true) }

        viewModelScope.launch {
            val recording = repository.getRecordingById(recordingId)
            if (recording == null) {
                _uiState.update { it.copy(isStartingRecording = false) }
                return@launch
            }

            val existingSegments = recording.segments.map { segment ->
                TranscriptionSegment(
                    text = segment.text,
                    timestamp = segment.timestamp
                )
            }

            _uiState.update {
                it.copy(
                    currentRecordingId = recordingId,
                    currentRecordingName = recording.name,
                    transcriptionHistory = existingSegments,
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

        acquireWakeLock()
        SherpaRecognizer.reset()

        audioChannel = Channel(capacity = Channel.BUFFERED)

        _uiState.update {
            it.copy(
                isRecording = true,
                isStartingRecording = false,
                partialText = "",
                error = null,
                audioAmplitude = 0f,
                waveformAmplitudes = List(32) { 0f }
            )
        }

        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            audioRecorder.start()
                .catch { e ->
                    Log.e(TAG, "Recording error", e)
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            isStartingRecording = false,
                            error = "Recording error: ${e.message}"
                        )
                    }
                }
                .collect { audioData ->
                    _uiState.update {
                        it.copy(
                            audioAmplitude = audioData.amplitude,
                            waveformAmplitudes = audioData.amplitudes,
                            recordingDurationMs = it.recordingDurationMs +
                                (audioData.samples.size * 1000L / AudioRecorder.SAMPLE_RATE)
                        )
                    }

                    audioChannel?.trySend(audioData.samples)
                }
        }

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
        if (!_uiState.value.isRecording) return

        releaseWakeLock()

        // Stop audio capture first
        recordingJob?.cancel()
        recordingJob = null
        audioRecorder.stop()

        // Update UI immediately to show we're stopping
        _uiState.update {
            it.copy(
                isRecording = false,
                isProcessing = true,
                audioAmplitude = 0f,
                waveformAmplitudes = List(32) { 0f }
            )
        }

        // Close channel - this signals processingJob to finish its current work
        audioChannel?.close()
        audioChannel = null

        // Don't cancel processingJob - let it finish processing remaining audio
        // Start a new job to wait for processing and finalize
        viewModelScope.launch {
            // Wait for processing job to complete naturally
            processingJob?.join()
            processingJob = null

            // Flush any remaining audio in the recognizer
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

            _uiState.update { it.copy(isProcessing = false) }
            saveCurrentRecording()
        }
    }

    private suspend fun saveCurrentRecording() {
        val state = _uiState.value
        val recordingId = state.currentRecordingId ?: return

        val segments = state.transcriptionHistory.map { segment ->
            RecordingSegment(
                text = segment.text,
                timestamp = segment.timestamp
            )
        }

        val recording = Recording(
            id = recordingId,
            name = state.currentRecordingName,
            updatedAt = System.currentTimeMillis(),
            durationMs = state.recordingDurationMs,
            segments = segments,
            isComplete = true
        )

        repository.update(recording)
        Log.d(TAG, "Recording saved: ${recording.name}")
    }

    fun resumeCurrentRecording() {
        val recordingId = _uiState.value.currentRecordingId
        if (recordingId != null) {
            resumeRecording(recordingId)
        } else {
            // No current recording, start a new one
            startNewRecording()
        }
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
                recordingDurationMs = 0
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
                    timestamp = segment.timestamp
                )
            }

            _uiState.update {
                it.copy(
                    currentRecordingId = recordingId,
                    currentRecordingName = recording.name,
                    transcriptionHistory = segments,
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
        SherpaRecognizer.release()
    }
}
