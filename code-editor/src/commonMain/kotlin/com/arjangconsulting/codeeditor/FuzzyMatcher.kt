package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Immutable

/** A successful fuzzy match: a [score] (higher is better) and the matched candidate indices. */
@Immutable
public class FuzzyMatch internal constructor(public val score: Int, public val matchedIndices: IntArray) {
    override fun toString(): String = "FuzzyMatch(score=$score, indices=${matchedIndices.contentToString()})"
}

/**
 * Subsequence fuzzy matching in the style of editor completion pickers.
 *
 * Every pattern character must appear in order in the candidate, case-insensitively. Matches score
 * higher when they start the candidate, sit on word boundaries (`camelCase`, `snake_case`, `.`),
 * run consecutively, and match case exactly. The first pattern character must match the start of
 * the candidate or a word boundary, which keeps lists short and predictable.
 */
public object FuzzyMatcher {
    private const val MATCH = 1
    private const val CONSECUTIVE = 5
    private const val START = 8
    private const val BOUNDARY = 6
    private const val EXACT_CASE = 1
    private const val GAP_PENALTY = 1
    private const val FULL_PREFIX = 10

    /** Matches [pattern] against [candidate]; returns null when it does not match. */
    public fun match(pattern: String, candidate: String): FuzzyMatch? {
        if (pattern.isEmpty()) return FuzzyMatch(0, IntArray(0))
        if (pattern.length > candidate.length) return null
        val latest = IntArray(pattern.length)
        var candidateIndex = candidate.lastIndex
        for (patternIndex in pattern.indices.reversed()) {
            while (candidateIndex >= 0 && !candidate[candidateIndex].equals(pattern[patternIndex], ignoreCase = true)) candidateIndex--
            if (candidateIndex < 0) return null
            latest[patternIndex] = candidateIndex--
        }
        var best: FuzzyMatch? = null
        for (start in 0..latest[0]) {
            if (!candidate[start].equals(pattern[0], ignoreCase = true)) continue
            if (start != 0 && !isBoundary(candidate, start)) continue
            val match = greedyFrom(pattern, candidate, start, latest) ?: continue
            if (best == null || match.score > best.score) best = match
        }
        return best
    }

    private fun greedyFrom(pattern: String, candidate: String, start: Int, latest: IntArray): FuzzyMatch? {
        val indices = IntArray(pattern.length)
        var score = 0
        var ci = start
        var previous = -2
        for (pi in pattern.indices) {
            val pc = pattern[pi]
            // Prefer an adjacent match, then the next word-boundary match, then the next plain match.
            val index = when {
                pi == 0 -> start
                ci < candidate.length && ci == previous + 1 && candidate[ci].equals(pc, ignoreCase = true) -> ci
                else -> {
                    var first = -1
                    var boundary = -1
                    for (i in ci..latest[pi]) {
                        if (!candidate[i].equals(pc, ignoreCase = true)) continue
                        if (first == -1) first = i
                        if (isBoundary(candidate, i)) {
                            boundary = i
                            break
                        }
                    }
                    if (boundary != -1) boundary else first
                }
            }
            if (index == -1) return null
            score += MATCH
            if (index == 0) score += START
            if (index > 0 && isBoundary(candidate, index)) score += BOUNDARY
            if (index == previous + 1) score += CONSECUTIVE else if (pi > 0) score -= GAP_PENALTY * minOf(index - previous - 1, 5)
            if (candidate[index] == pc) score += EXACT_CASE
            indices[pi] = index
            previous = index
            ci = index + 1
        }
        if (indices[0] == 0 && indices.last() == pattern.length - 1) score += FULL_PREFIX
        return FuzzyMatch(score, indices)
    }

    private fun isBoundary(text: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = text[index - 1]
        val current = text[index]
        return !previous.isLetterOrDigit() ||
            (previous.isLowerCase() && current.isUpperCase()) ||
            (previous.isLetter() && current.isDigit())
    }
}

/** A completion item that survived filtering, with its match for highlighting. */
@Immutable
public class RankedCompletion internal constructor(public val item: CompletionItem, public val match: FuzzyMatch) {
    override fun toString(): String = "RankedCompletion(${item.label}, ${match.score})"
}

/**
 * Filters [items] by [prefix] against `filterText ?: label` and ranks them: higher fuzzy score
 * first, then `preselect`, then `sortText ?: label`, then original order. With an empty prefix the
 * order is `sortText ?: label`. At most [limit] results are returned.
 */
public fun filterAndRankCompletions(items: List<CompletionItem>, prefix: String, limit: Int = Int.MAX_VALUE): List<RankedCompletion> {
    val ranked = ArrayList<Pair<Int, RankedCompletion>>(items.size)
    items.forEachIndexed { index, item ->
        val match = FuzzyMatcher.match(prefix, item.filterText ?: item.label) ?: return@forEachIndexed
        ranked += index to RankedCompletion(item, match)
    }
    val comparator = compareByDescending<Pair<Int, RankedCompletion>> { if (prefix.isEmpty()) 0 else it.second.match.score }
        .thenByDescending { it.second.item.preselect }
        .thenBy { it.second.item.sortText ?: it.second.item.label }
        .thenBy { it.first }
    return ranked.sortedWith(comparator).asSequence().take(limit).map { it.second }.toList()
}
