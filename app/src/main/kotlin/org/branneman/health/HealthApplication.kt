package org.branneman.health

import android.app.Application
import androidx.room.Room
import org.branneman.health.db.HealthDatabase

class HealthApplication : Application() {

    lateinit var db: HealthDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, HealthDatabase::class.java, "health.db").build()
    }
}
