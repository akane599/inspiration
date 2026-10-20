package com.akane.inspiration.diagnostics.dictionary

enum class MatchType(
    internal val jsonKey: String,
) {
    EXACT("exact"),
    PREFIX("prefix"),
    SUFFIX("suffix"),
    CONTAINS("contains"),

    /** Java regex, applied with `containsMatchIn`; anchor with `^`/`$` when needed. */
    REGEX("regex"),
}

data class LocalizedText(
    val en: String,
    val tr: String,
)

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

/**
 * A localized explanation of a name. Sleep Lab's culprit card (TEMPLATE.md §4 Rule 2) words [action] as guidance to
 * the matching Toolkit control; nothing executes it. [category] is read only by tests today (phase-3 F4–F6).
 */
data class Explanation(
    val id: String,
    val category: String,
    val title: String,
    val body: String,
    val action: String?,
)
