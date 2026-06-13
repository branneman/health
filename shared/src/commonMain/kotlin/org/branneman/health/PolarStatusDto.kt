package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class PolarStatusDto(val connected: Boolean)
