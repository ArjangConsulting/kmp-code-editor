package com.arjangconsulting.codeeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Test tag of the hidden input field, for UI tests that need to type into the editor. */
internal const val CODE_EDITOR_INPUT_TAG = "CodeEditorInput"

private const val CARET_BLINK_MILLIS = 530L

/** The pure Compose engine: a virtualized, self-drawn editor with a hidden input field. */
@Composable
internal fun ComposeCodeEditor(state: CodeEditorState, controller: LanguageServiceController, config: EditorConfig, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val model = remember(state, controller) { EditorModel(state, controller, scope, config) }
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val clipboard = rememberEditorClipboard()
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    val fieldFocus = remember { FocusRequester() }
    model.textMeasurer = textMeasurer
    model.clipboard = clipboard
    model.focusManager = focusManager
    model.onRequestFocus = { runCatching { fieldFocus.requestFocus() } }
    SideEffect { model.config = config }

    val metrics = remember(config.textStyle, config.singleLine, density, textMeasurer) {
        val measured = textMeasurer.measure("M", config.textStyle)
        val lineHeight = measured.size.height.toFloat()
        val charWidth = measured.size.width.toFloat().coerceAtLeast(1f)
        EditorMetrics(
            lineHeight = lineHeight,
            charWidth = charWidth,
            paddingTop = if (config.singleLine) lineHeight * 0.35f else lineHeight * 0.25f,
            paddingLeft = charWidth * 0.75f,
        )
    }
    SideEffect { model.metrics = metrics }

    val semantic by controller.semanticTokens.collectAsState()
    LaunchedEffect(semantic, state.version) {
        model.semanticTokens = semantic?.takeIf { it.version == state.version }?.value?.groupBy { it.line } ?: emptyMap()
    }

    LaunchedEffect(model) {
        snapshotFlow { state.revealRequest }.collect { request ->
            if (request == null) return@collect
            snapshotFlow { model.viewportHeight > 0f && model.metrics.lineHeight > 0f }.first { it }
            model.reveal(request.range)
        }
    }
    LaunchedEffect(model.focused, model.caretActivity) {
        model.caretBlinkOn = true
        if (!model.focused) return@LaunchedEffect
        while (true) {
            delay(CARET_BLINK_MILLIS)
            model.caretBlinkOn = !model.caretBlinkOn
        }
    }
    LaunchedEffect(model) {
        snapshotFlow { state.version to state.selectionInternal }.collect {
            model.bridge.sync()
            model.clampScroll()
        }
    }
    LaunchedEffect(model) {
        snapshotFlow {
            val m = model.metrics
            if (m.lineHeight <= 0f || model.viewportHeight <= 0f) {
                0..0
            } else {
                val fold = model.foldLayout()
                val (first, last) = visibleRows(model, model.viewportHeight, fold.rowCount)
                fold.lineAt(first)..fold.lineAt(last)
            }
        }.collect { state.visibleLines = it }
    }
    LaunchedEffect(model) {
        snapshotFlow { model.bridge.field.selection }.collect { model.bridge.onFieldSelectionChanged(it) }
    }

    val lineCountDigits by remember(state) { derivedStateOf { state.snapshot().lineCount.toString().length } }
    // The gutter (and its diagnostic markers) only exists with line numbers or folding, so it never
    // appears or disappears as diagnostics arrive and shifts the text.
    val showGutter = config.showLineNumbers || config.folding
    val gutterLayout = GutterLayout.of(metrics, lineCountDigits, config.showLineNumbers, config.folding)
    val diagnosticsByLine = remember(config.diagnostics) { groupDiagnosticsByLine(config.diagnostics) }
    val caretPosition by remember(state) { derivedStateOf { state.caret } }

    val selectionColors = TextSelectionColors(handleColor = Color.Transparent, backgroundColor = Color.Transparent)
    Box(
        modifier
            .editorHeight(model, config.singleLine)
            .background(config.colors.background)
            .clipToBounds()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !model.focused || model.bridge.field.composition != null) {
                    return@onPreviewKeyEvent false
                }
                val binding = mapKey(
                    KeyChord(event.key, event.isShiftPressed, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed),
                    isApplePlatform,
                ) ?: return@onPreviewKeyEvent false
                model.execute(binding)
            },
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            BasicTextField(
                state = model.bridge.field,
                modifier = Modifier
                    .matchParentSize()
                    .semantics {
                        contentDescription = config.labels.editorDescription
                        stateDescription = config.labels.caretDescription(
                            caretPosition.line + 1,
                            caretPosition.character + 1,
                        )
                        customActions = if (config.folding) buildList {
                            if (state.foldingTarget(collapse = true) != null) {
                                add(CustomAccessibilityAction(config.labels.fold) { model.changeFold(collapse = true) })
                            }
                            if (state.foldingTarget(collapse = false) != null) {
                                add(CustomAccessibilityAction(config.labels.unfold) {
                                    model.changeFold(collapse = false)
                                })
                            }
                            if (state.foldingRanges.isNotEmpty()) {
                                add(CustomAccessibilityAction(config.labels.foldAll) {
                                    model.changeAllFolds(collapse = true)
                                })
                                add(CustomAccessibilityAction(config.labels.unfoldAll) {
                                    model.changeAllFolds(collapse = false)
                                })
                            }
                        } else emptyList()
                    }
                    .testTag(CODE_EDITOR_INPUT_TAG)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(Constraints())
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val row = model.foldLayout().rowOf(model.bridge.windowFirstLine)
                            val x = (if (showGutter) gutterLayout.width else 0f) + model.metrics.paddingLeft - model.scrollX
                            placeable.place(x.roundToInt(), model.rowTop(row).roundToInt())
                        }
                    }
                    .focusRequester(fieldFocus)
                    .onFocusChanged {
                        model.focused = it.isFocused
                        if (!it.isFocused) controller.closeCompletion()
                    },
                readOnly = config.readOnly,
                inputTransformation = model.bridge.inputTransformation,
                textStyle = config.textStyle.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
                lineLimits = TextFieldLineLimits.MultiLine(),
            )
        }
        Row(Modifier.fillMaxSize()) {
            if (showGutter) {
                Gutter(model, gutterLayout, diagnosticsByLine, Modifier.width(with(density) { gutterLayout.width.toDp() }).fillMaxHeight())
            }
            TextArea(model, diagnosticsByLine, textToolbar, Modifier.fillMaxHeight().weight(1f))
        }
        if (model.findVisible) {
            FindBar(model, Modifier.align(Alignment.TopEnd))
        }
        val gutterOffset = if (showGutter) gutterLayout.width else 0f
        CompletionPopup(model, gutterOffset)
        HoverPopup(model, diagnosticsByLine, gutterOffset)
    }
}

/** Single-line editors are one line tall; with unbounded height the editor fits all lines. */
private fun Modifier.editorHeight(model: EditorModel, singleLine: Boolean): Modifier = layout { measurable, constraints ->
    val metrics = model.metrics
    val height = when {
        singleLine -> (metrics.lineHeight + metrics.paddingTop * 2).roundToInt()
        !constraints.hasBoundedHeight -> model.contentHeight.roundToInt().coerceAtLeast((metrics.lineHeight + metrics.paddingTop * 2).roundToInt())
        else -> null
    }
    val childConstraints = if (height != null) {
        val h = height.coerceIn(constraints.minHeight, constraints.maxHeight)
        constraints.copy(minHeight = h, maxHeight = h)
    } else {
        constraints
    }
    val placeable = measurable.measure(childConstraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

internal fun groupDiagnosticsByLine(diagnostics: List<Diagnostic>): Map<Int, List<Diagnostic>> {
    if (diagnostics.isEmpty()) return emptyMap()
    val result = HashMap<Int, MutableList<Diagnostic>>()
    for (d in diagnostics) {
        for (line in d.range.start.line..d.range.end.line) result.getOrPut(line) { ArrayList() } += d
    }
    return result
}

/** Horizontal layout of the gutter: diagnostic marker, line numbers, fold markers. */
internal data class GutterLayout(val markerWidth: Float, val numbersWidth: Float, val foldWidth: Float) {
    val width: Float get() = markerWidth + numbersWidth + foldWidth

    companion object {
        fun of(metrics: EditorMetrics, digits: Int, lineNumbers: Boolean, folding: Boolean): GutterLayout = GutterLayout(
            markerWidth = metrics.charWidth * 1.25f,
            numbersWidth = if (lineNumbers) (maxOf(digits, 2) + 0.5f) * metrics.charWidth else 0f,
            foldWidth = if (folding) metrics.charWidth * 1.75f else metrics.charWidth * 0.5f,
        )
    }
}

@Composable
private fun Gutter(model: EditorModel, layout: GutterLayout, diagnosticsByLine: Map<Int, List<Diagnostic>>, modifier: Modifier) {
    val config = model.config
    val textMeasurer = model.textMeasurer ?: return
    val numberCache = remember(config.textStyle, config.colors) { HashMap<Pair<Int, Boolean>, TextLayoutResult>() }
    Box(
        modifier
            .background(config.colors.gutterBackground)
            .pointerInput(model, layout) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()
                    val m = model.metrics
                    val row = ((down.position.y + model.scrollY - m.paddingTop) / m.lineHeight).toInt()
                    val fold = model.foldLayout()
                    if (row !in 0 until fold.rowCount) return@awaitEachGesture
                    val line = fold.lineAt(row)
                    val inFoldArea = down.position.x >= layout.markerWidth + layout.numbersWidth
                    if (inFoldArea && model.config.folding && model.state.foldingRanges.any { it.startLine == line }) {
                        model.state.toggleFold(line)
                    } else {
                        model.config.onLineClick?.invoke(line) ?: run {
                            model.onRequestFocus()
                            model.selectLineAt(model.state.snapshot().lineStart(line))
                        }
                    }
                }
            }
            .pointerInput(model) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Move -> {
                                val m = model.metrics
                                val row = ((change.position.y + model.scrollY - m.paddingTop) / m.lineHeight).toInt()
                                val fold = model.foldLayout()
                                val line = if (row in 0 until fold.rowCount) fold.lineAt(row) else null
                                model.gutterHoverLine = line?.takeIf { it in diagnosticsByLine && change.position.x < layout.markerWidth + layout.numbersWidth }
                            }
                            PointerEventType.Exit -> model.gutterHoverLine = null
                        }
                    }
                }
            }
            .drawBehind {
                val m = model.metrics
                if (m.lineHeight <= 0f) return@drawBehind
                val state = model.state
                val snapshot = state.snapshot()
                val fold = model.foldLayout()
                val caretLine = snapshot.positionOf(state.selectionInternal.caret).line
                val foldStarts = if (config.folding) state.foldingRanges.associateBy { it.startLine } else emptyMap()
                val (first, last) = visibleRows(model, size.height, fold.rowCount)
                for (row in first..last) {
                    val line = fold.lineAt(row)
                    val top = model.rowTop(row)
                    highlightFor(config.lineHighlights, line)?.let {
                        drawRect(config.colors.lineHighlightColor(it), Offset(0f, top), Size(size.width, m.lineHeight))
                    }
                    diagnosticsByLine[line]?.minByOrNull { it.severity.ordinal }?.let { worst ->
                        drawCircle(
                            config.colors.diagnosticColor(worst.severity),
                            radius = m.charWidth * 0.3f,
                            center = Offset(layout.markerWidth / 2f, top + m.lineHeight / 2f),
                        )
                    }
                    if (config.showLineNumbers) {
                        val active = line == caretLine
                        val number = numberCache.getOrPut((line + 1) to active) {
                            textMeasurer.measure(
                                (line + 1).toString(),
                                style = config.textStyle.copy(color = if (active) config.colors.gutterActiveForeground else config.colors.gutterForeground),
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                        val x = layout.markerWidth + layout.numbersWidth - number.size.width - m.charWidth * 0.25f
                        drawText(number, topLeft = Offset(x, top + (m.lineHeight - number.size.height) / 2f))
                    }
                    if (line in foldStarts) {
                        drawFoldChevron(
                            center = Offset(layout.markerWidth + layout.numbersWidth + layout.foldWidth / 2f, top + m.lineHeight / 2f),
                            size = m.charWidth * 0.7f,
                            collapsed = line in state.foldedLines,
                            color = config.colors.gutterForeground,
                        )
                    }
                }
                if (numberCache.size > 2_000) numberCache.clear()
            },
    )
}

private fun DrawScope.drawFoldChevron(center: Offset, size: Float, collapsed: Boolean, color: Color) {
    val half = size / 2f
    val path = Path()
    if (collapsed) {
        path.moveTo(center.x - half / 2f, center.y - half)
        path.lineTo(center.x + half / 2f, center.y)
        path.lineTo(center.x - half / 2f, center.y + half)
    } else {
        path.moveTo(center.x - half, center.y - half / 2f)
        path.lineTo(center.x, center.y + half / 2f)
        path.lineTo(center.x + half, center.y - half / 2f)
    }
    drawPath(path, color, style = Stroke(width = maxOf(1f, size / 6f), cap = StrokeCap.Round))
}

internal fun highlightFor(highlights: List<LineHighlight>, line: Int): LineHighlightRole? {
    var role: LineHighlightRole? = null
    for (h in highlights) if (line in h.lines) role = h.role
    return role
}

private fun visibleRows(model: EditorModel, height: Float, rowCount: Int): Pair<Int, Int> {
    val m = model.metrics
    val first = floor((model.scrollY - m.paddingTop) / m.lineHeight).toInt().coerceIn(0, maxOf(0, rowCount - 1))
    val last = ceil((model.scrollY + height - m.paddingTop) / m.lineHeight).toInt().coerceIn(0, maxOf(0, rowCount - 1))
    return first to last
}

@Composable
private fun TextArea(
    model: EditorModel,
    diagnosticsByLine: Map<Int, List<Diagnostic>>,
    textToolbar: androidx.compose.ui.platform.TextToolbar,
    modifier: Modifier,
) {
    val config = model.config
    val vertical = rememberScrollableState { model.scrollVerticallyBy(it) }
    val horizontal = rememberScrollableState { model.scrollHorizontallyBy(it) }
    val placeholder = remember(config.textStyle, config.colors, config.labels.foldedPlaceholder) {
        model.textMeasurer?.measure(config.labels.foldedPlaceholder, config.textStyle.copy(color = config.colors.foldPlaceholderForeground))
    }
    Box(
        modifier
            .onGloballyPositioned { model.textAreaPositionInRoot = it.positionInRoot() }
            .onSizeChanged {
                model.viewportWidth = it.width.toFloat()
                model.viewportHeight = it.height.toFloat()
                model.clampScroll()
            }
            .clearAndSetSemantics { hideFromAccessibility() }
            .scrollable(vertical, Orientation.Vertical, enabled = !config.singleLine, reverseDirection = true)
            .scrollable(horizontal, Orientation.Horizontal, reverseDirection = true)
            .pointerHoverIcon(PointerIcon.Text)
            .pointerInput(model) { detectEditorGestures(model, textToolbar, placeholder) }
            .pointerInput(model) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (!model.config.options.hover) continue
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> if (!change.pressed && change.type == PointerType.Mouse) {
                                if (model.isOverText(change.position)) {
                                    val offset = model.offsetAt(change.position)
                                    if (offset != model.hoverOffset) {
                                        model.hoverOffset = offset
                                        val snapshot = model.state.snapshot()
                                        model.controller.requestHover(snapshot, snapshot.positionOf(offset))
                                    }
                                } else if (model.hoverOffset != null) {
                                    model.hoverOffset = null
                                    model.controller.cancelHover()
                                }
                            }
                            PointerEventType.Exit, PointerEventType.Press, PointerEventType.Scroll -> if (model.hoverOffset != null) {
                                model.hoverOffset = null
                                model.controller.cancelHover()
                            }
                        }
                    }
                }
            }
            .drawBehind { drawEditor(model, diagnosticsByLine, placeholder) },
    ) {
        Scrollbars(model, Modifier.matchParentSize())
    }
}

/** Mouse: click places the caret, Shift extends, double/triple click select word/line, drag selects.
 *  Touch: tap places the caret, long press selects a word and shows the text toolbar; drags scroll. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectEditorGestures(
    model: EditorModel,
    textToolbar: androidx.compose.ui.platform.TextToolbar,
    placeholder: TextLayoutResult?,
) {
    var lastClickTime = 0L
    var lastClickPosition = Offset.Zero
    var clickCount = 0
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        model.onRequestFocus()
        textToolbar.hide()
        if (handleFoldPlaceholderClick(model, down.position, placeholder)) {
            down.consume()
            return@awaitEachGesture
        }
        if (down.type == PointerType.Mouse || down.type == PointerType.Stylus) {
            val near = (down.position - lastClickPosition).getDistance() < viewConfiguration.touchSlop
            clickCount = if (down.uptimeMillis - lastClickTime < viewConfiguration.doubleTapTimeoutMillis && near) clickCount + 1 else 1
            lastClickTime = down.uptimeMillis
            lastClickPosition = down.position
            val offset = model.offsetAt(down.position)
            val shift = currentEvent.keyboardModifiers.isShiftPressed
            when {
                clickCount >= 3 -> model.selectLineAt(offset)
                clickCount == 2 -> model.selectWordAt(offset)
                else -> model.moveCaret(offset, extend = shift)
            }
            down.consume()
            val anchor = model.state.selectionInternal
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val position = change.position
                if (position.y < 0f) model.scrollVerticallyBy(position.y / 2f)
                if (position.y > size.height) model.scrollVerticallyBy((position.y - size.height) / 2f)
                val target = model.offsetAt(position)
                if (clickCount == 1) {
                    model.state.selectionInternal = Selection(anchor.anchor, target)
                } else {
                    // Extend by whole words/lines from the initial word/line selection.
                    val start = minOf(anchor.start, target)
                    val end = maxOf(anchor.end, target)
                    model.state.selectionInternal = if (target < anchor.start) Selection(end, start) else Selection(start, end)
                }
                model.noteCaretActivity()
                model.ensureCaretVisible()
                change.consume()
            }
            model.bridge.sync()
        } else {
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
            if (up != null) {
                model.moveCaret(model.offsetAt(up.position), extend = false)
                return@awaitEachGesture
            }
            // Either a long press or a scroll drag took over the pointer.
            if (currentEvent.changes.any { it.isConsumed }) return@awaitEachGesture
            val offset = model.offsetAt(down.position)
            model.selectWordAt(offset)
            val anchor = model.state.selectionInternal
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val target = model.offsetAt(change.position)
                model.state.selectionInternal = Selection(if (target < anchor.start) anchor.end else anchor.start, target)
                model.ensureCaretVisible()
                change.consume()
            }
            model.bridge.sync()
            showTextToolbar(model, textToolbar)
        }
    }
}

private fun handleFoldPlaceholderClick(model: EditorModel, position: Offset, placeholder: TextLayoutResult?): Boolean {
    if (placeholder == null || !model.config.folding) return false
    val m = model.metrics
    val fold = model.foldLayout()
    val row = ((position.y + model.scrollY - m.paddingTop) / m.lineHeight).toInt()
    if (row !in 0 until fold.rowCount) return false
    val line = fold.lineAt(row)
    if (line !in fold.collapsedRanges) return false
    val start = m.paddingLeft + model.lineLayout(line).layout.size.width + m.charWidth * 0.5f - model.scrollX
    val end = start + placeholder.size.width + m.charWidth
    if (position.x !in start..end) return false
    model.state.unfold(line)
    return true
}

private fun showTextToolbar(model: EditorModel, toolbar: androidx.compose.ui.platform.TextToolbar) {
    val selection = model.state.selectionInternal
    val start = model.caretRect(selection.start)
    val end = model.caretRect(selection.end)
    val rect = Rect(
        minOf(start.left, end.left),
        minOf(start.top, end.top),
        maxOf(start.right, end.right),
        maxOf(start.bottom, end.bottom),
    ).translate(model.textAreaPositionInRoot)
    val readOnly = model.config.readOnly
    toolbar.showMenu(
        rect = rect,
        onCopyRequested = { model.execute(KeyBinding(EditorCommand.COPY)) },
        onPasteRequested = if (readOnly) null else ({ model.execute(KeyBinding(EditorCommand.PASTE)) }),
        onCutRequested = if (readOnly || selection.collapsed) null else ({ model.execute(KeyBinding(EditorCommand.CUT)) }),
        onSelectAllRequested = { model.execute(KeyBinding(EditorCommand.SELECT_ALL)) },
    )
}

/** Draws backgrounds, selection, text, decorations, and the caret for the visible rows. */
private fun DrawScope.drawEditor(model: EditorModel, diagnosticsByLine: Map<Int, List<Diagnostic>>, placeholder: TextLayoutResult?) {
    val m = model.metrics
    if (m.lineHeight <= 0f || model.textMeasurer == null) return
    val config = model.config
    val colors = config.colors
    val state = model.state
    model.beginFrame(LineStyleKey(config.textStyle, colors, config.options.tabSize))
    val snapshot = state.snapshot()
    val fold = model.foldLayout()
    val selection = state.selectionInternal
    val caretLine = snapshot.positionOf(selection.caret).line
    val brackets = if (config.options.matchBrackets && model.focused && selection.collapsed) model.matchingBracket() else null
    val matches = model.findMatches()
    val currentMatch = matches.getOrNull(model.findIndex)
    val (first, last) = visibleRows(model, size.height, fold.rowCount)
    val textLeft = m.paddingLeft - model.scrollX
    clipRect {
        for (row in first..last) {
            val line = fold.lineAt(row)
            val top = model.rowTop(row)
            val lineStart = snapshot.lineStart(line)
            val lineEnd = snapshot.lineEnd(line)
            highlightFor(config.lineHighlights, line)?.let {
                drawRect(colors.lineHighlightColor(it), Offset(0f, top), Size(size.width, m.lineHeight))
            }
            if (line == caretLine && config.options.highlightCurrentLine && selection.collapsed && model.focused) {
                drawRect(colors.currentLineBackground, Offset(0f, top), Size(size.width, m.lineHeight))
            }
            val cached = model.lineLayout(line)
            fun x(offset: Int): Float = textLeft + model.columnX(line, offset - lineStart)
            if (matches.isNotEmpty()) {
                var index = lowerBound(matches, lineStart)
                while (index < matches.size && matches[index].first <= lineEnd) {
                    val match = matches[index]
                    val color = if (match == currentMatch) colors.findCurrentMatchBackground else colors.findMatchBackground
                    val s = match.first.coerceAtLeast(lineStart)
                    val e = (match.last + 1).coerceAtMost(lineEnd)
                    if (e > s) drawRect(color, Offset(x(s), top), Size(x(e) - x(s), m.lineHeight))
                    index++
                }
            }
            if (!selection.collapsed && selection.start <= lineEnd && selection.end >= lineStart) {
                val s = selection.start.coerceAtLeast(lineStart)
                val e = selection.end.coerceAtMost(lineEnd)
                val newlineSelected = selection.end > lineEnd
                val right = x(e) + if (newlineSelected) m.charWidth * 0.6f else 0f
                drawRect(colors.selectionBackground, Offset(x(s), top), Size((right - x(s)).coerceAtLeast(0f), m.lineHeight))
            }
            if (brackets != null) {
                for (offset in intArrayOf(brackets.open, brackets.close)) {
                    if (offset in lineStart until lineEnd) {
                        drawRect(colors.matchingBracketBackground, Offset(x(offset), top), Size(x(offset + 1) - x(offset), m.lineHeight))
                    }
                }
            }
            drawText(cached.layout, topLeft = Offset(textLeft, top + (m.lineHeight - cached.layout.size.height) / 2f))
            if (placeholder != null && line in fold.collapsedRanges) {
                val px = textLeft + cached.layout.size.width + m.charWidth * 0.5f
                drawRoundRect(
                    colors.foldPlaceholderBackground,
                    topLeft = Offset(px, top + m.lineHeight * 0.1f),
                    size = Size(placeholder.size.width + m.charWidth, m.lineHeight * 0.8f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(m.lineHeight * 0.2f),
                )
                drawText(placeholder, topLeft = Offset(px + m.charWidth / 2f, top + (m.lineHeight - placeholder.size.height) / 2f))
            }
            diagnosticsByLine[line]?.forEach { d ->
                val start = snapshot.offsetOf(d.range.start).coerceIn(lineStart, lineEnd)
                var end = snapshot.offsetOf(d.range.end).coerceIn(lineStart, lineEnd)
                if (end <= start) end = (start + 1).coerceAtMost(lineEnd)
                val x1 = x(start)
                val x2 = if (end > start) x(end) else x1 + m.charWidth
                drawSquiggle(x1, x2, top + m.lineHeight - m.lineHeight * 0.12f, m, colors.diagnosticColor(d.severity))
            }
        }
        if (model.focused && model.caretBlinkOn && !config.readOnly) {
            val rect = model.caretRect()
            if (rect.bottom >= 0f && rect.top <= size.height) {
                drawRect(colors.caret, rect.topLeft, Size(maxOf(1.dp.toPx(), m.charWidth / 8f), m.lineHeight))
            }
        }
    }
}

private fun lowerBound(matches: List<IntRange>, offset: Int): Int {
    var low = 0
    var high = matches.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (matches[mid].last + 1 < offset) low = mid + 1 else high = mid
    }
    return low
}

private fun DrawScope.drawSquiggle(x1: Float, x2: Float, y: Float, m: EditorMetrics, color: Color) {
    val amplitude = m.lineHeight * 0.07f
    val step = maxOf(2f, m.charWidth / 2.5f)
    val path = Path()
    path.moveTo(x1, y)
    var x = x1
    var up = true
    while (x < x2) {
        val next = minOf(x + step, x2)
        path.lineTo(next, if (up) y - amplitude else y + amplitude)
        up = !up
        x = next
    }
    drawPath(path, color, style = Stroke(width = maxOf(1f, m.lineHeight / 16f)))
}
