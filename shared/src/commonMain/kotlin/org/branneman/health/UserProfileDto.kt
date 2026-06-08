package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class UserProfileDto(
    val heightCm: Int,
    val birthYear: Int,
    val sex: String,
    val goalWeightKg: Double,
    val activityLevel: String,
    val targetDeficit: Int,
    val phase: String,
    val vacationMode: Boolean,
)
