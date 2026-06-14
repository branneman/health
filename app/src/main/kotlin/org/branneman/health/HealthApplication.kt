package org.branneman.health

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import org.branneman.health.db.HealthDatabase

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sport_tonight (" +
            "date TEXT NOT NULL PRIMARY KEY, " +
            "activityType TEXT NOT NULL, " +
            "intensity TEXT NOT NULL, " +
            "estimatedKcal INTEGER NOT NULL)"
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN wakeTime TEXT NOT NULL DEFAULT '07:00'")
        db.execSQL("ALTER TABLE user_profile ADD COLUMN bedtime TEXT NOT NULL DEFAULT '23:00'")
    }
}

class HealthApplication : Application() {

    lateinit var db: HealthDatabase
        private set

    val polarCallbackPending = MutableStateFlow(false)

    fun onPolarCallback() {
        polarCallbackPending.value = true
    }

    fun clearPolarCallback() {
        polarCallbackPending.value = false
    }

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, HealthDatabase::class.java, "health.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
