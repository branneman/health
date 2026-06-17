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

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE meal_template ADD COLUMN sortOrder INTEGER")
        db.execSQL("ALTER TABLE meal_template ADD COLUMN quickAddKcal INTEGER")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS dynamic_budget_params (" +
            "date TEXT NOT NULL PRIMARY KEY, " +
            "expectedTodaySport INTEGER, " +
            "expectedTodayNonSport INTEGER, " +
            "eatingFractionSport REAL, " +
            "eatingFractionNonSport REAL, " +
            "postWorkoutModeSport INTEGER NOT NULL, " +
            "postWorkoutModeNonSport INTEGER NOT NULL)"
        )
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `dynamic_budget_params`")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `dynamic_budget_params` (
                `date` TEXT NOT NULL,
                `expectedTodaySport` INTEGER,
                `expectedTodayNonSport` INTEGER,
                PRIMARY KEY(`date`)
            )
        """)
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    }
}
