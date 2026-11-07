package com.arjangconsulting.codeeditor

/** Most selections an editor keeps; selections beyond this are dropped. */
internal const val MAX_SELECTIONS: Int = 10_000

/**
 * Every selection of an editor. The [primary] selection owns the native input window, completion,
 * hover, and scrolling, and is the most recently added one; [secondary] holds the others.
 */
internal data class Selections(val primary: Selection, val secondary: List<Selection> = emptyList()) {
    /** True when there is more than one selection. */
    val isMulti: Boolean get() = secondary.isNotEmpty()

    /** All selections in document order. */
    val all: List<Selection> get() = if (secondary.isEmpty()) listOf(primary) else (secondary + primary).sortedBy { it.start }

    val size: Int get() = secondary.size + 1

    fun coerceIn(length: Int): Selections = Selections(primary.coerceIn(length), secondary.map { it.coerceIn(length) })
}

private fun Selection.isReversed(): Boolean = caret < anchor

/**
 * Sorts selections and merges ones that overlap, start at the same offset, or where a caret touches
 * another selection. The merged group that contains the primary stays primary and keeps its direction.
 * Keeps at most [MAX_SELECTIONS], always including the primary.
 */
internal fun normalizeSelections(primary: Selection, secondary: List<Selection>): Selections {
    if (secondary.isEmpty()) return Selections(primary)
    val items = (secondary.map { it to false } + (primary to true)).sortedWith(compareBy({ it.first.start }, { it.first.end }))
    val merged = ArrayList<Pair<Selection, Boolean>>(items.size)
    for ((selection, isPrimary) in items) {
        val last = merged.lastOrNull()
        if (last != null && touches(last.first, selection)) {
            val start = minOf(last.first.start, selection.start)
            val end = maxOf(last.first.end, selection.end)
            val reversed = if (isPrimary) selection.isReversed() else last.first.isReversed()
            merged[merged.size - 1] = (if (reversed) Selection(end, start) else Selection(start, end)) to (last.second || isPrimary)
        } else {
            merged += selection to isPrimary
        }
    }
    val mergedPrimary = merged.first { it.second }.first
    val others = merged.filter { !it.second }.map { it.first }
    return Selections(mergedPrimary, if (others.size >= MAX_SELECTIONS) others.take(MAX_SELECTIONS - 1) else others)
}

/** Whether [b] (which starts at or after [a]) must merge with [a]. */
private fun touches(a: Selection, b: Selection): Boolean =
    b.start < a.end || b.start == a.start || (b.start == a.end && (a.collapsed || b.collapsed))

/**
 * Runs [op] at every selection (in document order, with its index) against the same document and
 * combines the results into one outcome. Replacements stay in original coordinates; each resulting
 * selection is shifted by the length change of earlier selections' replacements. A selection that
 * lies inside, or whose replacements would overlap, an earlier selection's replacements (including an
 * insertion at the same offset as an earlier insertion, such as two carets commenting one line) is
 * left unedited and mapped through them instead. The kind is shared by all edits, or [EditKind.OTHER].
 * Returns null when no selection produced an outcome.
 */
internal fun combineOutcomes(selections: Selections, op: (selection: Selection, index: Int) -> EditOutcome?): EditOutcome? {
    if (!selections.isMulti) return op(selections.primary, 0)
    val replacements = ArrayList<Replacement>()
    val results = ArrayList<Pair<Selection, Boolean>>()
    var delta = 0
    var lastEnd = -1
    val insertions = HashSet<Int>()
    var kind: EditKind? = null
    var produced = false
    for ((index, selection) in selections.all.withIndex()) {
        val isPrimary = selection == selections.primary
        val outcome = op(selection, index)
        val own = outcome?.replacements.orEmpty().filter { !(it.start == it.end && it.text.isEmpty()) }.sortedBy { it.start }
        val conflicts = selection.start < lastEnd || own.any { it.start < lastEnd || (it.start == it.end && it.start in insertions) }
        if (outcome == null || conflicts) {
            results += shiftSelection(selection, replacements) to isPrimary
            continue
        }
        produced = true
        replacements += own
        for (replacement in own) {
            lastEnd = maxOf(lastEnd, replacement.end)
            if (replacement.start == replacement.end) insertions += replacement.start
        }
        results += Selection(outcome.selection.anchor + delta, outcome.selection.caret + delta) to isPrimary
        delta += own.sumOf { it.text.length - (it.end - it.start) }
        kind = when (kind) {
            null, outcome.kind -> outcome.kind
            else -> EditKind.OTHER
        }
    }
    if (!produced) return null
    val primary = results.first { it.second }.first
    return EditOutcome(replacements, primary, kind ?: EditKind.OTHER, results.filter { !it.second }.map { it.first })
}

/** [outcome] with [secondary] selections mapped through its replacements, for primary-only edits. */
internal fun withShiftedSecondaries(outcome: EditOutcome, secondary: List<Selection>): EditOutcome =
    if (secondary.isEmpty()) outcome else outcome.copy(secondarySelections = secondary.map { shiftSelection(it, outcome.replacements) })

/**
 * Adds the next occurrence of the primary selection's text after it (wrapping around) as the new
 * primary. Returns null when every occurrence is already selected or the primary is collapsed.
 * Matching is literal and case-sensitive.
 */
internal fun addNextOccurrence(text: String, selections: Selections): Selections? {
    val primary = selections.primary
    if (primary.collapsed) return null
    val needle = text.substring(primary.start, primary.end)
    val existing = selections.all
    var from = primary.end
    var wrapped = false
    while (true) {
        var index = text.indexOf(needle, from)
        if (index < 0) {
            if (wrapped) return null
            wrapped = true
            index = text.indexOf(needle, 0)
            if (index < 0) return null
        }
        if (wrapped && index >= primary.start) return null
        val candidate = Selection(index, index + needle.length)
        if (existing.none { it.start < candidate.end && candidate.start < it.end }) {
            return Selections(candidate, selections.secondary + primary)
        }
        from = index + 1
    }
}

/**
 * One selection per range in [ranges] (ascending, non-overlapping). The range containing (or else
 * the first after) [nearOffset] becomes primary. Returns null for an empty list.
 */
internal fun selectionsForRanges(ranges: List<IntRange>, nearOffset: Int): Selections? {
    if (ranges.isEmpty()) return null
    val capped = ranges.take(MAX_SELECTIONS)
    val primaryIndex = capped.indexOfFirst { it.last + 1 >= nearOffset }.takeIf { it >= 0 } ?: 0
    val selections = capped.map { Selection(it.first, it.last + 1) }
    return Selections(selections[primaryIndex], selections.filterIndexed { i, _ -> i != primaryIndex })
}

/**
 * Removes the caret at [offset] when one is there and it isn't the only selection, else adds a caret
 * there as the new primary. The last remaining secondary becomes primary when the primary is removed.
 */
internal fun toggleCaret(selections: Selections, offset: Int): Selections {
    val all = selections.all
    val hit = all.firstOrNull { it.collapsed && it.caret == offset }
    if (hit == null || all.size == 1) {
        if (hit != null) return selections
        return normalizeSelections(Selection(offset), selections.secondary + selections.primary)
    }
    return if (hit == selections.primary) {
        Selections(selections.secondary.last(), selections.secondary.dropLast(1))
    } else {
        Selections(selections.primary, selections.secondary - hit)
    }
}
