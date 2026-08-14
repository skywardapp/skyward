package dev.fritze.skyward.core.persistence

import kotlinx.serialization.json.Json

/** Shared JSON config for every `*_json` column (§11). Lenient to old rows from earlier app versions. */
internal val persistenceJson = Json { ignoreUnknownKeys = true }
