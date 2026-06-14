package org.branneman.health.data

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object BodyWeight : Table("body_weight") {
    val id        = uuid("id")
    val userId    = uuid("user_id")
    val date      = date("date")
    val kg        = decimal("kg", 5, 2)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object DailyEnergy : Table("daily_energy") {
    val userId     = uuid("user_id")
    val date       = date("date")
    val bmrKcal    = integer("bmr_kcal")
    val activeKcal = integer("active_kcal")
    val totalKcal  = integer("total_kcal")
    val steps      = integer("steps").nullable()
    val dataSource = text("source")
    override val primaryKey = PrimaryKey(userId, date)
}

object Workout : Table("workout") {
    val id              = uuid("id")
    val userId          = uuid("user_id")
    val date            = date("date")
    val type            = text("type")
    val durationSecs    = integer("duration_secs").nullable()
    val avgHr           = integer("avg_hr").nullable()
    val kcal            = integer("kcal").nullable()
    val polarExerciseId = text("polar_exercise_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

object FoodItem : Table("food_item") {
    val id             = uuid("id")
    val userId         = uuid("user_id")
    val barcode        = text("barcode").nullable()
    val name           = text("name")
    val kcalPer100g    = decimal("kcal_per_100g", 7, 2)
    val proteinPer100g = decimal("protein_per_100g", 7, 2).nullable()
    val carbsPer100g   = decimal("carbs_per_100g", 7, 2).nullable()
    val fatPer100g     = decimal("fat_per_100g", 7, 2).nullable()
    val dataSource     = text("source")
    override val primaryKey = PrimaryKey(id)
}

object MealTemplate : Table("meal_template") {
    val id           = uuid("id")
    val userId       = uuid("user_id")
    val name         = text("name")
    val quickAddKcal = integer("quick_add_kcal").nullable()
    val sortOrder    = integer("sort_order").nullable()
    val createdAt    = timestampWithTimeZone("created_at")
    val updatedAt    = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object MealTemplateItem : Table("meal_template_item") {
    val templateId = uuid("template_id")
    val foodItemId = uuid("food_item_id")
    val grams      = decimal("grams", 7, 1)
    override val primaryKey = PrimaryKey(templateId, foodItemId)
}

object LogEntry : Table("log_entry") {
    val id            = uuid("id")
    val userId        = uuid("user_id")
    val loggedAt      = timestampWithTimeZone("logged_at")
    val mealType      = registerColumn<String>("meal_type", PgEnumColumnType("meal_type"))
    val createdAt     = timestampWithTimeZone("created_at")
    val quickAddKcal  = integer("quick_add_kcal").nullable()
    val quickAddLabel = text("quick_add_label").nullable()
    override val primaryKey = PrimaryKey(id)
}

object LogEntryItem : Table("log_entry_item") {
    val logEntryId     = uuid("log_entry_id")
    val foodItemId     = uuid("food_item_id")
    val grams          = decimal("grams", 7, 1)
    val kcalPer100g    = decimal("kcal_per_100g", 7, 2)
    val proteinPer100g = decimal("protein_per_100g", 7, 2).nullable()
    val carbsPer100g   = decimal("carbs_per_100g", 7, 2).nullable()
    val fatPer100g     = decimal("fat_per_100g", 7, 2).nullable()
    override val primaryKey = PrimaryKey(logEntryId, foodItemId)
}

object UserProfile : Table("user_profile") {
    val userId        = uuid("user_id")
    val heightCm      = integer("height_cm")
    val birthYear     = integer("birth_year")
    val sex           = text("sex")
    val goalWeightKg  = decimal("goal_weight_kg", 5, 2)
    val activityLevel = text("activity_level")
    val targetDeficit = integer("target_deficit")
    val phase         = text("phase")
    val vacationMode  = bool("vacation_mode")
    val wakeTime      = time("wake_time").default(java.time.LocalTime.of(7, 0))
    val bedtime       = time("bedtime").default(java.time.LocalTime.of(23, 0))
    val updatedAt     = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

object Shortcut : Table("shortcut") {
    val id        = uuid("id")
    val userId    = uuid("user_id")
    val emoji     = text("emoji")
    val label     = text("label")
    val kcal      = integer("kcal")
    val sortOrder = integer("sort_order")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object PolarAuth : Table("polar_auth") {
    val userId       = text("user_id")
    val accessToken  = text("access_token")
    val createdAt    = timestampWithTimeZone("created_at")
    val healthUserId = uuid("health_user_id").nullable()
    override val primaryKey = PrimaryKey(userId)
}

object PolarConnectState : Table("polar_connect_state") {
    val state     = text("state")
    val userId    = uuid("user_id")
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(state)
}
