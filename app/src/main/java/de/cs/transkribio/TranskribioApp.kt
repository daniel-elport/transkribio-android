package de.cs.transkribio

import android.app.Application
import de.cs.transkribio.data.RecordingsDatabase
import de.cs.transkribio.data.RecordingsRepository

class TranskribioApp : Application() {

    val database by lazy { RecordingsDatabase.getDatabase(this) }
    val repository by lazy { RecordingsRepository(database.recordingDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TranskribioApp
            private set
    }
}
