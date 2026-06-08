package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class ShortcutDto(
    val id: String,
    val emoji: String,
    val label: String,
    val kcal: Int,
    val sortOrder: Int,
)
