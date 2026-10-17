package com.akane.inspiration.diagnostics.dictionary

import org.json.JSONException
import org.json.JSONObject
import java.util.regex.PatternSyntaxException

/** Parses `diagnostic_dictionary.json`. Every problem is reported as [IllegalArgumentException]. */
object DictionaryLoader {
    private const val SUPPORTED_VERSION = 1
    private val ALLOWED_ACTIONS = setOf("bg_dexopt", "standby_bucket", "appops_wake_lock")

    fun parse(json: String): List<DictionaryEntry> = try {
        parseEntries(JSONObject(json))
    } catch (e: JSONException) {
        throw IllegalArgumentException("Malformed dictionary JSON: ${e.message}", e)
    }

    private fun parseEntries(root: JSONObject): List<DictionaryEntry> {
        val version = root.optInt("version", SUPPORTED_VERSION)
        require(version == SUPPORTED_VERSION) { "Unsupported dictionary version $version" }
        val array = root.getJSONArray("entries")
        val seenIds = HashSet<String>()
        return List(array.length()) { index ->
            val entry = parseEntry(array.getJSONObject(index), index)
            require(seenIds.add(entry.id)) { "Duplicate dictionary id '${entry.id}'" }
            entry
        }
    }

    private fun parseEntry(obj: JSONObject, index: Int): DictionaryEntry {
        val id = requiredString(obj, "id", "entry #$index")
        val where = "entry '$id'"
        val match = obj.optJSONObject("match") ?: throw IllegalArgumentException("$where: missing 'match'")
        val matchType = parseMatchType(requiredString(match, "type", where), where)
        val value = requiredString(match, "value", where)
        if (matchType == MatchType.REGEX) {
            try {
                Regex(value)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("$where: invalid regex '$value'", e)
            }
        }
        return DictionaryEntry(
            id = id,
            kinds = parseKinds(obj, where),
            matchType = matchType,
            value = value,
            category = requiredString(obj, "category", where),
            title = localized(obj, "title", where),
            body = localized(obj, "body", where),
            action = parseAction(obj, where),
        )
    }

    private fun parseKinds(obj: JSONObject, where: String): Set<EntryKind> {
        val array = obj.optJSONArray("kinds") ?: throw IllegalArgumentException("$where: missing 'kinds'")
        require(array.length() > 0) { "$where: 'kinds' is empty" }
        return List(array.length()) { i ->
            val key = array.optString(i)
            EntryKind.entries.firstOrNull { it.jsonKey == key }
                ?: throw IllegalArgumentException("$where: unknown kind '$key'")
        }.toSet()
    }

    private fun parseMatchType(key: String, where: String): MatchType =
        MatchType.entries.firstOrNull { it.jsonKey == key }
            ?: throw IllegalArgumentException("$where: unknown match type '$key'")

    private fun parseAction(obj: JSONObject, where: String): String? {
        if (obj.isNull("action")) return null
        val action = obj.getString("action")
        require(action in ALLOWED_ACTIONS) { "$where: unknown action '$action'" }
        return action
    }

    private fun localized(obj: JSONObject, key: String, where: String): LocalizedText {
        val text = obj.optJSONObject(key) ?: throw IllegalArgumentException("$where: missing '$key'")
        return LocalizedText(
            en = requiredString(text, "en", "$where $key"),
            tr = requiredString(text, "tr", "$where $key"),
        )
    }

    private fun requiredString(obj: JSONObject, key: String, where: String): String {
        val value = if (obj.isNull(key)) "" else obj.getString(key)
        require(value.isNotBlank()) { "$where: missing or blank '$key'" }
        return value
    }
}
