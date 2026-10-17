package com.akane.inspiration.diagnostics.dictionary

import java.util.Locale

/**
 * Deterministic name matcher. Precedence: exact > longest prefix/suffix > longest contains >
 * regex (file order). Remaining ties go to the entry that comes first in [entries].
 * Kernel and wakeup-reason names (and the entry values they are compared with) are lowercased;
 * other kinds are case-sensitive. For ALARM and PARTIAL names that start with "*walarm*:" or
 * "*alarm*:", the remainder is looked up first with the same precedence; the full name is only
 * looked up (and so reaches the prefix entries) when nothing specific matches the remainder.
 * PARTIAL names "*job*<v>/..." (JobScheduler variant letter: r, e, u) are looked up as "*job*/...".
 */
class DiagnosticDictionary(entries: List<DictionaryEntry>) {
    private class Compiled(val entry: DictionaryEntry, val regex: Regex?) {
        val lowerValue: String = entry.value.lowercase(Locale.ROOT)
    }

    private val byKind: Map<EntryKind, List<Compiled>>

    init {
        val compiled = entries.map {
            Compiled(it, if (it.matchType == MatchType.REGEX) Regex(it.value) else null)
        }
        byKind = EntryKind.entries.associateWith { kind -> compiled.filter { kind in it.entry.kinds } }
    }

    fun explain(kind: EntryKind, name: String, locale: Locale): Explanation? {
        val lowered = if (kind.caseInsensitive) name.lowercase(Locale.ROOT) else name
        val subject = if (kind == EntryKind.PARTIAL) JOB_VARIANT.replace(lowered, "*job*/") else lowered
        val stripped = if (kind == EntryKind.ALARM || kind == EntryKind.PARTIAL) stripAlarmPrefix(subject) else null
        // Alarm subjects arrive as "*walarm*:<tag>" / "*alarm*:<tag>": a specific entry for the tag
        // wins; the prefix entries (walarm_prefix, alarm_prefix) are the fallback for the full name.
        val best = stripped?.let { lookup(kind, it) } ?: lookup(kind, subject)
        return best?.entry?.toExplanation(locale)
    }

    private fun stripAlarmPrefix(subject: String): String? =
        ALARM_PREFIXES.firstOrNull { subject.startsWith(it) }?.let { subject.substring(it.length) }

    private fun lookup(kind: EntryKind, subject: String): Compiled? {
        var best: Compiled? = null
        var bestTier = Int.MAX_VALUE
        var bestLength = -1
        // Candidates are in file order, so a strict improvement check keeps the earliest on ties.
        for (candidate in byKind.getValue(kind)) {
            val tier = tierOf(candidate, subject, kind) ?: continue
            val length = if (candidate.entry.matchType.hasLength) candidate.entry.value.length else 0
            if (tier < bestTier || (tier == bestTier && length > bestLength)) {
                best = candidate
                bestTier = tier
                bestLength = length
            }
        }
        return best
    }

    private fun tierOf(candidate: Compiled, subject: String, kind: EntryKind): Int? {
        val value = if (kind.caseInsensitive) candidate.lowerValue else candidate.entry.value
        val matched = when (candidate.entry.matchType) {
            MatchType.EXACT -> subject == value
            MatchType.PREFIX -> subject.startsWith(value)
            MatchType.SUFFIX -> subject.endsWith(value)
            MatchType.CONTAINS -> subject.contains(value)
            MatchType.REGEX -> candidate.regex?.containsMatchIn(subject) == true
        }
        return if (matched) candidate.entry.matchType.tier else null
    }

    private val MatchType.tier: Int
        get() = when (this) {
            MatchType.EXACT -> 0
            MatchType.PREFIX, MatchType.SUFFIX -> 1
            MatchType.CONTAINS -> 2
            MatchType.REGEX -> 3
        }

    private val MatchType.hasLength: Boolean
        get() = this == MatchType.PREFIX || this == MatchType.SUFFIX || this == MatchType.CONTAINS

    private fun DictionaryEntry.toExplanation(locale: Locale): Explanation {
        val turkish = locale.language == "tr"
        return Explanation(
            id = id,
            category = category,
            title = if (turkish) title.tr else title.en,
            body = if (turkish) body.tr else body.en,
            action = action,
        )
    }

    private companion object {
        /** AlarmManagerService stats tag prefixes: wakeup alarms, then non-wakeup alarms. */
        val ALARM_PREFIXES = listOf("*walarm*:", "*alarm*:")

        /** JobScheduler wakelock tags carry one variant letter: regular, expedited, user-initiated. */
        val JOB_VARIANT = Regex("""^\*job\*[a-z]/""")
    }
}
