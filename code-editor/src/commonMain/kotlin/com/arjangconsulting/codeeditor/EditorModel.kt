package com.arjangconsulting.codeeditor

import androidx.compose.animation.core.animate
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.text.TextRange as ComposeTextRange

/** Lines longer than this are truncated for display; editing still works on the full text. */
internal const val MAX_RENDERED_LINE_CHARS = 10_000

/** Pixel metrics derived from the text style. */
internal data class EditorMetrics(
    val lineHeight: Float,
    val charWidth: Float,
    val paddingTop: Float,
    val paddingLeft: Float,
) {
    companion object {
        val Zero = EditorMetrics(0f, 0f, 0f, 0f)
    }
}

/** A line as displayed: tabs expanded to spaces, with column mapping back to the document. */
internal class DisplayLine(val text: String, private val docToDisplay: IntArray?, val truncated: Boolean) {
    fun toDisplay(column: Int): Int = docToDisplay?.let { it[column.coerceIn(0, it.size - 1)] } ?: column.coerceAtMost(text.length)

    fun toDocument(displayColumn: Int): Int {
        val map = docToDisplay ?: return displayColumn
        // Columns inside an expanded tab snap to the nearer edge of the tab.
        var low = 0
        var high = map.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (map[mid] <= displayColumn) low = mid else high = mid - 1
        }
        if (low + 1 < map.size && displayColumn - map[low] > map[low + 1] - displayColumn) return low + 1
        return low
    }

    companion object {
        fun of(line: String, tabSize: Int): DisplayLine {
            val truncated = line.length > MAX_RENDERED_LINE_CHARS
            val source = if (truncated) line.substring(0, MAX_RENDERED_LINE_CHARS) else line
            if ('\t' !in source) return DisplayLine(source, null, truncated)
            val map = IntArray(source.length + 1)
            val builder = StringBuilder(source.length + 16)
            for (i in source.indices) {
                map[i] = builder.length
                if (source[i] == '\t') {
                    val spaces = tabSize - (builder.length % tabSize)
                    repeat(spaces) { builder.append(' ') }
                } else {
                    builder.append(source[i])
                }
            }
            map[source.length] = builder.length
            return DisplayLine(builder.toString(), map, truncated)
        }
    }
}

internal class CachedLine(val display: DisplayLine, val layout: TextLayoutResult, var lastUsedFrame: Int)

/**
 * View state and behavior of the Compose engine for one editor. Owns scrolling, metrics,
 * highlighting caches, find, and the hidden input field bridge; translates commands into
 * [CodeEditorState] edits. UI-thread only.
 */
@Stable
internal class EditorModel(
    val state: CodeEditorState,
    val controller: LanguageServiceController,
    val scope: CoroutineScope,
    config: EditorConfig,
) {
    var config: EditorConfig by mutableStateOf(config)
    var clipboard: EditorClipboard? = null
    var focusManager: FocusManager? = null
    var textMeasurer: TextMeasurer? = null
    var onRequestFocus: () -> Unit = {}

    var metrics: EditorMetrics by mutableStateOf(EditorMetrics.Zero)
    var viewportWidth: Float by mutableFloatStateOf(0f)
    var viewportHeight: Float by mutableFloatStateOf(0f)
    var scrollX: Float by mutableFloatStateOf(0f)
    var scrollY: Float by mutableFloatStateOf(0f)
    var focused: Boolean by mutableStateOf(false)
    var caretBlinkOn: Boolean by mutableStateOf(true)
    var caretActivity: Int by mutableIntStateOf(0)
        private set

    /** Document offset under a resting mouse pointer, for hover. */
    var hoverOffset: Int? by mutableStateOf(null)

    var textAreaPositionInRoot: Offset = Offset.Zero

    /** Gutter line under the mouse pointer, for diagnostic tooltips. */
    var gutterHoverLine: Int? by mutableStateOf(null)

    var findVisible: Boolean by mutableStateOf(false)
    val findQuery: TextFieldState = TextFieldState()
    var findCaseSensitive: Boolean by mutableStateOf(false)
    var findIndex: Int by mutableIntStateOf(-1)
    var findFocusRequests: Int by mutableIntStateOf(0)
        private set

    var semanticTokens: Map<Int, List<SemanticToken>> by mutableStateOf(emptyMap())
    var semanticVersion: Long = -1

    private var highlighterInstance: Highlighter = Highlighter(config.language)
    private val lineCache = HashMap<Any, CachedLine>()
    private var frame = 0
    private var cacheStyleKey: Any? = null
    private var preferredDisplayColumn: Int? = null
    private var revealJob: Job? = null

    val bridge: InputBridge = InputBridge(this)

    /** The highlighter, re-created when the language changes and updated to the current text. */
    val highlighter: Highlighter
        get() {
            if (highlighterInstance.rules !== config.language) {
                highlighterInstance = Highlighter(config.language)
                lineCache.clear()
            }
            highlighterInstance.update(state.snapshot())
            return highlighterInstance
        }

    private var foldCacheKey: Any? = null
    private var foldCache: FoldLayout = FoldLayout.unfolded(1)

    /** Visible lines given the current folds. */
    fun foldLayout(): FoldLayout {
        val lineCount = state.snapshot().lineCount
        val ranges = if (config.folding) state.foldingRanges else emptyList()
        val folded = if (config.folding) state.foldedLines else emptySet()
        val key = Triple(lineCount, ranges, folded)
        if (key != foldCacheKey) {
            foldCache = if (folded.isEmpty()) FoldLayout.unfolded(lineCount) else FoldLayout(lineCount, ranges, folded)
            foldCacheKey = key
        }
        return foldCache
    }

    private var widthCacheVersion = -1L
    private var widthCacheTabSize = -1
    private var maxLineColumns = 0

    /** Widest line in columns (tabs expanded), cached per version. */
    fun maxColumns(): Int {
        val snapshot = state.snapshot()
        val tabSize = config.options.tabSize
        if (snapshot.version != widthCacheVersion || tabSize != widthCacheTabSize) {
            var max = 0
            for (line in 0 until snapshot.lineCount) {
                val start = snapshot.lineStart(line)
                val end = snapshot.lineEnd(line)
                var columns = 0
                for (i in start until end) columns += if (snapshot.text[i] == '\t') tabSize - columns % tabSize else 1
                if (columns > max) max = columns
            }
            maxLineColumns = max.coerceAtMost(MAX_RENDERED_LINE_CHARS)
            widthCacheVersion = snapshot.version
            widthCacheTabSize = tabSize
        }
        return maxLineColumns
    }

    val contentHeight: Float get() = metrics.paddingTop * 2 + foldLayout().rowCount * metrics.lineHeight

    val contentWidth: Float get() = metrics.paddingLeft * 2 + (maxColumns() + 2) * metrics.charWidth

    val maxScrollY: Float get() = (contentHeight - viewportHeight).coerceAtLeast(0f)

    val maxScrollX: Float get() = (contentWidth - viewportWidth).coerceAtLeast(0f)

    fun scrollVerticallyBy(delta: Float): Float {
        val old = scrollY
        scrollY = (scrollY + delta).coerceIn(0f, maxScrollY)
        return scrollY - old
    }

    fun scrollHorizontallyBy(delta: Float): Float {
        val old = scrollX
        scrollX = (scrollX + delta).coerceIn(0f, maxScrollX)
        return scrollX - old
    }

    fun clampScroll() {
        scrollY = scrollY.coerceIn(0f, maxScrollY)
        scrollX = scrollX.coerceIn(0f, maxScrollX)
    }

    // region Line layout

    /** Starts a frame for layout-cache eviction. Call once per draw pass. */
    fun beginFrame(styleKey: Any) {
        if (styleKey != cacheStyleKey) {
            lineCache.clear()
            cacheStyleKey = styleKey
        }
        frame++
        if (lineCache.size > 600) lineCache.entries.removeAll { it.value.lastUsedFrame < frame - 1 }
    }

    /** The cached display text and layout of [line]. */
    fun lineLayout(line: Int): CachedLine {
        val lexed = highlighter.line(line)
        val semantic = semanticTokens[line]
        val key: Any = if (semantic == null) lexed else Pair(lexed, semantic)
        lineCache[key]?.let {
            it.lastUsedFrame = frame
            return it
        }
        val text = state.snapshot().line(line)
        val display = DisplayLine.of(text, config.options.tabSize)
        val annotated = buildLineText(display, lexed, semantic)
        val measurer = textMeasurer ?: error("textMeasurer not set")
        val layout = measurer.measure(
            annotated,
            style = config.textStyle.copy(color = config.colors.foreground),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        )
        return CachedLine(display, layout, frame).also { lineCache[key] = it }
    }

    private fun buildLineText(display: DisplayLine, lexed: LexedLine, semantic: List<SemanticToken>?): AnnotatedString {
        val colors = config.colors
        val builder = AnnotatedString.Builder(display.text)
        val length = display.text.length
        fun span(start: Int, end: Int, kind: TokenKind) {
            val s = display.toDisplay(start).coerceAtMost(length)
            val e = display.toDisplay(end).coerceAtMost(length)
            if (e > s && kind != TokenKind.PLAIN) builder.addStyle(SpanStyle(color = colors.tokenColor(kind)), s, e)
        }
        lexed.tokens.forEach { span(it.start, it.end, it.kind) }
        semantic?.forEach { span(it.startCharacter, it.startCharacter + it.length, it.kind) }
        return builder.toAnnotatedString()
    }

    /** X of [column] in [line], relative to the text start (before scrolling and padding). */
    fun columnX(line: Int, column: Int): Float {
        val cached = lineLayout(line)
        val display = cached.display.toDisplay(column)
        if (display >= cached.display.text.length) {
            val overflow = column - (state.snapshot().lineLength(line))
            return cached.layout.size.width + overflow.coerceAtLeast(0) * metrics.charWidth
        }
        return cached.layout.getHorizontalPosition(display, usePrimaryDirection = true)
    }

    /** Top of visual [row] in text-area coordinates. */
    fun rowTop(row: Int): Float = metrics.paddingTop + row * metrics.lineHeight - scrollY

    /** Rectangle of the caret at [offset] in text-area coordinates. */
    fun caretRect(offset: Int = state.selectionInternal.caret): Rect {
        val snapshot = state.snapshot()
        val position = snapshot.positionOf(offset)
        val row = foldLayout().rowOf(position.line)
        val x = metrics.paddingLeft + columnX(position.line, position.character) - scrollX
        val top = rowTop(row)
        return Rect(x, top, x + maxOf(1f, metrics.charWidth / 8f), top + metrics.lineHeight)
    }

    /** Document offset nearest to [point] in text-area coordinates. */
    fun offsetAt(point: Offset): Int {
        val snapshot = state.snapshot()
        val layout = foldLayout()
        val row = ((point.y + scrollY - metrics.paddingTop) / metrics.lineHeight).toInt().coerceIn(0, layout.rowCount - 1)
        val line = layout.lineAt(row)
        val cached = lineLayout(line)
        val x = point.x + scrollX - metrics.paddingLeft
        val displayColumn = if (x <= 0f) 0 else cached.layout.getOffsetForPosition(Offset(x, metrics.lineHeight / 2))
        val column = cached.display.toDocument(displayColumn).coerceAtMost(snapshot.lineLength(line))
        return snapToCodePointBoundary(snapshot.text, snapshot.lineStart(line) + column)
    }

    /** True when [point] is over actual characters (not past the end of the line). */
    fun isOverText(point: Offset): Boolean {
        val layout = foldLayout()
        val row = ((point.y + scrollY - metrics.paddingTop) / metrics.lineHeight).toInt()
        if (row !in 0 until layout.rowCount) return false
        val x = point.x + scrollX - metrics.paddingLeft
        return x >= 0f && x <= lineLayout(layout.lineAt(row)).layout.size.width
    }

    // endregion

    // region Caret, selection, and scrolling

    fun noteCaretActivity() {
        caretActivity++
        caretBlinkOn = true
    }

    /** Moves the caret to [offset], extending the selection when [extend]. */
    fun moveCaret(offset: Int, extend: Boolean, keepPreferredColumn: Boolean = false) {
        val text = state.text
        val target = snapToCodePointBoundary(text, offset.coerceIn(0, text.length))
        val current = state.selectionInternal
        state.selectionInternal = if (extend) Selection(current.anchor, target) else Selection(target)
        if (!keepPreferredColumn) preferredDisplayColumn = null
        val line = state.snapshot().positionOf(target).line
        if (config.folding) state.unfoldToShow(line)
        afterCaretChange()
    }

    fun adoptFieldSelection(selection: Selection) {
        state.selectionInternal = selection
        preferredDisplayColumn = null
        if (config.folding) state.unfoldToShow(state.caret.line)
        afterCaretChange()
    }

    private fun afterCaretChange() {
        noteCaretActivity()
        ensureCaretVisible()
        bridge.sync()
    }

    /** Scrolls the minimum amount that brings the caret into view with a small margin. */
    fun ensureCaretVisible() {
        if (viewportHeight <= 0f || metrics.lineHeight <= 0f) return
        val rect = caretRect()
        val margin = metrics.charWidth * 2
        if (rect.top < 0f) scrollVerticallyBy(rect.top) else if (rect.bottom > viewportHeight) scrollVerticallyBy(rect.bottom - viewportHeight)
        if (rect.left < margin && scrollX > 0f) {
            scrollHorizontallyBy(rect.left - margin)
        } else if (rect.right > viewportWidth - margin) {
            scrollHorizontallyBy(rect.right - viewportWidth + margin)
        }
    }

    /** Scrolls [range] into view, centering it when it was off screen, and unfolds as needed. */
    fun reveal(range: TextRange, animated: Boolean = true) {
        if (config.folding) {
            state.unfoldToShow(range.start.line)
            state.unfoldToShow(range.end.line)
        }
        if (viewportHeight <= 0f || metrics.lineHeight <= 0f) return
        val layout = foldLayout()
        val startRow = layout.rowOf(range.start.line)
        val endRow = layout.rowOf(range.end.line)
        val top = metrics.paddingTop + startRow * metrics.lineHeight
        val bottom = metrics.paddingTop + (endRow + 1) * metrics.lineHeight
        val visible = top >= scrollY && bottom <= scrollY + viewportHeight
        val targetY = if (visible) {
            scrollY
        } else {
            (((top + bottom) / 2f) - viewportHeight / 2f).coerceIn(0f, maxScrollY)
        }
        val startX = metrics.paddingLeft + columnX(range.start.line, range.start.character)
        val targetX = if (startX < scrollX || startX > scrollX + viewportWidth - metrics.charWidth * 2) {
            (startX - viewportWidth / 3f).coerceIn(0f, maxScrollX)
        } else {
            scrollX
        }
        revealJob?.cancel()
        if (!animated || (targetY == scrollY && targetX == scrollX)) {
            scrollY = targetY
            scrollX = targetX
            return
        }
        val fromY = scrollY
        val fromX = scrollX
        revealJob = scope.launch {
            animate(0f, 1f) { fraction, _ ->
                scrollY = fromY + (targetY - fromY) * fraction
                scrollX = fromX + (targetX - fromX) * fraction
            }
        }
    }

    // endregion

    // region Editing

    private fun tokens(): TokenLookup = TokenLookup.of(state.snapshot(), highlighter)

    /** Applies an editing outcome and keeps the caret visible. Returns true when the text changed. */
    fun applyOutcome(outcome: EditOutcome?, typed: Char? = null): Boolean {
        if (outcome == null) return false
        if (config.readOnly) return false
        preferredDisplayColumn = null
        val changed = state.apply(outcome, typed)
        afterCaretChange()
        return changed
    }

    /** Types [c] at the selection with auto-closing and commit characters. */
    fun typeCharacter(c: Char) {
        val session = controller.completion.value
        val selected = session?.selected
        if (selected != null && c in selected.commitCharacters) acceptCompletion()
        val snapshot = state.snapshot()
        applyOutcome(
            typeCharacterOutcome(snapshot, state.selectionInternal, c, config.language, tokens(), config.options.autoCloseBrackets),
            typed = c,
        )
    }

    fun insertNewline() {
        if (config.singleLine) {
            submit()
            return
        }
        val snapshot = state.snapshot()
        applyOutcome(newlineOutcome(snapshot, state.selectionInternal, config.language, tokens(), config.options.autoIndent))
    }

    fun submit() {
        controller.closeCompletion()
        config.onSubmit?.invoke(state.text)
    }

    /** Inserts pasted or programmatic [text], normalizing line breaks (and dropping them when single-line). */
    fun insertText(text: String) {
        var normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (config.singleLine) normalized = normalized.replace('\n', ' ')
        val selection = state.selectionInternal
        applyOutcome(EditOutcome(listOf(Replacement(selection.start, selection.end, normalized)), Selection(selection.start + normalized.length)))
    }

    fun acceptCompletion(): Boolean {
        val session = controller.completion.value ?: return false
        val item = session.selected ?: return false
        val snapshot = state.snapshot()
        val outcome = completionAcceptOutcome(snapshot, item, session, state.selectionInternal.caret)
        controller.closeCompletion()
        applyOutcome(outcome)
        return true
    }

    fun requestCompletion() {
        controller.requestCompletion(state.snapshot(), state.selectionInternal.caret)
    }

    // endregion

    // region Commands

    fun changeFold(collapse: Boolean): Boolean {
        if (!config.folding || !state.foldAtCaret(collapse)) return false
        controller.closeCompletion()
        afterCaretChange()
        return true
    }

    fun changeAllFolds(collapse: Boolean): Boolean {
        if (!config.folding || state.foldingRanges.isEmpty()) return false
        if (collapse) state.foldAll() else state.unfoldAll()
        state.keepSelectionVisible()
        controller.closeCompletion()
        afterCaretChange()
        return true
    }

    /** Executes a key [binding]; returns true when handled (so the key event is consumed). */
    fun execute(binding: KeyBinding): Boolean {
        val command = binding.command
        val popupOpen = controller.completion.value != null
        if (popupOpen) {
            val pageSize = 8
            when (command) {
                EditorCommand.UP -> return true.also { controller.moveSelection(-1) }
                EditorCommand.DOWN -> return true.also { controller.moveSelection(1) }
                EditorCommand.PAGE_UP -> return true.also { controller.moveSelection(-pageSize, wrap = false) }
                EditorCommand.PAGE_DOWN -> return true.also { controller.moveSelection(pageSize, wrap = false) }
                EditorCommand.ENTER, EditorCommand.TAB -> if (acceptCompletion()) return true
                EditorCommand.ESCAPE -> return true.also { controller.closeCompletion() }
                else -> Unit
            }
        }
        val snapshot = state.snapshot()
        val selection = state.selectionInternal
        val text = snapshot.text
        val rules = config.language
        val readOnly = config.readOnly
        when (command) {
            EditorCommand.LEFT -> moveCaret(
                if (!selection.collapsed && !binding.extend) selection.start else previousCodePoint(text, selection.caret).let { crlfStart(text, it) },
                binding.extend,
            )
            EditorCommand.RIGHT -> moveCaret(
                if (!selection.collapsed && !binding.extend) selection.end else nextCodePoint(text, selection.caret).let { crlfEnd(text, it) },
                binding.extend,
            )
            EditorCommand.WORD_LEFT -> moveCaret(previousWordBoundary(text, selection.caret, rules), binding.extend)
            EditorCommand.WORD_RIGHT -> moveCaret(nextWordBoundary(text, selection.caret, rules), binding.extend)
            EditorCommand.LINE_START -> moveCaret(smartHome(snapshot, selection.caret), binding.extend)
            EditorCommand.LINE_END -> moveCaret(snapshot.lineEnd(snapshot.positionOf(selection.caret).line), binding.extend)
            EditorCommand.DOCUMENT_START -> moveCaret(0, binding.extend)
            EditorCommand.DOCUMENT_END -> moveCaret(text.length, binding.extend)
            EditorCommand.UP, EditorCommand.DOWN -> {
                if (config.singleLine) {
                    val history = config.onHistory ?: return false
                    history(if (command == EditorCommand.UP) -1 else 1)
                    return true
                }
                moveVertically(if (command == EditorCommand.UP) -1 else 1, binding.extend)
            }
            EditorCommand.PAGE_UP, EditorCommand.PAGE_DOWN -> {
                val rows = ((viewportHeight / metrics.lineHeight).toInt() - 1).coerceAtLeast(1)
                val delta = if (command == EditorCommand.PAGE_UP) -rows else rows
                scrollVerticallyBy(delta * metrics.lineHeight)
                moveVertically(delta, binding.extend)
            }
            EditorCommand.SELECT_ALL -> {
                state.selectAll()
                noteCaretActivity()
                bridge.sync()
            }
            EditorCommand.BACKSPACE -> if (!readOnly) applyOutcome(backspaceOutcome(snapshot, selection, rules))
            EditorCommand.DELETE_FORWARD -> if (!readOnly) applyOutcome(deleteForwardOutcome(text, selection))
            EditorCommand.DELETE_WORD_BACKWARD -> if (!readOnly) {
                val start = if (selection.collapsed) previousWordBoundary(text, selection.caret, rules) else selection.start
                applyOutcome(EditOutcome(listOf(Replacement(start, selection.end, "")), Selection(start), EditKind.DELETION))
            }
            EditorCommand.DELETE_WORD_FORWARD -> if (!readOnly) {
                val end = if (selection.collapsed) nextWordBoundary(text, selection.caret, rules) else selection.end
                applyOutcome(EditOutcome(listOf(Replacement(selection.start, end, "")), Selection(selection.start), EditKind.DELETION))
            }
            EditorCommand.DELETE_TO_LINE_START -> if (!readOnly) {
                val lineStart = snapshot.lineStart(snapshot.positionOf(selection.start).line)
                val start = if (selection.collapsed) lineStart else selection.start
                applyOutcome(EditOutcome(listOf(Replacement(start, selection.end, "")), Selection(start), EditKind.DELETION))
            }
            EditorCommand.ENTER -> {
                if (readOnly) return false
                insertNewline()
            }
            EditorCommand.TAB -> {
                if (config.tabBehavior == TabBehavior.MOVE_FOCUS || readOnly) {
                    return focusManager?.moveFocus(FocusDirection.Next) ?: false
                }
                applyOutcome(indentOutcome(snapshot, selection, rules))
            }
            EditorCommand.OUTDENT -> {
                if (config.tabBehavior == TabBehavior.MOVE_FOCUS || readOnly) {
                    return focusManager?.moveFocus(FocusDirection.Previous) ?: false
                }
                applyOutcome(outdentOutcome(snapshot, selection, rules))
            }
            EditorCommand.UNDO -> if (!readOnly && state.undo()) afterCaretChange()
            EditorCommand.REDO -> if (!readOnly && state.redo()) afterCaretChange()
            EditorCommand.COPY -> copy(cut = false)
            EditorCommand.CUT -> copy(cut = !readOnly)
            EditorCommand.PASTE -> if (!readOnly) paste()
            EditorCommand.FIND -> openFind()
            EditorCommand.FIND_NEXT -> findStep(1)
            EditorCommand.FIND_PREVIOUS -> findStep(-1)
            EditorCommand.ESCAPE -> when {
                findVisible -> closeFind()
                !selection.collapsed -> moveCaret(selection.caret, extend = false)
                else -> return false
            }
            EditorCommand.TRIGGER_COMPLETION -> if (!readOnly) requestCompletion()
            EditorCommand.TOGGLE_COMMENT -> if (!readOnly) applyOutcome(toggleLineCommentOutcome(snapshot, selection, rules))
            EditorCommand.FOLD -> return changeFold(collapse = true)
            EditorCommand.UNFOLD -> return changeFold(collapse = false)
        }
        return true
    }

    private fun crlfStart(text: String, offset: Int): Int =
        if (offset > 0 && text.getOrNull(offset) == '\n' && text[offset - 1] == '\r') offset - 1 else offset

    private fun crlfEnd(text: String, offset: Int): Int =
        if (offset > 0 && offset < text.length && text[offset - 1] == '\r' && text[offset] == '\n') offset + 1 else offset

    private fun moveVertically(rows: Int, extend: Boolean) {
        val snapshot = state.snapshot()
        val caret = state.selectionInternal.caret
        val position = snapshot.positionOf(caret)
        val layout = foldLayout()
        val row = layout.rowOf(position.line)
        val targetRow = row + rows
        if (targetRow < 0) return moveCaret(0, extend)
        if (targetRow >= layout.rowCount) return moveCaret(snapshot.text.length, extend)
        val currentDisplay = lineLayout(position.line).display.toDisplay(position.character)
        val column = preferredDisplayColumn ?: currentDisplay
        preferredDisplayColumn = column
        val targetLine = layout.lineAt(targetRow)
        val targetCached = lineLayout(targetLine)
        val documentColumn = targetCached.display.toDocument(column).coerceAtMost(snapshot.lineLength(targetLine))
        moveCaret(snapshot.lineStart(targetLine) + documentColumn, extend, keepPreferredColumn = true)
    }

    private fun copy(cut: Boolean) {
        val snapshot = state.snapshot()
        val selection = state.selectionInternal
        // With no selection, copy/cut the whole line, as code editors do.
        val (start, end) = if (selection.collapsed) {
            val line = snapshot.positionOf(selection.caret).line
            val nextStart = if (line + 1 < snapshot.lineCount) snapshot.lineStart(line + 1) else snapshot.lineEnd(line)
            snapshot.lineStart(line) to nextStart
        } else {
            selection.start to selection.end
        }
        var text = snapshot.text.substring(start, end)
        if (selection.collapsed && !text.endsWith('\n')) text += "\n"
        val clipboard = clipboard ?: return
        scope.launch { clipboard.setText(text) }
        if (cut) applyOutcome(EditOutcome(listOf(Replacement(start, end, "")), Selection(start), EditKind.OTHER))
    }

    private fun paste() {
        val clipboard = clipboard ?: return
        scope.launch { clipboard.getText()?.let(::insertText) }
    }

    fun selectWordAt(offset: Int) {
        val range = wordRangeAt(state.text, offset, config.language)
        state.selectionInternal = if (range.isEmpty()) Selection(offset) else Selection(range.first, range.last + 1)
        afterCaretChange()
    }

    fun selectLineAt(offset: Int) {
        val snapshot = state.snapshot()
        val line = snapshot.positionOf(offset).line
        val end = if (line + 1 < snapshot.lineCount) snapshot.lineStart(line + 1) else snapshot.lineEnd(line)
        state.selectionInternal = Selection(snapshot.lineStart(line), end)
        afterCaretChange()
    }

    // endregion

    private var bracketKey: Any? = null
    private var bracketCache: BracketPair? = null

    /** The bracket pair at the caret, cached per version and caret. */
    fun matchingBracket(): BracketPair? {
        val caret = state.selectionInternal.caret
        val key = Triple(state.version, caret, config.language)
        if (key != bracketKey) {
            bracketCache = findMatchingBracket(state.text, caret, config.language, tokens())
            bracketKey = key
        }
        return bracketCache
    }

    // region Find

    private var findCacheKey: Any? = null
    private var findCache: List<IntRange> = emptyList()

    fun findMatches(): List<IntRange> {
        val query = findQuery.text.toString()
        val key = Triple(query, findCaseSensitive, state.version)
        if (!findVisible) return emptyList()
        if (key != findCacheKey) {
            findCache = findMatches(state.text, query, findCaseSensitive)
            findCacheKey = key
        }
        return findCache
    }

    fun openFind() {
        val selected = state.selectedText
        if (selected.isNotEmpty() && '\n' !in selected) {
            findQuery.edit {
                replace(0, length, selected)
                selection = ComposeTextRange(0, length)
            }
        } else {
            findQuery.edit { selection = ComposeTextRange(0, length) }
        }
        findVisible = true
        findFocusRequests++
        findIndex = -1
    }

    fun closeFind() {
        findVisible = false
        findIndex = -1
        onRequestFocus()
    }

    /** Moves to the next ([direction] = 1) or previous match relative to the caret and selects it. */
    fun findStep(direction: Int) {
        if (!findVisible) {
            openFind()
            return
        }
        val matches = findMatches()
        if (matches.isEmpty()) {
            findIndex = -1
            return
        }
        val caret = state.selectionInternal
        val index = if (findIndex in matches.indices && matches[findIndex].first == caret.start && matches[findIndex].last + 1 == caret.end) {
            (findIndex + direction + matches.size) % matches.size
        } else if (direction > 0) {
            matches.indexOfFirst { it.first >= caret.end }.takeIf { it >= 0 } ?: 0
        } else {
            matches.indexOfLast { it.last + 1 <= caret.start }.takeIf { it >= 0 } ?: (matches.size - 1)
        }
        selectMatch(matches, index)
    }

    /** Selects the first match at or after the caret while the query is typed. */
    fun findIncremental() {
        val matches = findMatches()
        if (matches.isEmpty()) {
            findIndex = -1
            return
        }
        val from = state.selectionInternal.start
        selectMatch(matches, matches.indexOfFirst { it.first >= from }.takeIf { it >= 0 } ?: 0)
    }

    private fun selectMatch(matches: List<IntRange>, index: Int) {
        findIndex = index
        val match = matches[index]
        state.selectionInternal = Selection(match.first, match.last + 1)
        val snapshot = state.snapshot()
        reveal(TextRange(snapshot.positionOf(match.first), snapshot.positionOf(match.last + 1)), animated = false)
        noteCaretActivity()
        bridge.sync()
    }

    // endregion
}

/**
 * Mirrors a small window of the document (the selected lines plus one line of context on each side)
 * into a hidden `BasicTextField`, so platform text input, IME composition, soft keyboards, and
 * accessibility work while the editor renders text itself. Edits the field receives are diffed
 * against the window and applied to the document; editing commands that the editor handles itself
 * (from key events) re-sync the window afterwards.
 */
internal class InputBridge(private val model: EditorModel) {
    val field: TextFieldState = TextFieldState()
    private var windowStart = 0
    private var windowText = ""
    private var detached = false
    private var syncedVersion = -1L
    private var inTransformation = false

    /** Document line of the first window line, used to position the field for IME popups. */
    var windowFirstLine: Int by mutableIntStateOf(0)
        private set

    private data class Window(val start: Int, val text: String, val firstLine: Int, val selection: ComposeTextRange, val detached: Boolean)

    private fun computeWindow(): Window {
        val state = model.state
        val snapshot = state.snapshot()
        val selection = state.selectionInternal
        val firstSelected = snapshot.positionOf(selection.start).line
        val lastSelected = snapshot.positionOf(selection.end).line
        val detached = lastSelected - firstSelected > MAX_WINDOW_LINES
        val caretLine = snapshot.positionOf(selection.caret).line
        val first = (if (detached) caretLine else firstSelected) - 1
        val last = (if (detached) caretLine else lastSelected) + 1
        val firstLine = first.coerceAtLeast(0)
        val lastLine = last.coerceAtMost(snapshot.lineCount - 1)
        val start = snapshot.lineStart(firstLine)
        val end = snapshot.lineEnd(lastLine)
        val text = snapshot.text.substring(start, end)
        val fieldSelection = if (detached) {
            ComposeTextRange(selection.caret - start)
        } else {
            ComposeTextRange(selection.anchor - start, selection.caret - start)
        }
        return Window(start, text, firstLine, fieldSelection, detached)
    }

    /** Pushes the current document window into the field when it differs. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    fun sync() {
        // Inside the field's own input transformation the buffer is reconciled at the end instead.
        if (inTransformation) return
        val window = computeWindow()
        val current = field.text
        if (current.toString() != window.text || field.selection != window.selection) {
            field.edit {
                if (current.toString() != window.text) replace(0, length, window.text)
                selection = window.selection
            }
            field.undoState.clearHistory()
        }
        adopt(window)
    }

    private fun adopt(window: Window) {
        windowStart = window.start
        windowText = window.text
        detached = window.detached
        windowFirstLine = window.firstLine
        syncedVersion = model.state.version
    }

    /** Applies a field selection change (e.g. iOS keyboard cursor control) to the document. */
    fun onFieldSelectionChanged(selection: ComposeTextRange) {
        if (field.text.toString() != windowText || detached) return
        val mapped = Selection(windowStart + selection.start, windowStart + selection.end)
        val current = model.state.selectionInternal
        if (mapped == current) return
        model.adoptFieldSelection(mapped)
    }

    val inputTransformation: InputTransformation = InputTransformation { onInput(this) }

    private fun onInput(buffer: TextFieldBuffer) {
        val state = model.state
        if (model.config.readOnly) {
            buffer.revertAllChanges()
            return
        }
        val original = buffer.originalText.toString()
        val current = buffer.asCharSequence().toString()
        val stale = syncedVersion != state.version || original != windowText ||
            !state.text.regionMatches(windowStart, windowText, 0, windowText.length)
        val diff = diffText(original, current, caretHint = buffer.originalSelection.min)
        if (diff.isNoOp) return
        inTransformation = true
        try {
            if (stale) {
                // The document changed underneath the field: apply the inserted text at the caret.
                if (diff.newText.isNotEmpty()) model.insertText(diff.newText)
            } else {
                applyDiff(diff, buffer)
            }
        } finally {
            inTransformation = false
        }
        val window = computeWindow()
        if (buffer.asCharSequence().toString() != window.text) buffer.replace(0, buffer.length, window.text)
        buffer.selection = window.selection
        adopt(window)
    }

    private fun applyDiff(diff: TextDiff, buffer: TextFieldBuffer) {
        val state = model.state
        val selection = state.selectionInternal
        val start = windowStart + diff.start
        val end = windowStart + diff.oldEnd
        val inserted = diff.newText
        val removedLength = end - start
        when {
            field.composition != null -> {
                val mapped = Selection(windowStart + buffer.selection.start, windowStart + buffer.selection.end)
                model.applyOutcome(EditOutcome(listOf(Replacement(start, end, inserted)), mapped))
            }
            // A selection spanning beyond the window: the first keystroke replaces it.
            detached && !selection.collapsed -> {
                if (inserted.length == 1 && removedLength == 0) model.typeCharacter(inserted[0]) else model.insertText(inserted)
            }
            inserted == "\n" && removedLength == 0 -> model.insertNewline()
            removedLength == 0 && isSingleCodePoint(inserted) && selection.collapsed && start == selection.caret ->
                if (inserted.length == 1) model.typeCharacter(inserted[0]) else model.insertText(inserted)
            inserted.length == 1 && removedLength > 0 && start == selection.start && end == selection.end ->
                model.typeCharacter(inserted[0])
            inserted.isEmpty() && selection.collapsed && end == selection.caret && start == previousCodePoint(state.text, end) ->
                model.execute(KeyBinding(EditorCommand.BACKSPACE))
            inserted.isEmpty() && selection.collapsed && start == selection.caret && end == nextCodePoint(state.text, start) ->
                model.execute(KeyBinding(EditorCommand.DELETE_FORWARD))
            else -> {
                var text = inserted
                if (model.config.singleLine) text = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
                val bufferSelection = buffer.selection
                val newSelection = Selection(windowStart + bufferSelection.start, windowStart + bufferSelection.end)
                val kind = if (text.isEmpty()) EditKind.DELETION else EditKind.OTHER
                model.applyOutcome(EditOutcome(listOf(Replacement(start, end, text)), newSelection.coerceIn(state.text.length - removedLength + text.length), kind))
            }
        }
    }

    private fun isSingleCodePoint(text: String): Boolean =
        text.length == 1 || (text.length == 2 && text[0].isHighSurrogate() && text[1].isLowSurrogate())

    private companion object {
        const val MAX_WINDOW_LINES = 60
    }
}

/** Text style plus colors: the key for invalidating cached line layouts. */
internal data class LineStyleKey(val style: TextStyle, val colors: CodeEditorColors, val tabSize: Int)
