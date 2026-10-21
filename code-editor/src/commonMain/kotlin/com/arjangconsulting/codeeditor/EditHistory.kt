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
        val selectionBefore: Selection,
        var selectionAfter: Selection,
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

    /** Records [changes], applied in list order, that moved the selection from [before] to [after]. */
    fun record(changes: List<Change>, before: Selection, after: Selection, kind: EditKind) {
        if (changes.isEmpty()) return
        redoStack.clear()
        val now = clock()
        val last = undoStack.lastOrNull()
        if (!sealed && last != null && changes.size == 1 && canMerge(last, changes[0], kind, now)) {
            mergeInto(last, changes[0])
            last.selectionAfter = after
            last.time = now
            return
        }
        undoStack.addLast(Entry(changes.toMutableList(), before, after, kind, now))
        while (undoStack.size > maxEntries) undoStack.removeFirst()
        sealed = kind == EditKind.OTHER
    }

    private fun canMerge(last: Entry, change: Change, kind: EditKind, now: Long): Boolean {
        if (kind != last.kind || kind == EditKind.OTHER) return false
        if (now - last.time > coalesceWindowMillis || last.changes.size != 1) return false
        val previous = last.changes[0]
        return when (kind) {
            EditKind.TYPING -> {
                val contiguous = change.removed.isEmpty() && change.start == previous.start + previous.inserted.length
                val breaksWord = change.inserted.firstOrNull()?.isWhitespace() == true &&
                    previous.inserted.lastOrNull()?.isWhitespace() == false
                contiguous && !breaksWord && '\n' !in change.inserted
            }
            EditKind.DELETION -> change.inserted.isEmpty() && previous.inserted.isEmpty() &&
                (change.start + change.removed.length == previous.start || change.start == previous.start)
            EditKind.OTHER -> false
        }
    }

    private fun mergeInto(entry: Entry, change: Change) {
        val previous = entry.changes[0]
        entry.changes[0] = when {
            change.removed.isEmpty() -> previous.copy(inserted = previous.inserted + change.inserted)
            change.start + change.removed.length == previous.start -> Change(change.start, change.removed + previous.removed, "")
            else -> previous.copy(removed = previous.removed + change.removed)
        }
    }

    /** Undoes the last entry against [text]; returns the new text and selection, or null. */
    fun undo(text: String): Pair<String, Selection>? {
        val entry = undoStack.removeLastOrNull() ?: return null
        var result = text
        for (change in entry.changes.asReversed()) {
            result = result.replaceRange(change.start, change.start + change.inserted.length, change.removed)
        }
        redoStack.addLast(entry)
        sealed = true
        return result to entry.selectionBefore
    }

    /** Re-applies the last undone entry against [text]; returns the new text and selection, or null. */
    fun redo(text: String): Pair<String, Selection>? {
        val entry = redoStack.removeLastOrNull() ?: return null
        var result = text
        for (change in entry.changes) {
            result = result.replaceRange(change.start, change.start + change.removed.length, change.inserted)
        }
        undoStack.addLast(entry)
        sealed = true
        return result to entry.selectionAfter
    }

    private companion object {
        val MONOTONIC_START = kotlin.time.TimeSource.Monotonic.markNow()
    }
}
