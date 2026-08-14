package dev.fritze.skyward.core.persistence

import kotlinx.serialization.json.Json

/**
 * Shared JSON config for every `*_json` column (§11). `ignoreUnknownKeys` only covers a JSON
 * value having *extra* fields a newer app version dropped from its serializable class -- it does
 * NOT let decoding tolerate a JSON value that's *missing* a field a newer app version added,
 * unless that field has a Kotlin default. Any new non-null property on a persisted serializable
 * class needs a default (or a migration) or old rows will fail to decode with older-schema JSON.
 */
internal val persistenceJson = Json { ignoreUnknownKeys = true }
