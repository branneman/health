package org.branneman.health.db

import androidx.room.Database
import androidx.room.RoomDatabase
import org.branneman.health.db.dao.*
import org.branneman.health.db.entities.*

@Database(
    entities = [
        BodyWeightEntity::class,
        DailyEnergyEntity::class,
        WorkoutEntity::class,
        LogEntryEntity::class,
        LogEntryItemEntity::class,
        MealTemplateEntity::class,
        MealTemplateItemEntity::class,
        FoodItemEntity::class,
        ShortcutEntity::class,
        UserProfileEntity::class,
        SportTonightEntity::class,
        DynamicBudgetParamsEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun bodyWeightDao(): BodyWeightDao
    abstract fun dailyEnergyDao(): DailyEnergyDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun logEntryDao(): LogEntryDao
    abstract fun mealTemplateDao(): MealTemplateDao
    abstract fun foodItemDao(): FoodItemDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun sportTonightDao(): SportTonightDao
    abstract fun dynamicBudgetParamsDao(): DynamicBudgetParamsDao
}
