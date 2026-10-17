package com.akane.inspiration.diagnostics.dictionary

/** The kind of name a dictionary entry explains. */
enum class EntryKind(
    /** Key used in the `kinds` array of `diagnostic_dictionary.json`. */
    internal val jsonKey: String,
    /** Kernel-sourced names are matched lowercase; app-supplied names are case-sensitive. */
    internal val caseInsensitive: Boolean,
) {
    PARTIAL("partial", caseInsensitive = false),
    KERNEL("kernel", caseInsensitive = true),
    WAKEUP_REASON("wakeup_reason", caseInsensitive = true),
    ALARM("alarm", caseInsensitive = false),
}
