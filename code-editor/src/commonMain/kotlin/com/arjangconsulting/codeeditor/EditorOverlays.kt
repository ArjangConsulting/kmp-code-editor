package com.arjangconsulting.codeeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal const val COMPLETION_LIST_TAG = "CodeEditorCompletionList"
internal const val HOVER_TAG = "CodeEditorHover"
internal const val FIND_FIELD_TAG = "CodeEditorFindField"
internal const val REPLACE_FIELD_TAG = "CodeEditorReplaceField"
internal const val REPLACE_BUTTON_TAG = "CodeEditorReplaceButton"
internal const val REPLACE_ALL_BUTTON_TAG = "CodeEditorReplaceAllButton"

/** Places a popup below [anchor] (relative to the popup's parent), or above when it doesn't fit. */
internal class AnchoredPositionProvider(private val anchor: IntRect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = anchorBounds.left + anchor.left
        val below = anchorBounds.top + anchor.bottom + gap
        val above = anchorBounds.top + anchor.top - gap - popupContentSize.height
        val y = if (below + popupContentSize.height <= windowSize.height || above < 0) below else above
        val x = left.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        return IntOffset(x, y.coerceAtLeast(0))
    }

    override fun equals(other: Any?): Boolean = other is AnchoredPositionProvider && other.anchor == anchor && other.gap == gap

    override fun hashCode(): Int = anchor.hashCode() * 31 + gap
}

private fun Float.toDp(density: androidx.compose.ui.unit.Density): Dp = with(density) { this@toDp.toDp() }

@Composable
internal fun CompletionPopup(model: EditorModel, gutterOffset: Float) {
    val session by model.controller.completion.collectAsState()
    val current = session ?: return
    if (!model.focused) return
    val density = LocalDensity.current
    val config = model.config
    val colors = config.colors
    val m = model.metrics
    val caret = model.caretRect(current.anchor.coerceAtMost(model.state.text.length))
    val anchor = IntRect(
        (caret.left + gutterOffset).roundToInt(),
        caret.top.roundToInt(),
        (caret.left + gutterOffset).roundToInt() + 1,
        caret.bottom.roundToInt(),
    )
    val rowHeight = (m.lineHeight * 1.45f).toDp(density)
    val listWidth = (m.charWidth * 44).toDp(density)
    val maxRows = 10
    Popup(
        popupPositionProvider = AnchoredPositionProvider(anchor, (m.lineHeight * 0.15f).roundToInt()),
        onDismissRequest = { model.controller.closeCompletion() },
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            PopupSurface(colors) {
                if (current.items.isEmpty()) {
                    Text(
                        config.labels.noSuggestions,
                        style = config.textStyle,
                        color = colors.popupSecondaryForeground,
                        modifier = Modifier
                            .width(listWidth)
                            .padding(horizontal = (m.charWidth).toDp(density), vertical = (m.lineHeight * 0.3f).toDp(density))
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(current.selectedIndex) {
                        val visible = listState.layoutInfo.visibleItemsInfo
                        val firstFull = visible.firstOrNull()?.index ?: 0
                        val lastFull = visible.lastOrNull()?.index ?: 0
                        if (current.selectedIndex < firstFull || current.selectedIndex >= lastFull) {
                            listState.scrollToItem((current.selectedIndex - maxRows + 2).coerceAtLeast(0).coerceAtMost(current.selectedIndex))
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .width(listWidth)
                            .heightIn(max = rowHeight * maxRows)
                            .testTag(COMPLETION_LIST_TAG)
                            .semantics { contentDescription = config.labels.completionListDescription },
                    ) {
                        itemsIndexed(current.items, key = { index, item -> "$index:${item.item.label}" }) { index, ranked ->
                            CompletionRow(
                                ranked = ranked,
                                selected = index == current.selectedIndex,
                                colors = colors,
                                textStyle = config.textStyle,
                                height = rowHeight,
                                horizontalPadding = (m.charWidth * 0.5f).toDp(density),
                                onClick = {
                                    model.controller.select(index)
                                    model.acceptCompletion()
                                    model.onRequestFocus()
                                },
                            )
                        }
                    }
                }
            }
            val selected = current.selected
            if (selected != null && (selected.documentation != null || selected.detail != null)) {
                Spacer(Modifier.width((m.charWidth * 0.5f).toDp(density)))
                PopupSurface(colors) {
                    Column(
                        Modifier
                            .width((m.charWidth * 40).toDp(density))
                            .heightIn(max = rowHeight * maxRows)
                            .verticalScroll(rememberScrollState())
                            .padding((m.charWidth * 0.75f).toDp(density)),
                        verticalArrangement = Arrangement.spacedBy((m.lineHeight * 0.3f).toDp(density)),
                    ) {
                        selected.detail?.let { Text(it, style = config.textStyle, color = colors.popupForeground) }
                        selected.documentation?.let { MarkupText(it, config.textStyle, colors, (m.charWidth * 0.5f).toDp(density)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupSurface(colors: CodeEditorColors, content: @Composable () -> Unit) {
    Surface(
        color = colors.popupBackground,
        contentColor = colors.popupForeground,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colors.popupBorder),
        content = content,
    )
}

@Composable
private fun CompletionRow(
    ranked: RankedCompletion,
    selected: Boolean,
    colors: CodeEditorColors,
    textStyle: TextStyle,
    height: Dp,
    horizontalPadding: Dp,
    onClick: () -> Unit,
) {
    val item = ranked.item
    val badgeColor = completionKindColor(item.kind, colors)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(if (selected) colors.popupSelectedBackground else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(horizontalPadding),
    ) {
        Text(
            completionKindBadge(item.kind),
            style = textStyle.copy(fontWeight = FontWeight.Bold),
            color = badgeColor,
            modifier = Modifier.widthIn(min = height * 0.9f),
            maxLines = 1,
        )
        Text(
            highlightMatches(item.label, ranked, colors),
            style = textStyle,
            color = colors.popupForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        item.detail?.let {
            Spacer(Modifier.weight(1f))
            Text(it, style = textStyle, color = colors.popupSecondaryForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun highlightMatches(label: String, ranked: RankedCompletion, colors: CodeEditorColors): AnnotatedString {
    // Matched indices refer to filterText; only highlight when matching ran on the label itself.
    if (ranked.item.filterText != null && ranked.item.filterText != label) return AnnotatedString(label)
    return buildAnnotatedString {
        append(label)
        for (index in ranked.match.matchedIndices) {
            if (index < label.length) addStyle(SpanStyle(color = colors.popupMatchForeground, fontWeight = FontWeight.Bold), index, index + 1)
        }
    }
}

internal fun completionKindBadge(kind: CompletionItemKind?): String = when (kind) {
    CompletionItemKind.METHOD, CompletionItemKind.FUNCTION, CompletionItemKind.CONSTRUCTOR -> "fn"
    CompletionItemKind.FIELD, CompletionItemKind.PROPERTY -> "pr"
    CompletionItemKind.VARIABLE -> "var"
    CompletionItemKind.CLASS, CompletionItemKind.INTERFACE, CompletionItemKind.STRUCT -> "T"
    CompletionItemKind.ENUM, CompletionItemKind.ENUM_MEMBER -> "en"
    CompletionItemKind.KEYWORD -> "kw"
    CompletionItemKind.SNIPPET -> "{}"
    CompletionItemKind.CONSTANT, CompletionItemKind.VALUE -> "c"
    CompletionItemKind.MODULE, CompletionItemKind.FILE, CompletionItemKind.FOLDER, CompletionItemKind.REFERENCE -> "m"
    CompletionItemKind.OPERATOR -> "op"
    CompletionItemKind.TYPE_PARAMETER -> "<T>"
    CompletionItemKind.UNIT, CompletionItemKind.COLOR, CompletionItemKind.EVENT, CompletionItemKind.TEXT, null -> "ab"
}

private fun completionKindColor(kind: CompletionItemKind?, colors: CodeEditorColors): Color = when (kind) {
    CompletionItemKind.METHOD, CompletionItemKind.FUNCTION, CompletionItemKind.CONSTRUCTOR,
    -> colors.tokenColor(TokenKind.FUNCTION)
    CompletionItemKind.KEYWORD -> colors.tokenColor(TokenKind.KEYWORD)
    CompletionItemKind.CLASS, CompletionItemKind.INTERFACE, CompletionItemKind.STRUCT, CompletionItemKind.ENUM,
    CompletionItemKind.TYPE_PARAMETER,
    -> colors.tokenColor(TokenKind.TYPE)
    CompletionItemKind.FIELD, CompletionItemKind.PROPERTY, CompletionItemKind.VARIABLE,
    -> colors.tokenColor(TokenKind.PROPERTY)
    CompletionItemKind.CONSTANT, CompletionItemKind.ENUM_MEMBER, CompletionItemKind.VALUE,
    -> colors.tokenColor(TokenKind.CONSTANT)
    CompletionItemKind.SNIPPET -> colors.tokenColor(TokenKind.ANNOTATION)
    else -> colors.popupSecondaryForeground
}

@Composable
internal fun HoverPopup(model: EditorModel, diagnosticsByLine: Map<Int, List<Diagnostic>>, gutterOffset: Float) {
    val hover by model.controller.hover.collectAsState()
    val completion by model.controller.completion.collectAsState()
    if (completion != null) return
    val density = LocalDensity.current
    val config = model.config
    val m = model.metrics
    val snapshot = model.state.snapshot()
    var gutterLine by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(model.gutterHoverLine) {
        gutterLine = null
        val line = model.gutterHoverLine ?: return@LaunchedEffect
        kotlinx.coroutines.delay(config.serviceOptions.hoverDelayMillis)
        gutterLine = line
    }
    val gutter = gutterLine
    val h = hover
    val (anchorOffset, diagnostics, contents) = when {
        gutter != null -> Triple(snapshot.lineStart(gutter), diagnosticsByLine[gutter].orEmpty(), null)
        h != null && h.offset == model.hoverOffset && h.version == snapshot.version -> {
            val position = snapshot.positionOf(h.offset)
            val atPosition = diagnosticsByLine[position.line].orEmpty().filter {
                val start = snapshot.offsetOf(it.range.start)
                val end = snapshot.offsetOf(it.range.end).coerceAtLeast(start + 1)
                h.offset in start..end
            }
            Triple(h.offset, atPosition, h.hover?.contents)
        }
        else -> return
    }
    if (diagnostics.isEmpty() && contents == null) return
    val rect = model.caretRect(anchorOffset)
    val anchor = if (gutter != null) {
        IntRect(0, rect.top.roundToInt(), 1, rect.bottom.roundToInt())
    } else {
        IntRect((rect.left + gutterOffset).roundToInt(), rect.top.roundToInt(), (rect.left + gutterOffset).roundToInt() + 1, rect.bottom.roundToInt())
    }
    Popup(
        popupPositionProvider = AnchoredPositionProvider(anchor, (m.lineHeight * 0.15f).roundToInt()),
        properties = PopupProperties(focusable = false),
    ) {
        PopupSurface(config.colors) {
            Column(
                Modifier
                    .widthIn(max = (m.charWidth * 70).toDp(density))
                    .heightIn(max = (m.lineHeight * 16).toDp(density))
                    .verticalScroll(rememberScrollState())
                    .padding((m.charWidth * 0.75f).toDp(density))
                    .testTag(HOVER_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy((m.lineHeight * 0.3f).toDp(density)),
            ) {
                diagnostics.forEach { d ->
                    val prefix = listOfNotNull(d.source, d.code).joinToString(" ").let { if (it.isEmpty()) "" else " ($it)" }
                    Text(
                        buildAnnotatedString {
                            withStyleCompat(SpanStyle(color = config.colors.diagnosticColor(d.severity), fontWeight = FontWeight.Bold)) {
                                append(config.labels.severityLabel(d.severity))
                            }
                            append(": ")
                            append(d.message)
                            withStyleCompat(SpanStyle(color = config.colors.popupSecondaryForeground)) { append(prefix) }
                        },
                        style = config.textStyle,
                        color = config.colors.popupForeground,
                    )
                }
                contents?.let { MarkupText(it, config.textStyle, config.colors, (m.charWidth * 0.5f).toDp(density)) }
            }
        }
    }
}

private inline fun AnnotatedString.Builder.withStyleCompat(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val index = pushStyle(style)
    try {
        block()
    } finally {
        pop(index)
    }
}

/** Renders [content]: plain text verbatim, or a safe Markdown subset (no HTML, no links). */
@Composable
internal fun MarkupText(content: MarkupContent, textStyle: TextStyle, colors: CodeEditorColors, codePadding: Dp) {
    if (content.kind == MarkupKind.PLAIN_TEXT) {
        Text(content.value, style = textStyle, color = colors.popupForeground)
        return
    }
    val blocks = remember(content.value) { parseMarkdownBlocks(content.value) }
    Column(verticalArrangement = Arrangement.spacedBy(Dp.Hairline)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> Text(
                    block.text,
                    style = textStyle,
                    color = colors.popupForeground,
                    modifier = Modifier.fillMaxWidth().background(colors.background, MaterialTheme.shapes.extraSmall).padding(codePadding),
                )
                is MarkdownBlock.Paragraph -> Text(
                    renderInlineMarkdown(block.text, colors),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.popupForeground,
                )
            }
        }
    }
}

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock

    data class Code(val text: String) : MarkdownBlock
}

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = ArrayList<MarkdownBlock>()
    val lines = markdown.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            flush()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            blocks += MarkdownBlock.Code(code.toString())
        } else if (line.isBlank()) {
            flush()
        } else {
            if (paragraph.isNotEmpty()) paragraph.append(if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) "\n" else " ")
            paragraph.append(line.trim().removePrefix("#").removePrefix("#").removePrefix("#").trimStart().let { if (line.trimStart().startsWith("#")) "**$it**" else it })
        }
        i++
    }
    flush()
    return blocks
}

/** Inline Markdown: `code`, **bold**, *italic* / _italic_. Everything else stays literal. */
internal fun renderInlineMarkdown(text: String, colors: CodeEditorColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyleCompat(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = colors.background)) {
                        append(text, i + 1, end)
                    }
                    i = end + 1
                } else {
                    append(c)
                    i++
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyleCompat(SpanStyle(fontWeight = FontWeight.Bold)) { append(text, i + 2, end) }
                    i = end + 2
                } else {
                    append(c)
                    i++
                }
            }
            (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1) {
                    withStyleCompat(SpanStyle(fontStyle = FontStyle.Italic)) { append(text, i + 1, end) }
                    i = end + 1
                } else {
                    append(c)
                    i++
                }
            }
            else -> {
                append(c)
                i++
            }
        }
    }
}

/**
 * The find and replace bar shared by both engines. Sizes derive from the editor's [charWidth] and
 * [lineHeight] in pixels. The replace row is hidden for read-only editors.
 */
@Composable
internal fun FindReplaceBar(session: FindSession, config: EditorConfig, charWidth: Float, lineHeight: Float, modifier: Modifier = Modifier) {
    val colors = config.colors
    val labels = config.labels
    val density = LocalDensity.current
    val findFocus = remember { FocusRequester() }
    val replaceFocus = remember { FocusRequester() }
    val canReplace = !config.readOnly
    LaunchedEffect(session.focusRequest) {
        val target = if (session.focusRequest.replace && canReplace && session.replaceVisible) replaceFocus else findFocus
        runCatching { target.requestFocus() }
    }
    LaunchedEffect(session) {
        snapshotFlow { Triple(session.query.text.toString(), session.caseSensitive, session.regex) }.collect { session.incremental() }
    }
    val result = session.result()
    val buttonSize = (lineHeight * 1.3f).toDp(density)
    val padding = (charWidth * 0.5f).toDp(density)
    val fieldWidth = (charWidth * 22).toDp(density)
    val status = when {
        session.replacedCount != null -> labels.replacedCount(session.replacedCount ?: 0)
        session.query.text.isEmpty() -> ""
        result.error == FindError.INVALID_PATTERN || session.replaceError == FindError.INVALID_PATTERN -> labels.findInvalidPattern
        result.error == FindError.TIMED_OUT || session.replaceError == FindError.TIMED_OUT -> labels.findTimedOut
        result.error == FindError.TOO_COMPLEX || session.replaceError == FindError.TOO_COMPLEX -> labels.findTooComplex
        result.matches.isEmpty() -> labels.findNoResults
        else -> labels.findMatchCount(session.index.coerceAtLeast(0) + if (session.index >= 0) 1 else 0, result.matches.size)
    }
    Surface(
        modifier = modifier.onPreviewKeyEvent { event ->
            // Escape closes the bar from any of its controls, not just the query field.
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) true.also { session.close() } else false
        },
        color = colors.popupBackground,
        contentColor = colors.popupForeground,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colors.popupBorder),
    ) {
        Column(Modifier.padding(horizontal = padding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canReplace) {
                    ToolButton(labels.findToggleReplace, session.replaceVisible, buttonSize, colors, onClick = { session.toggleReplace() }) {
                        Icon(
                            if (session.replaceVisible) EditorIcons.ChevronDown else EditorIcons.ChevronRight,
                            contentDescription = null,
                            tint = colors.popupForeground,
                        )
                    }
                }
                FindField(
                    state = session.query,
                    placeholder = labels.findPlaceholder,
                    tag = FIND_FIELD_TAG,
                    focusRequester = findFocus,
                    width = fieldWidth,
                    config = config,
                ) { event ->
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.F3 -> true.also { session.step(if (event.isShiftPressed) -1 else 1) }
                        else -> false
                    }
                }
                Text(
                    text = status,
                    style = config.textStyle,
                    color = colors.popupSecondaryForeground,
                    modifier = Modifier.padding(horizontal = padding).semantics { liveRegion = LiveRegionMode.Polite },
                    maxLines = 1,
                )
                ToolButton(labels.findMatchCase, session.caseSensitive, buttonSize, colors, onClick = { session.caseSensitive = !session.caseSensitive }) {
                    Text("Aa", style = config.textStyle.copy(fontWeight = FontWeight.Bold), color = colors.popupForeground)
                }
                ToolButton(labels.findRegex, session.regex, buttonSize, colors, onClick = { session.regex = !session.regex }) {
                    Text(".*", style = config.textStyle.copy(fontWeight = FontWeight.Bold), color = colors.popupForeground)
                }
                ToolButton(labels.findPrevious, false, buttonSize, colors, onClick = { session.step(-1) }) {
                    Icon(EditorIcons.ChevronUp, contentDescription = null, tint = colors.popupForeground)
                }
                ToolButton(labels.findNext, false, buttonSize, colors, onClick = { session.step(1) }) {
                    Icon(EditorIcons.ChevronDown, contentDescription = null, tint = colors.popupForeground)
                }
                ToolButton(labels.findClose, false, buttonSize, colors, onClick = { session.close() }) {
                    Icon(EditorIcons.Close, contentDescription = null, tint = colors.popupForeground)
                }
            }
            if (canReplace && session.replaceVisible) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(buttonSize))
                    FindField(
                        state = session.replacement,
                        placeholder = labels.replacePlaceholder,
                        tag = REPLACE_FIELD_TAG,
                        focusRequester = replaceFocus,
                        width = fieldWidth,
                        config = config,
                    ) { event ->
                        val shortcut = if (isApplePlatform) event.isMetaPressed else event.isCtrlPressed
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> true.also { if (shortcut) session.replaceAll() else session.replaceCurrent() }
                            else -> false
                        }
                    }
                    TextToolButton(labels.replace, REPLACE_BUTTON_TAG, buttonSize, padding, config, onClick = { session.replaceCurrent() })
                    TextToolButton(labels.replaceAll, REPLACE_ALL_BUTTON_TAG, buttonSize, padding, config, onClick = { session.replaceAll() })
                }
            }
        }
    }
}

/** A single-line field of the find bar with a placeholder; [onKeyDown] handles key presses first. */
@Composable
private fun FindField(
    state: TextFieldState,
    placeholder: String,
    tag: String,
    focusRequester: FocusRequester,
    width: Dp,
    config: EditorConfig,
    onKeyDown: (KeyEvent) -> Boolean,
) {
    val colors = config.colors
    Box(Modifier.width(width), contentAlignment = Alignment.CenterStart) {
        if (state.text.isEmpty()) {
            Text(placeholder, style = config.textStyle, color = colors.popupSecondaryForeground)
        }
        BasicTextField(
            state = state,
            textStyle = config.textStyle.copy(color = colors.popupForeground),
            cursorBrush = SolidColor(colors.caret),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(tag)
                .semantics { contentDescription = placeholder }
                .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKeyDown(event) },
        )
    }
}

@Composable
private fun TextToolButton(label: String, tag: String, height: Dp, padding: Dp, config: EditorConfig, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(height)
            .clickable(onClick = onClick)
            .testTag(tag)
            .semantics { role = Role.Button }
            .padding(horizontal = padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = config.textStyle, color = config.colors.popupForeground, maxLines = 1)
    }
}

@Composable
private fun ToolButton(
    label: String,
    selected: Boolean,
    size: Dp,
    colors: CodeEditorColors,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(if (selected) colors.popupSelectedBackground else Color.Transparent, MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                this.selected = selected
            }
            .padding(size / 6),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Tiny stroked icons so the module needs no icon dependency. */
internal object EditorIcons {
    private fun stroked(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    val ChevronUp: ImageVector = stroked("ChevronUp") {
        moveTo(6f, 15f)
        lineTo(12f, 9f)
        lineTo(18f, 15f)
    }
    val ChevronRight: ImageVector = stroked("ChevronRight") {
        moveTo(9f, 6f)
        lineTo(15f, 12f)
        lineTo(9f, 18f)
    }
    val ChevronDown: ImageVector = stroked("ChevronDown") {
        moveTo(6f, 9f)
        lineTo(12f, 15f)
        lineTo(18f, 9f)
    }
    val Close: ImageVector = stroked("Close") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }
}

/** Thin draggable scrollbars, shown only when the content overflows. */
@Composable
internal fun Scrollbars(model: EditorModel, modifier: Modifier) {
    val m = model.metrics
    if (m.lineHeight <= 0f) return
    val density = LocalDensity.current
    val thickness = (m.charWidth * 0.6f).coerceAtLeast(4f)
    Box(modifier) {
        if (model.maxScrollY > 0f) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(thickness.toDp(density))
                    .fillMaxHeight()
                    .pointerInput(model) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val track = model.viewportHeight
                            val thumb = (track * track / model.contentHeight).coerceAtLeast(m.lineHeight)
                            val ratio = model.maxScrollY / (track - thumb).coerceAtLeast(1f)
                            model.scrollVerticallyBy(drag.y * ratio)
                        }
                    }
                    .drawBehind {
                        val track = size.height
                        val thumb = (track * track / model.contentHeight).coerceAtLeast(m.lineHeight).coerceAtMost(track)
                        val top = (track - thumb) * (model.scrollY / model.maxScrollY.coerceAtLeast(1f))
                        drawRoundRect(model.config.colors.scrollbar, Offset(0f, top), Size(size.width, thumb), CornerRadius(size.width / 2f))
                    },
            )
        }
        if (model.maxScrollX > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .height(thickness.toDp(density))
                    .fillMaxWidth()
                    .pointerInput(model) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val track = model.viewportWidth
                            val thumb = (track * track / model.contentWidth).coerceAtLeast(m.charWidth * 2)
                            val ratio = model.maxScrollX / (track - thumb).coerceAtLeast(1f)
                            model.scrollHorizontallyBy(drag.x * ratio)
                        }
                    }
                    .drawBehind {
                        val track = size.width
                        val thumb = (track * track / model.contentWidth).coerceAtLeast(m.charWidth * 2).coerceAtMost(track)
                        val left = (track - thumb) * (model.scrollX / model.maxScrollX.coerceAtLeast(1f))
                        drawRoundRect(model.config.colors.scrollbar, Offset(left, 0f), Size(thumb, size.height), CornerRadius(size.height / 2f))
                    },
            )
        }
    }
}
