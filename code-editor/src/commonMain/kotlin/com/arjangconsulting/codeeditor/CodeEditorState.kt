package com.arjangconsulting.codeeditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The state of a [CodeEditor]: text, selection, version, folding, and undo/redo history. Create it
 * with [rememberCodeEditorState] and hoist it like a `TextFieldState`.
 *
 * Every text change made through this class or by the user increments [version] by one, which is
 * what [DocumentSnapshot.version] reports to a [LanguageService]. Offsets are UTF-16 indices into
 * [text]; positions follow LSP (see [TextPosition]).
 *
 * All members are backed by snapshot state, so reading them in composition recomposes on change.
 * Mutate the state from the UI thread only.
 */
@Stable
public class CodeEditorState(initialText: String = "", initialSelection: IntRange = initialText.length until initialText.length) {
    private var textState by mutableStateOf(initialText)
    private var selectionState by mutableStateOf(Selection(initialSelection.first, initialSelection.last + 1).coerceIn(initialText.length))
    private var versionState by mutableLongStateOf(0L)
    private var foldedState by mutableStateOf(emptySet<Int>())
    private var cachedSnapshot: DocumentSnapshot? = null
    internal val history: EditHistory = EditHistory()
    private var undoRevision by mutableIntStateOf(0)
    private val textListeners = ArrayList<(String) -> Unit>()

    /** The current text. */
    public val text: String get() = textState

    /** Increments by one on every text change. Starts at 0. */
    public val version: Long get() = versionState

    /** Selection start offset (the anchor, which may be after [selectionEnd]). */
    public val selectionStart: Int get() = selectionState.anchor

    /** Selection end offset: where the caret is. */
    public val selectionEnd: Int get() = selectionState.caret

    /** The selection as a normalized LSP range. */
    public val selection: TextRange
        get() {
            val snapshot = snapshot()
            return TextRange(snapshot.positionOf(selectionState.start), snapshot.positionOf(selectionState.end))
        }

    /** The caret position. */
    public val caret: TextPosition get() = snapshot().positionOf(selectionState.caret)

    /** The selected text, or an empty string. */
    public val selectedText: String get() = text.substring(selectionState.start, selectionState.end)

    /** True when there is something to undo. */
    public val canUndo: Boolean get() = undoRevision.let { history.canUndo }

    /** True when there is something to redo. */
    public val canRedo: Boolean get() = undoRevision.let { history.canRedo }

    /**
     * Folding ranges currently known to the editor (computed from the language or supplied by the
     * [LanguageService]). Empty until a [CodeEditor] with folding enabled has shown this state.
     */
    public var foldingRanges: List<FoldingRange> by mutableStateOf(emptyList())
        internal set

    /**
     * 0-based document lines currently on screen (including partially visible ones), as reported by
     * the engine showing this state; `0..0` before the first layout.
     */
    public var visibleLines: IntRange by mutableStateOf(0..0)
        internal set

    /** Start lines of collapsed folding ranges. */
    public val foldedLines: Set<Int> get() = foldedState

    internal var selectionInternal: Selection
        get() = selectionState
        set(value) {
            val clamped = value.coerceIn(textState.length)
            if (clamped != selectionState) {
                selectionState = clamped
                history.seal()
            }
        }

    /** The single-character insertion that produced the current version, for completion triggers. */
    internal var lastTypedCharacter: Char? = null
        private set

    internal var revealRequest: RevealRequest? by mutableStateOf(null)
        private set

    /** An immutable snapshot of the current text and version, cached per version. */
    public fun snapshot(): DocumentSnapshot {
        val text = textState
        val version = versionState
        val cached = cachedSnapshot
        if (cached != null && cached.version == version && cached.text === text) return cached
        return DocumentSnapshot(text, version).also { cachedSnapshot = it }
    }

    /** Replaces the whole text as one undoable change and puts the caret at [caret] (default: end). */
    public fun setText(text: String, caret: Int = text.length) {
        if (text == textState) {
            selectionInternal = Selection(caret)
            return
        }
        apply(EditOutcome(listOf(Replacement(0, textState.length, text)), Selection(caret.coerceIn(0, text.length))))
    }

    /** Replaces the text and clears undo history, e.g. when loading a different file. */
    public fun resetText(text: String, caret: Int = 0) {
        apply(EditOutcome(listOf(Replacement(0, textState.length, text)), Selection(caret.coerceIn(0, text.length))), record = false)
        history.clear()
        foldedState = emptySet()
        undoRevision++
    }

    /** Selects offsets [start] to [end]; the caret goes to [end]. */
    public fun select(start: Int, end: Int = start) {
        selectionInternal = Selection(start, end)
    }

    /** Selects [range]; the caret goes to its end. */
    public fun select(range: TextRange) {
        val snapshot = snapshot()
        selectionInternal = Selection(snapshot.offsetOf(range.start), snapshot.offsetOf(range.end))
    }

    /** Selects the whole text. */
    public fun selectAll() {
        selectionInternal = Selection(0, textState.length)
    }

    /** Replaces the selection with [text] as one undoable change. */
    public fun insert(text: String) {
        val selection = selectionState
        apply(EditOutcome(listOf(Replacement(selection.start, selection.end, text)), Selection(selection.start + text.length)))
    }

    /**
     * Applies LSP [edits] (ranges refer to the current text and must not overlap) as one undoable
     * change. The selection is mapped through the edits.
     */
    public fun edit(edits: List<TextEdit>) {
        if (edits.isEmpty()) return
        val snapshot = snapshot()
        val replacements = edits.map { Replacement(snapshot.offsetOf(it.range.start), snapshot.offsetOf(it.range.end), it.newText) }
        apply(EditOutcome(replacements, shiftSelection(selectionState, replacements)))
    }

    /** Undoes the last change. Returns false when there was nothing to undo. */
    public fun undo(): Boolean {
        val (text, selection) = history.undo(textState) ?: return false
        remapHistoryFolds(text)
        replaceAll(text, selection, typed = null)
        undoRevision++
        return true
    }

    /** Redoes the last undone change. Returns false when there was nothing to redo. */
    public fun redo(): Boolean {
        val (text, selection) = history.redo(textState) ?: return false
        remapHistoryFolds(text)
        replaceAll(text, selection, typed = null)
        undoRevision++
        return true
    }

    /** Collapses the folding range starting at [line], if any. */
    public fun fold(line: Int) {
        if (foldingRanges.any { it.startLine == line }) foldedState = foldedState + line
    }

    /** Expands the folding range starting at [line]. */
    public fun unfold(line: Int) {
        foldedState = foldedState - line
    }

    /** Toggles the folding range starting at [line]. */
    public fun toggleFold(line: Int) {
        if (line in foldedState) unfold(line) else fold(line)
    }

    /** Collapses every known folding range. */
    public fun foldAll() {
        foldedState = foldingRanges.map { it.startLine }.toSet()
    }

    /** Expands everything. */
    public fun unfoldAll() {
        foldedState = emptySet()
    }

    /** Scrolls the editor so 0-based [line] is visible (centered when it was off screen). */
    public fun revealLine(line: Int) {
        val snapshot = snapshot()
        val clamped = line.coerceIn(0, snapshot.lineCount - 1)
        revealRange(TextRange.at(TextPosition(clamped, 0)))
    }

    /** Scrolls the editor so [range] is visible, unfolding as needed. */
    public fun revealRange(range: TextRange) {
        revealRequest = RevealRequest(range, (revealRequest?.id ?: 0) + 1)
    }

    internal fun foldingTarget(collapse: Boolean): FoldingRange? = foldingRanges
        .filter { caret.line in it.startLine..it.endLine && (it.startLine in foldedState) != collapse }
        .minByOrNull { it.endLine - it.startLine }

    internal fun foldAtCaret(collapse: Boolean): Boolean {
        val target = foldingTarget(collapse) ?: return false
        if (collapse) {
            fold(target.startLine)
            keepSelectionVisible()
        } else {
            unfold(target.startLine)
        }
        return true
    }

    internal fun keepSelectionVisible() {
        val snapshot = snapshot()
        val anchorLine = snapshot.positionOf(selectionStart).line
        val caretLine = snapshot.positionOf(selectionEnd).line
        val target = foldingRanges.filter { range ->
            range.startLine in foldedState &&
                (anchorLine in range.startLine + 1..range.endLine || caretLine in range.startLine + 1..range.endLine)
        }.minByOrNull { it.startLine } ?: return
        val column = caret.character.coerceAtMost(snapshot.lineLength(target.startLine))
        select(snapshot.lineStart(target.startLine) + column)
    }

    /** Expands any collapsed range that hides [line]. */
    internal fun unfoldToShow(line: Int) {
        val hiding = foldingRanges.filter { it.startLine in foldedState && line in (it.startLine + 1)..it.endLine }
        if (hiding.isNotEmpty()) foldedState = foldedState - hiding.map { it.startLine }.toSet()
    }

    internal fun addTextListener(listener: (String) -> Unit) {
        textListeners += listener
    }

    internal fun removeTextListener(listener: (String) -> Unit) {
        textListeners -= listener
    }

    /**
     * Applies an editing [outcome]. [typed] marks a single typed character for completion triggers.
     * Returns false when nothing changed.
     */
    internal fun apply(outcome: EditOutcome, typed: Char? = null, record: Boolean = true): Boolean {
        val replacements = outcome.replacements.filter { !(it.start == it.end && it.text.isEmpty()) }
        if (replacements.isEmpty()) {
            selectionInternal = outcome.selection
            return false
        }
        val before = selectionState
        val oldText = textState
        val sorted = replacements.withIndex().sortedWith(
            compareByDescending<IndexedValue<Replacement>> { it.value.start }
                .thenByDescending { it.value.end }
                .thenByDescending { it.index },
        ).map { it.value }
        val builder = StringBuilder(oldText)
        val changes = ArrayList<EditHistory.Change>(sorted.size)
        var previousStart = Int.MAX_VALUE
        for (r in sorted) {
            val start = r.start.coerceIn(0, oldText.length)
            val end = r.end.coerceIn(start, oldText.length)
            require(end <= previousStart) { "Overlapping replacements" }
            previousStart = start
            changes += EditHistory.Change(start, oldText.substring(start, end), r.text)
            builder.setRange(start, end, r.text)
        }
        val newText = builder.toString()
        if (newText == oldText) {
            selectionInternal = outcome.selection
            return false
        }
        remapFolds(oldText, newText, sorted)
        if (record) {
            history.record(changes, before, outcome.selection, outcome.kind)
            undoRevision++
        }
        replaceAll(newText, outcome.selection, typed)
        return true
    }

    private fun remapHistoryFolds(text: String) {
        val diff = diffText(textState, text)
        remapFolds(textState, text, listOf(Replacement(diff.start, diff.oldEnd, diff.newText)))
    }

    private fun replaceAll(text: String, selection: Selection, typed: Char?) {
        lastTypedCharacter = typed
        textState = text
        versionState++
        selectionState = selection.coerceIn(text.length)
        val listeners = textListeners.toList()
        listeners.forEach { it(text) }
    }

    /** Shifts collapsed fold start lines below an edit and drops folds the edit touched. */
    private fun remapFolds(oldText: String, newText: String, descending: List<Replacement>) {
        if (foldedState.isEmpty()) return
        val oldIndex = LineIndex(oldText)
        val newIndex = LineIndex(newText)
        val ranges = foldingRanges.associateBy { it.startLine }
        foldedState = foldedState.mapNotNull { line ->
            val rangeEnd = ranges[line]?.endLine ?: line
            val touched = descending.any { replacement ->
                val startLine = oldIndex.positionOf(replacement.start).line
                val endLine = oldIndex.positionOf(replacement.end).line
                startLine <= rangeEnd && endLine >= line
            }
            if (touched) {
                null
            } else {
                val mapped = shiftSelection(Selection(oldIndex.lineStart(line)), descending).caret
                newIndex.positionOf(mapped).line
            }
        }.toSet()
    }

    public companion object {
        /** Saves text and selection; history and folding are not saved. */
        public val Saver: Saver<CodeEditorState, Any> = listSaver(
            save = { listOf(it.text, it.selectionStart, it.selectionEnd) },
            restore = {
                val text = it[0] as String
                val start = it[1] as Int
                val end = it[2] as Int
                CodeEditorState(text).apply { selectionInternal = Selection(start, end) }
            },
        )
    }
}

/** A pending request to scroll [range] into view; [id] distinguishes repeated requests. */
internal data class RevealRequest(val range: TextRange, val id: Int)

/** Remembers a [CodeEditorState] across recomposition and saved-state restoration. */
@Composable
public fun rememberCodeEditorState(initialText: String = ""): CodeEditorState =
    rememberSaveable(saver = CodeEditorState.Saver) { CodeEditorState(initialText, 0 until 0) }
