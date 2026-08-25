package com.dualteacher.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response echoing whether an appended event was newly stored or a duplicate (idempotent). */
@Serializable
data class AppendEventResponse(
    @SerialName("event_id") val eventId: String,
    val stored: Boolean,
    val message: String,
)

/** Uniform error body. */
@Serializable
data class ErrorResponse(val error: String, val detail: String? = null)
