package com.arjangconsulting.codeeditor

/**
 * Undo/redo history of text changes, independent of any engine.
 *
 * A change is stored as `(start, removed, inserted)` in the coordinates of the text it was applied
 * to, so entries hold only the edited fragments. Consecutive typing (or consecutive deletion) within
 * [coalesceWindowMillis] merges into one entry; whitespace after a word, a line break, or any
 * structural edit (paste, completion, indent) starts a new entry. Recording a change clears redo.
 */
internal class EditHistory(
    private val maxEntries: Int = 1_000,
    private val coalesceWindowMillis: Long = 1_000,
    private val clock: () -> Long = { MONOTONIC_START.elapsedNow().inWholeMilliseconds },
) {
    data class Change(val start: Int, val removed: String, val inserted: String)

    private class Entry(
        val changes: MutableList<Change>,
        val selectionBefore: Selections,
        var selectionAfter: Selections,
        val kind: EditKind,
        var time: Long,
    )

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    /** Breaks coalescing so the next change starts a new entry (e.g. after the caret moves). */
    private var sealed = true

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun seal() {
        sealed = true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        sealed = true
    }

    /** Records single-selection [changes]; see the [Selections] overload. */
    fun record(changes: List<Change>, before: Selection, after: Selection, kind: EditKind) =
        record(changes, Selections(before), Selections(after), kind)

    /**
     * Records [changes] (in descending start order, in the coordinates of the text before them) that
     * moved the selections from [before] to [after]. A typing or deletion step merges into the previous
     * entry when each change continues its counterpart, so typing at several carets coalesces too.
     */
    fun record(changes: List<Change>, before: Selections, after: Selections, kind: EditKind) {
        if (changes.isEmpty()) return
        redoStack.clear()
        val now = clock()
        val last = undoStack.lastOrNull()
        if (!sealed && last != null && tryMerge(last, changes, kind, now)) {
            last.selectionAfter = after
            last.time = now
            return
        }
        undoStack.addLast(Entry(changes.toMutableList(), before, after, kind, now))
        while (undoStack.size > maxEntries) undoStack.removeFirst()
        sealed = kind == EditKind.OTHER
    }

    /**
     * Merges [changes] into [last] when they pair up one to one (in document order) and each continues
     * its counterpart: typing right after its insertion, or deleting just before (Backspace) or at
     * (Delete) its deletion without reaching a neighbouring change. [last]'s changes use the text before
     * [last]; [changes] use the text after it, so positions are converted through earlier changes.
     */
    private fun tryMerge(last: Entry, changes: List<Change>, kind: EditKind, now: Long): Boolean {
        if (kind != last.kind || kind == EditKind.OTHER) return false
        if (now - last.time > coalesceWindowMillis || changes.size != last.changes.size) return false
        val previous = last.changes.sortedBy { it.start }
        val next = changes.sortedBy { it.start }
        val postStarts = IntArray(previous.size)
        var delta = 0
        for (i in previous.indices) {
            postStarts[i] = previous[i].start + delta
            delta += previous[i].inserted.length - previous[i].removed.length
        }
        val merged = ArrayList<Change>(previous.size)
        for (i in previous.indices) {
            val p = previous[i]
            val c = next[i]
            val postStart = postStarts[i]
            val postEnd = postStart + p.inserted.length
            val lowerEnd = if (i > 0) postStarts[i - 1] + previous[i - 1].inserted.length else 0
            val upperStart = if (i + 1 < previous.size) postStarts[i + 1] else Int.MAX_VALUE
            merged += when (kind) {
                EditKind.TYPING -> {
                    val contiguous = c.removed.isEmpty() && c.start == postEnd
                    val breaksWord = c.inserted.firstOrNull()?.isWhitespace() == true && p.inserted.lastOrNull()?.isWhitespace() == false
                    if (!contiguous || breaksWord || '\n' in c.inserted) return false
                    p.copy(inserted = p.inserted + c.inserted)
                }
                EditKind.DELETION -> {
                    if (c.inserted.isNotEmpty() || p.inserted.isNotEmpty()) return false
                    when {
                        c.start + c.removed.length == postStart && c.start >= lowerEnd ->
                            Change(p.start - c.removed.length, c.removed + p.removed, "")
                        c.start == postStart && c.start + c.removed.length <= upperStart ->
                            p.copy(removed = p.removed + c.removed)
                        else -> return false
                    }
                }
                EditKind.OTHER -> return false
            }
        }
        last.changes.clear()
        last.changes.addAll(merged.sortedByDescending { it.start })
        return true
    }

    /** Undoes the last entry against [text]; returns the new text and selections, or null. */
    fun undo(text: String): Pair<String, Selections>? {
        val entry = undoStack.removeLastOrNull() ?: return null
        val builder = StringBuilder(text.length)
        var cursor = 0
        var delta = 0
        for (change in entry.changes.asReversed()) {
            val start = change.start + delta
            builder.append(text, cursor, start)
            builder.append(change.removed)
            cursor = start + change.inserted.length
            delta += change.inserted.length - change.removed.length
        }
        builder.append(text, cursor, text.length)
        val result = builder.toString()
        redoStack.addLast(entry)
        sealed = true
        return result to entry.selectionBefore
    }

    /** Re-applies the last undone entry against [text]; returns the new text and selections, or null. */
    fun redo(text: String): Pair<String, Selections>? {
        val entry = redoStack.removeLastOrNull() ?: return null
        val builder = StringBuilder(text.length)
        var cursor = 0
        for (change in entry.changes.asReversed()) {
            builder.append(text, cursor, change.start)
            builder.append(change.inserted)
            cursor = change.start + change.removed.length
        }
        builder.append(text, cursor, text.length)
        val result = builder.toString()
        undoStack.addLast(entry)
        sealed = true
        return result to entry.selectionAfter
    }

    private companion object {
        val MONOTONIC_START = kotlin.time.TimeSource.Monotonic.markNow()
    }
}
