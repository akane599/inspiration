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
 * A localized explanation of a name. [category] and [action] are read only by tests today. They are kept for the
 * unbuilt culprit card (TEMPLATE.md §5 Rule 2), which groups its rows by [category], and for its one-tap Shizuku fix
 * (TEMPLATE.md §1), which [action] names (phase-3 F4–F6, phase-5 H14).
 */
data class Explanation(
    val id: String,
    val category: String,
    val title: String,
    val body: String,
    val action: String?,
)
