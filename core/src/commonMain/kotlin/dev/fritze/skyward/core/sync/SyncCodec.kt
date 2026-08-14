package dev.fritze.skyward.core.sync

import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** One rule that failed to decode fully and was imported disabled instead (§12.3). */
data class RuleImportWarning(val ruleId: String, val ruleName: String)

/** The successfully-parsed contents of an import file, plus any per-rule warnings. */
data class ParsedSyncFile(
    val exportedAt: Instant,
    val appVersion: String,
    val locations: List<SavedLocation>,
    val rules: List<Rule>,
    val settings: Map<String, String>,
    val firedNotificationIds: List<String>,
    val ruleWarnings: List<RuleImportWarning>,
)

sealed class SyncImportError(message: String) : Exception(message) {
    /** Not a Skyward sync file at all. */
    data class WrongFormat(val format: String?) : SyncImportError("unrecognized sync file format: $format")

    /** §12.3: "Unknown formatVersion -> refuse with message." */
    data class UnknownFormatVersion(val version: Int?) : SyncImportError("unsupported sync file version: $version")
    data class Malformed(val detail: String) : SyncImportError("malformed sync file: $detail")
}

/**
 * §12.2 encode/decode for the sync file. Decoding is deliberately NOT a
 * single `Json.decodeFromString<SyncFile>` call: [Rule.condition] is a
 * polymorphic [Cond] tree, and a file written by a newer app version may
 * contain a [Cond] subtype this version doesn't know about. A naive decode
 * would then throw for the *entire* file over one rule; instead each rule
 * is decoded independently so one unrecognized condition degrades that one
 * rule (§12.3: imported disabled, with a warning) without losing the rest
 * of the file.
 */
object SyncCodec {
    // encodeDefaults: SyncFile.format/formatVersion carry defaults for construction convenience,
    // but the format/version check on import (below) needs them actually present in the JSON.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun export(file: SyncFile): String = json.encodeToString(SyncFile.serializer(), file)

    fun parseForImport(text: String): ParsedSyncFile {
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }
            .getOrElse { throw SyncImportError.Malformed(it.message ?: "invalid JSON") }

        val format = root["format"]?.jsonPrimitive?.content
        if (format != SyncFile.FORMAT) throw SyncImportError.WrongFormat(format)

        val formatVersion = root["formatVersion"]?.jsonPrimitive?.intOrNull
        if (formatVersion != SyncFile.FORMAT_VERSION) throw SyncImportError.UnknownFormatVersion(formatVersion)

        val exportedAt = runCatching { Instant.parse(root.getValue("exportedAt").jsonPrimitive.content) }
            .getOrElse { throw SyncImportError.Malformed("exportedAt: ${it.message}") }
        val appVersion = root["appVersion"]?.jsonPrimitive?.content ?: ""

        val locations = decodeField(root, "locations") { ListSerializer(SavedLocation.serializer()) }
        val settings = decodeField(root, "settings") { MapSerializer(String.serializer(), String.serializer()) } ?: emptyMap()
        val firedNotificationIds = decodeField(root, "firedNotificationIds") { ListSerializer(String.serializer()) } ?: emptyList()

        val warnings = mutableListOf<RuleImportWarning>()
        val ruleElements = (root["rules"] as? JsonArray) ?: JsonArray(emptyList())
        val rules = ruleElements.mapNotNull { decodeRuleOrDisabled(it, warnings) }

        return ParsedSyncFile(
            exportedAt = exportedAt,
            appVersion = appVersion,
            locations = locations ?: emptyList(),
            rules = rules,
            settings = settings,
            firedNotificationIds = firedNotificationIds,
            ruleWarnings = warnings,
        )
    }

    private fun <T> decodeField(root: JsonObject, key: String, serializer: () -> kotlinx.serialization.KSerializer<T>): T? {
        val element = root[key] ?: return null
        return runCatching { json.decodeFromJsonElement(serializer(), element) }
            .getOrElse { throw SyncImportError.Malformed("$key: ${it.message}") }
    }

    private fun decodeRuleOrDisabled(element: JsonElement, warnings: MutableList<RuleImportWarning>): Rule? {
        runCatching { json.decodeFromJsonElement(Rule.serializer(), element) }.getOrNull()?.let { return it }

        // Full decode failed. Every Rule field but `condition` is a closed, stable type, so this is
        // almost always an unrecognized Cond subtype -- recover everything else via RuleSkeleton
        // (identical to Rule, but leaves `condition` undecoded) and import the rule disabled (§12.3).
        val skeleton = runCatching { json.decodeFromJsonElement(RuleSkeleton.serializer(), element) }.getOrNull()
        if (skeleton == null) {
            // Not just an unknown condition type -- some other field is malformed too. Nothing
            // recoverable to import; still warn, using whatever id/name we can scrape out raw.
            val obj = element as? JsonObject
            val id = obj?.get("id")?.jsonPrimitive?.content ?: "unknown"
            val name = obj?.get("name")?.jsonPrimitive?.content ?: id
            warnings += RuleImportWarning(id, name)
            return null
        }

        warnings += RuleImportWarning(skeleton.id, skeleton.name)
        return Rule(
            id = skeleton.id,
            name = skeleton.name,
            enabled = false,
            phenomena = skeleton.phenomena,
            locationIds = skeleton.locationIds,
            condition = UNRECOGNIZED_CONDITION,
            schedule = skeleton.schedule,
            hidden = skeleton.hidden,
            createdAt = skeleton.createdAt,
            modifiedAt = skeleton.modifiedAt,
        )
    }

    /** Always evaluates `false` (`Not(And(emptyList()))`, and `all` on an empty list is vacuously `true`) -- inert placeholder for a condition tree that couldn't be recovered. Paired with `enabled = false`, so it's purely defensive. */
    private val UNRECOGNIZED_CONDITION = Cond.Not(Cond.And(emptyList()))
}

/** Mirrors [Rule] field-for-field except [condition], left as raw JSON so an unknown [Cond] subtype elsewhere doesn't block decoding the rest of the rule. */
@Serializable
private data class RuleSkeleton(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val phenomena: Set<Phenomenon>,
    val locationIds: List<String>? = null,
    val condition: JsonElement,
    val schedule: NotifySchedule,
    val hidden: Boolean = false,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
