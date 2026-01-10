package de.cs.transkribio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.cs.transkribio.data.Recording
import de.cs.transkribio.data.RecordingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecordingsListState(
    val isLoading: Boolean = false,
    val selectedRecordingId: Long? = null,
    val showDeleteDialog: Boolean = false,
    val recordingToDelete: Recording? = null
)

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RecordingsRepository = TranskribioApp.instance.repository

    val recordings: StateFlow<List<Recording>> = repository.allRecordings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(RecordingsListState())
    val uiState: StateFlow<RecordingsListState> = _uiState.asStateFlow()

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            repository.delete(recording)
        }
        _uiState.update { it.copy(showDeleteDialog = false, recordingToDelete = null) }
    }

    fun showDeleteConfirmation(recording: Recording) {
        _uiState.update { it.copy(showDeleteDialog = true, recordingToDelete = recording) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false, recordingToDelete = null) }
    }

    fun renameRecording(recordingId: Long, newName: String) {
        viewModelScope.launch {
            repository.updateName(recordingId, newName)
        }
    }
}
