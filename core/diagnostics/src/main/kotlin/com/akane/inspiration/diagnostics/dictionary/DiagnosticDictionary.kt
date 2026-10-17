package com.akane.inspiration.diagnostics.dictionary

import java.util.Locale

/**
 * Deterministic name matcher. Precedence: exact > longest prefix/suffix > longest contains >
 * regex (file order). Remaining ties go to the entry that comes first in [entries].
 * Kernel and wakeup-reason names (and the entry values they are compared with) are lowercased;
 * other kinds are case-sensitive.
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
        val subject = if (kind.caseInsensitive) name.lowercase(Locale.ROOT) else name
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
        return best?.entry?.toExplanation(locale)
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
}
