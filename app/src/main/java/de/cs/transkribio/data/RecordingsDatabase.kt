package de.cs.transkribio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Recording::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RecordingsDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: RecordingsDatabase? = null

        fun getDatabase(context: Context): RecordingsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecordingsDatabase::class.java,
                    "recordings_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
