package com.dualteacher.app

import kotlinx.serialization.json.Json

/** Shared JSON configuration used for both the HTTP layer and JSONB (de)serialization. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    classDiscriminator = "type"
}
