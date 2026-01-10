package de.cs.transkribio.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "recordings")
@TypeConverters(Converters::class)
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val segments: List<RecordingSegment> = emptyList(),
    val speakerCount: Int = 0,
    val isComplete: Boolean = false
)

data class RecordingSegment(
    val text: String,
    val speakerId: Int = -1,
    val startTimeSec: Float = 0f,
    val endTimeSec: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromSegmentList(segments: List<RecordingSegment>): String {
        return gson.toJson(segments)
    }

    @TypeConverter
    fun toSegmentList(json: String): List<RecordingSegment> {
        val type = object : TypeToken<List<RecordingSegment>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
}
