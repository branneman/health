package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class QuickAddUpdateRequestDto(
    val kcal: Int,
    val label: String?,
)
