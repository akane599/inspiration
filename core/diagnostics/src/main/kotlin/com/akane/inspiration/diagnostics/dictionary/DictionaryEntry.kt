package com.akane.inspiration.diagnostics.dictionary

enum class MatchType(internal val jsonKey: String) {
    EXACT("exact"),
    PREFIX("prefix"),
    SUFFIX("suffix"),
    CONTAINS("contains"),

    /** Java regex, applied with `containsMatchIn`; anchor with `^`/`$` when needed. */
    REGEX("regex"),
}

data class LocalizedText(val en: String, val tr: String)

/** One parsed dictionary entry. Order in the list is the file order used to break ties. */
data class DictionaryEntry(
    val id: String,
    val kinds: Set<EntryKind>,
    val matchType: MatchType,
    val value: String,
    val category: String,
    val title: LocalizedText,
    val body: LocalizedText,
    /** Data hint only (`bg_dexopt`, `standby_bucket`, `appops_wake_lock`); nothing here executes it. */
    val action: String?,
)

/** A localized explanation of a name. */
data class Explanation(
    val id: String,
    val category: String,
    val title: String,
    val body: String,
    val action: String?,
)
