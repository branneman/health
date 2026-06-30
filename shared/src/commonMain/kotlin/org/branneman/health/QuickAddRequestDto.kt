package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class QuickAddRequestDto(
    val id: String,
    val quickAddKcal: Int,
    val quickAddLabel: String? = null,
    val loggedAt: String? = null,
    val sortOrder: Int = 0,
)
