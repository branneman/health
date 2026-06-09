package org.branneman.health

import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.*
import java.util.UUID

fun uuid() = UUID.randomUUID().toString()

fun aBodyWeightEntry(
    id: String = uuid(),
    userId: String = uuid(),
    date: String = "2026-01-01",
    kg: Double = 80.0,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
) = BodyWeightEntity(id = id, userId = userId, date = date, kg = kg, syncStatus = syncStatus)

fun aDailyEnergy(
    userId: String = uuid(),
    date: String = "2026-01-01",
    bmrKcal: Int = 1800,
    activeKcal: Int = 400,
    totalKcal: Int = 2200,
    steps: Int? = 8000,
    source: String = "polar",
) = DailyEnergyEntity(
    userId = userId, date = date, bmrKcal = bmrKcal, activeKcal = activeKcal,
    totalKcal = totalKcal, steps = steps, source = source,
)

fun aFoodItem(
    id: String = uuid(),
    userId: String = uuid(),
    name: String = "Test Food",
    kcalPer100g: Double = 200.0,
    source: String = "manual",
) = FoodItemEntity(
    id = id, userId = userId, barcode = null, name = name,
    kcalPer100g = kcalPer100g, proteinPer100g = null, carbsPer100g = null,
    fatPer100g = null, source = source, syncStatus = SyncStatus.SYNCED,
)

fun aShortcut(
    id: String = uuid(),
    userId: String = uuid(),
    emoji: String = "🍎",
    label: String = "Apple",
    kcal: Int = 52,
    sortOrder: Int = 0,
) = ShortcutEntity(
    id = id, userId = userId, emoji = emoji, label = label,
    kcal = kcal, sortOrder = sortOrder, syncStatus = SyncStatus.SYNCED,
)

fun aUserProfile(
    userId: String = uuid(),
) = UserProfileEntity(
    userId = userId, heightCm = 177, birthYear = 1986, sex = "male",
    goalWeightKg = 74.0, activityLevel = "lightly_active", targetDeficit = 300,
    phase = "loss", vacationMode = false, syncStatus = SyncStatus.SYNCED,
)

fun aLogEntry(
    id: String = uuid(),
    userId: String = uuid(),
    loggedAt: String = "2026-01-01T08:00:00Z",
    mealType: String = "breakfast",
) = LogEntryEntity(
    id = id, userId = userId, loggedAt = loggedAt, mealType = mealType,
    quickAddKcal = null, quickAddLabel = null, syncStatus = SyncStatus.PENDING_CREATE,
)

fun aMealTemplate(
    id: String = uuid(),
    userId: String = uuid(),
    name: String = "Test Template",
) = MealTemplateEntity(id = id, userId = userId, name = name, syncStatus = SyncStatus.SYNCED)

fun aWorkout(
    id: String = uuid(),
    userId: String = uuid(),
    date: String = "2026-01-01",
    type: String = "running",
) = WorkoutEntity(
    id = id, userId = userId, date = date, type = type,
    durationSecs = 1800, avgHr = 145, kcal = 400,
)
