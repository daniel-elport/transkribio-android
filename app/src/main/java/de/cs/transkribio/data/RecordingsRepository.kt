package de.cs.transkribio.data

import kotlinx.coroutines.flow.Flow

class RecordingsRepository(private val recordingDao: RecordingDao) {

    val allRecordings: Flow<List<Recording>> = recordingDao.getAllRecordings()

    suspend fun getRecordingById(id: Long): Recording? {
        return recordingDao.getRecordingById(id)
    }

    fun getRecordingByIdFlow(id: Long): Flow<Recording?> {
        return recordingDao.getRecordingByIdFlow(id)
    }

    suspend fun insert(recording: Recording): Long {
        return recordingDao.insert(recording)
    }

    suspend fun update(recording: Recording) {
        recordingDao.update(recording)
    }

    suspend fun delete(recording: Recording) {
        recordingDao.delete(recording)
    }

    suspend fun deleteById(id: Long) {
        recordingDao.deleteById(id)
    }

    suspend fun updateName(id: Long, name: String) {
        recordingDao.updateName(id, name)
    }

    suspend fun appendSegments(id: Long, newSegments: List<RecordingSegment>, speakerCount: Int) {
        val recording = recordingDao.getRecordingById(id) ?: return
        val updatedSegments = recording.segments + newSegments
        val converter = Converters()
        recordingDao.updateSegments(id, converter.fromSegmentList(updatedSegments), speakerCount)
    }
}
