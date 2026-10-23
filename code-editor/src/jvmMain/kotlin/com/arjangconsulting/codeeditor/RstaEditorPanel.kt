package com.arjangconsulting.codeeditor

import org.fife.ui.autocomplete.AutoCompletion
import org.fife.ui.autocomplete.AutoCompletionEvent
import org.fife.ui.autocomplete.BasicCompletion
import org.fife.ui.autocomplete.Completion
import org.fife.ui.autocomplete.CompletionProviderBase
import org.fife.ui.autocomplete.ParameterizedCompletion
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.Style
import org.fife.ui.rsyntaxtextarea.SyntaxScheme
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMaker
import org.fife.ui.rsyntaxtextarea.TokenMakerBase
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rsyntaxtextarea.folding.Fold
import org.fife.ui.rsyntaxtextarea.folding.FoldParser
import org.fife.ui.rsyntaxtextarea.folding.FoldParserManager
import org.fife.ui.rsyntaxtextarea.folding.FoldType
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice
import org.fife.ui.rsyntaxtextarea.parser.ParseResult
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ToolTipManager
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.JTextComponent
import javax.swing.text.Segment
import androidx.compose.ui.graphics.Color as ComposeColor

/** Converts a Compose color to AWT, keeping alpha. */
internal fun ComposeColor.toAwt(): Color = Color(red, green, blue, alpha)

/** The RSyntaxTextArea style key for [rules]; also the key for its fold parser. */
internal fun styleKeyFor(rules: TokenRules): String = "text/x-kmpcodeeditor-${rules.name}"

/** The RSTA token type used for each [TokenKind]; distinct so each kind gets its own color. */
internal fun TokenKind.toRstaType(): Int = when (this) {
    TokenKind.PLAIN -> TokenTypes.IDENTIFIER
    TokenKind.KEYWORD -> TokenTypes.RESERVED_WORD
    TokenKind.TYPE -> TokenTypes.DATA_TYPE
    TokenKind.FUNCTION -> TokenTypes.FUNCTION
    TokenKind.VARIABLE -> TokenTypes.VARIABLE
    TokenKind.PROPERTY -> TokenTypes.MARKUP_TAG_ATTRIBUTE
    TokenKind.ATTRIBUTE -> TokenTypes.MARKUP_TAG_NAME
    TokenKind.STRING -> TokenTypes.LITERAL_STRING_DOUBLE_QUOTE
    TokenKind.ESCAPE -> TokenTypes.LITERAL_BACKQUOTE
    TokenKind.NUMBER -> TokenTypes.LITERAL_NUMBER_DECIMAL_INT
    TokenKind.CONSTANT -> TokenTypes.LITERAL_BOOLEAN
    TokenKind.COMMENT -> TokenTypes.COMMENT_EOL
    TokenKind.ANNOTATION -> TokenTypes.ANNOTATION
    TokenKind.OPERATOR -> TokenTypes.OPERATOR
    TokenKind.PUNCTUATION -> TokenTypes.SEPARATOR
    TokenKind.PREPROCESSOR -> TokenTypes.PREPROCESSOR
    TokenKind.ERROR -> TokenTypes.ERROR_IDENTIFIER
}

/**
 * Adapts the shared lexer to RSyntaxTextArea. Multi-line state is carried in RSTA's "last token
 * type on line" as a negative value, the same convention RSTA's own JFlex lexers use.
 */
internal class SharedLexerTokenMaker(private val rules: TokenRules) : TokenMakerBase() {
    override fun getTokenList(text: Segment, initialTokenType: Int, startOffset: Int): Token {
        resetTokenList()
        val line = String(text.array, text.offset, text.count)
        val state = if (initialTokenType < 0) LexerState(-initialTokenType) else LexerState.Initial
        val lexed = rules.tokenizeLine(line, state)
        val base = text.offset
        var pos = 0
        fun gap(end: Int) {
            var i = pos
            while (i < end) {
                val whitespace = line[i].isWhitespace()
                var j = i
                while (j < end && line[j].isWhitespace() == whitespace) j++
                addToken(text.array, base + i, base + j - 1, if (whitespace) TokenTypes.WHITESPACE else TokenTypes.IDENTIFIER, startOffset + i)
                i = j
            }
            pos = end
        }
        for (token in lexed.tokens) {
            if (token.start > pos) gap(token.start)
            addToken(text.array, base + token.start, base + token.end - 1, token.kind.toRstaType(), startOffset + token.start)
            pos = token.end
        }
        if (pos < line.length) gap(line.length)
        if (lexed.endState != LexerState.Initial) {
            // A zero-length end token whose type encodes the state for the next line.
            addToken(text.array, base + line.length, base + line.length - 1, -lexed.endState.value, startOffset + line.length)
        } else {
            addNullToken()
        }
        return firstToken
    }

    override fun getLineCommentStartAndEnd(languageIndex: Int): Array<String?> = arrayOf(rules.lineComment, null)

    override fun getCurlyBracesDenoteCodeBlocks(languageIndex: Int): Boolean = rules.brackets.any { it.first == '{' }

    override fun getShouldIndentNextLineAfter(token: Token?): Boolean {
        if (token == null || token.length() != 1) return false
        val c = token.charAt(0)
        return token.type == TokenTypes.SEPARATOR && rules.brackets.any { it.first == c }
    }

    override fun isIdentifierChar(languageIndex: Int, ch: Char): Boolean = rules.isIdentifierPart(ch)

    override fun getBracketPairs(): String = rules.brackets.joinToString("") { "${it.first}${it.second}" }

    override fun getMarkOccurrencesOfTokenType(type: Int): Boolean = false

    override fun getClosestStandardTokenTypeForInternalType(type: Int): Int =
        if (type < 0) TokenTypes.COMMENT_MULTILINE else type
}

/** RSTA folds from [computeFoldingRanges]. */
internal class SharedFoldParser(private val rules: () -> TokenRules) : FoldParser {
    override fun getFolds(textArea: RSyntaxTextArea): List<Fold> {
        val snapshot = DocumentSnapshot(textArea.text, 0)
        val currentRules = rules()
        val ranges = computeFoldingRanges(snapshot, currentRules, Highlighter(currentRules).also { it.update(snapshot) })
        val roots = ArrayList<Fold>()
        val stack = ArrayList<Pair<FoldingRange, Fold>>()
        for (range in ranges.sortedWith(compareBy({ it.startLine }, { -it.endLine }))) {
            while (stack.isNotEmpty() && stack.last().first.endLine < range.startLine) stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()
            if (parent != null && range.endLine > parent.first.endLine) continue
            val type = if (range.kind == FoldingRangeKind.COMMENT) FoldType.COMMENT else FoldType.CODE
            val start = (snapshot.lineEnd(range.startLine) - 1).coerceAtLeast(snapshot.lineStart(range.startLine))
            val fold = runCatching {
                if (parent == null) Fold(type, textArea, start) else parent.second.createChild(type, start)
            }.getOrNull() ?: continue
            fold.endOffset = snapshot.lineEnd(range.endLine)
            if (parent == null) roots += fold
            stack += range to fold
        }
        return roots
    }
}

/** Publishes [diagnostics] as RSTA parser notices (squiggles, tooltips, error strip). */
internal class DiagnosticsParser : AbstractParser() {
    @Volatile
    var diagnostics: List<Diagnostic> = emptyList()

    @Volatile
    var colors: CodeEditorColors? = null

    override fun parse(doc: RSyntaxDocument, style: String?): ParseResult {
        val result = DefaultParseResult(this)
        val text = doc.getText(0, doc.length)
        val snapshot = DocumentSnapshot(text, 0)
        result.setParsedLines(0, snapshot.lineCount - 1)
        for (d in diagnostics) {
            val start = snapshot.offsetOf(d.range.start)
            val end = snapshot.offsetOf(d.range.end)
            val length = (end - start).coerceAtLeast(1).coerceAtMost((text.length - start).coerceAtLeast(0))
            val prefix = listOfNotNull(d.source, d.code).joinToString(" ").let { if (it.isEmpty()) "" else " ($it)" }
            val notice = DefaultParserNotice(this, d.message + prefix, d.range.start.line, start, length)
            notice.level = when (d.severity) {
                DiagnosticSeverity.ERROR -> ParserNotice.Level.ERROR
                DiagnosticSeverity.WARNING -> ParserNotice.Level.WARNING
                DiagnosticSeverity.INFORMATION, DiagnosticSeverity.HINT -> ParserNotice.Level.INFO
            }
            colors?.let { notice.color = it.diagnosticColor(d.severity).toAwt() }
            result.addNotice(notice)
        }
        return result
    }
}

/** A completion backed by a [CompletionItem]; its summary is the (HTML-escaped) documentation. */
internal class ServiceCompletion(provider: CompletionProviderBase, val item: CompletionItem, relevance: Int) :
    BasicCompletion(provider, item.label, item.detail, item.documentation?.let(::markupToHtml)) {
    init {
        setRelevance(relevance)
    }

    override fun getInputText(): String = item.filterText ?: item.label
}

internal fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Renders markup as escaped HTML: code blocks become `<pre>`, everything else is escaped text. */
internal fun markupToHtml(content: MarkupContent): String {
    if (content.kind == MarkupKind.PLAIN_TEXT) return "<pre>${escapeHtml(content.value)}</pre>"
    return parseMarkdownBlocks(content.value).joinToString("") { block ->
        when (block) {
            is MarkdownBlock.Code -> "<pre>${escapeHtml(block.text)}</pre>"
            is MarkdownBlock.Paragraph -> "<p>${escapeHtml(block.text).replace("\n", "<br>")}</p>"
        }
    }
}

/**
 * Feeds the latest [CompletionSession] to fifesoft AutoCompletion. Filtering and ranking already
 * happened in the controller; this provider only re-filters locally as the user types while the
 * popup is open.
 */
internal class SessionCompletionProvider(private val rules: () -> TokenRules) : CompletionProviderBase() {
    var session: CompletionSession? = null
        set(value) {
            field = value
            val items = value?.items.orEmpty()
            completions = items.mapIndexed { index, ranked -> ServiceCompletion(this, ranked.item, items.size - index) }
        }

    var completions: List<ServiceCompletion> = emptyList()
        private set

    override fun getCompletionsImpl(comp: JTextComponent): List<Completion> {
        val prefix = getAlreadyEnteredText(comp)
        val ranked = filterAndRankCompletions(completions.map { it.item }, prefix)
        val byItem = completions.associateBy { it.item }
        return ranked.mapNotNull { byItem[it.item] }
    }

    override fun getAlreadyEnteredText(comp: JTextComponent): String {
        val caret = comp.caretPosition
        val text = comp.text
        val start = completionPrefixStart(text, caret, rules())
        return text.substring(start, caret)
    }

    override fun getCompletionsAt(comp: JTextComponent, p: Point): List<Completion> = emptyList()

    override fun getParameterizedCompletions(tc: JTextComponent): List<ParameterizedCompletion> = emptyList()
}

/** Accepting applies the item's text edit through [onAccept] instead of AutoCompletion's replacement. */
internal class ServiceAutoCompletion(provider: SessionCompletionProvider, private val onAccept: (CompletionItem) -> Unit) :
    AutoCompletion(provider) {
    override fun insertCompletion(c: Completion, typedParamListStartChar: Boolean) {
        val item = (c as? ServiceCompletion)?.item ?: return
        hideChildWindows()
        onAccept(item)
    }

    override fun isHideOnNoText(): Boolean = false

    /** Accepts [c] as if chosen in the popup; the popup itself can't be shown headless. */
    internal fun acceptForTest(c: Completion) = insertCompletion(c, false)
}

/** A small filled circle for gutter diagnostic markers. */
internal class DotIcon(private val color: Color, private val diameter: Int) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(x + 2, y + 2, diameter - 4, diameter - 4)
        g2.dispose()
    }

    override fun getIconWidth(): Int = diameter

    override fun getIconHeight(): Int = diameter
}

/**
 * The Swing side of the RSyntaxTextArea engine. Keeps the RSTA document and [state] in sync in both
 * directions; [state] is the source of truth for text, selection, and undo history. Must be used
 * on the EDT (Compose Desktop's UI thread).
 */
internal class RstaEditorPanel(
    private val state: CodeEditorState,
    initialConfig: EditorConfig,
    private val callbacks: Callbacks,
) : JPanel(BorderLayout()) {
    /** Calls back into Compose / the controller. */
    interface Callbacks {
        fun requestCompletion()
        fun closeCompletion()
        fun acceptCompletion(item: CompletionItem)
        fun requestHover(offset: Int)
        fun cancelHover()
        fun openFind()
        fun closeFind()
    }

    var config: EditorConfig = initialConfig
        private set

    private val diagnosticsParser = DiagnosticsParser()
    private var tokenMakerRules: TokenRules? = null
    private var applyingFromState = false
    private var applyingToState = false
    private var lineHighlightTags = emptyList<Any>()
    private var appliedHighlights: List<LineHighlight>? = null
    private var appliedDiagnostics: List<Diagnostic>? = null
    private var appliedColors: CodeEditorColors? = null
    private var lastMouse: Point? = null

    internal val lineHighlightCount: Int get() = lineHighlightTags.size

    internal val diagnosticsParserForTest: DiagnosticsParser get() = diagnosticsParser

    /** The text shown in the hover tooltip for [hoverOffset], set when a hover result arrives. */
    var hoverHtml: String? = null
    var hoverOffset: Int = -1

    val completionProvider = SessionCompletionProvider { config.language }
    internal val autoCompletion = ServiceAutoCompletion(completionProvider) { callbacks.acceptCompletion(it) }

    internal val textArea: RSyntaxTextArea = RSyntaxTextArea().apply {
        isCodeFoldingEnabled = true
        antiAliasingEnabled = true
        isBracketMatchingEnabled = true
        paintMatchedBracketPair = true
        markOccurrences = false
        highlightSecondaryLanguages = false
        toolTipSupplier = org.fife.ui.rtextarea.ToolTipSupplier { _, e -> tooltipFor(e) }
    }

    internal val scrollPane = RTextScrollPane(textArea, true).apply {
        isIconRowHeaderEnabled = true
        isFoldIndicatorEnabled = true
    }

    init {
        add(scrollPane, BorderLayout.CENTER)
        textArea.addParser(diagnosticsParser)
        textArea.addCaretListener {
            if (applyingFromState || applyingToState) return@addCaretListener
            val caret = textArea.caret
            state.selectionInternal = Selection(caret.mark, caret.dot)
        }
        textArea.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                lastMouse = e.point
            }
        })
        textArea.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                lastMouse = null
                callbacks.cancelHover()
            }
        })
        scrollPane.gutter.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val onLineClick = config.onLineClick ?: return
                val offset = textArea.viewToModel2D(Point(0, e.y))
                if (offset >= 0) onLineClick(textArea.getLineOfOffset(offset))
            }
        })
        ToolTipManager.sharedInstance().registerComponent(textArea)
        scrollPane.viewport.addChangeListener { updateVisibleLines() }
        installCompletion()
        installKeyBindings()
        applyConfig(initialConfig, force = true)
        textArea.discardAllEdits()
        syncSelectionFromState()
    }

    private fun installCompletion() {
        autoCompletion.isAutoActivationEnabled = false
        autoCompletion.setAutoCompleteSingleChoices(false)
        autoCompletion.descWindowVisibility = org.fife.ui.autocomplete.DescWindowVisibility.ALWAYS
        autoCompletion.setParameterAssistanceEnabled(false)
        // Ctrl+Space is routed through the controller so the service is asked, not the stale list.
        autoCompletion.triggerKey = KeyStroke.getKeyStroke(KeyEvent.VK_F24, 0)
        autoCompletion.install(textArea)
        autoCompletion.addAutoCompletionListener { event ->
            if (event.eventType == AutoCompletionEvent.Type.POPUP_HIDDEN && !refreshingPopup) callbacks.closeCompletion()
        }
    }

    private var refreshingPopup = false

    private fun installKeyBindings() {
        val shortcut = if (isApplePlatform) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        fun bind(stroke: KeyStroke, name: String, action: () -> Boolean) {
            val previous = textArea.inputMap.get(stroke)?.let { textArea.actionMap.get(it) }
            textArea.inputMap.put(stroke, name)
            textArea.actionMap.put(
                name,
                object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        if (!action()) previous?.actionPerformed(e)
                    }
                },
            )
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "kmp.complete") {
            if (!config.readOnly) callbacks.requestCompletion()
            true
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), "kmp.undo") {
            if (!config.readOnly) state.undo()
            true
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut or InputEvent.SHIFT_DOWN_MASK), "kmp.redo") {
            if (!config.readOnly) state.redo()
            true
        }
        if (!isApplePlatform) {
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "kmp.redo2") {
                if (!config.readOnly) state.redo()
                true
            }
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcut), "kmp.find") {
            callbacks.openFind()
            true
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "kmp.escape") {
            callbacks.closeFind()
            false
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "kmp.enter") { handleEnter() }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "kmp.up") { handleHistory(-1) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "kmp.down") { handleHistory(1) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "kmp.tab") { handleTab(forward = true) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "kmp.shiftTab") { handleTab(forward = false) }
    }

    /** Enter submits in single-line mode when no completion popup is open. */
    internal fun handleEnter(): Boolean {
        if (!config.singleLine || autoCompletion.isPopupVisible) return false
        callbacks.closeCompletion()
        config.onSubmit?.invoke(state.text)
        return true
    }

    /** Up/Down browse history in single-line mode when no completion popup is open. */
    internal fun handleHistory(delta: Int): Boolean {
        if (!config.singleLine || autoCompletion.isPopupVisible) return false
        val history = config.onHistory ?: return false
        history(delta)
        return true
    }

    private fun handleTab(forward: Boolean): Boolean {
        if (config.tabBehavior != TabBehavior.MOVE_FOCUS && !config.readOnly) return false
        if (forward) textArea.transferFocus() else textArea.transferFocusBackward()
        return true
    }

    /** Pushes the state's text (minimal diff) and selection into RSTA. */
    fun syncFromState() {
        if (applyingToState) return
        val current = textArea.text
        val target = state.text
        if (current != target) {
            val diff = diffText(current, target)
            applyingFromState = true
            try {
                val doc = textArea.document as AbstractDocument
                doc.replace(diff.start, diff.oldEnd - diff.start, diff.newText, null)
                textArea.discardAllEdits()
            } finally {
                applyingFromState = false
            }
        }
        syncSelectionFromState()
    }

    private fun syncSelectionFromState() {
        val selection = state.selectionInternal.coerceIn(textArea.document.length)
        val caret = textArea.caret
        if (caret.mark == selection.anchor && caret.dot == selection.caret) return
        applyingFromState = true
        try {
            caret.dot = selection.anchor
            caret.moveDot(selection.caret)
        } finally {
            applyingFromState = false
        }
    }

    /** Applies configuration that changed since the last call. */
    fun applyConfig(config: EditorConfig, force: Boolean = false) {
        val previous = this.config
        this.config = config
        if (force || tokenMakerRules !== config.language) installLanguage(config.language)
        textArea.isEditable = !config.readOnly
        textArea.isAutoIndentEnabled = config.options.autoIndent
        textArea.closeCurlyBraces = config.options.autoCloseBrackets
        textArea.insertPairedCharacters = config.options.autoCloseBrackets
        textArea.isBracketMatchingEnabled = config.options.matchBrackets
        textArea.highlightCurrentLine = config.options.highlightCurrentLine && !config.singleLine
        textArea.tabSize = config.options.tabSize
        textArea.tabsEmulated = config.language.indentUnit.all { it == ' ' }
        textArea.isCodeFoldingEnabled = config.folding
        scrollPane.lineNumbersEnabled = config.showLineNumbers
        scrollPane.isFoldIndicatorEnabled = config.folding
        if (config.singleLine) {
            textArea.rows = 1
            scrollPane.verticalScrollBarPolicy = RTextScrollPane.VERTICAL_SCROLLBAR_NEVER
            scrollPane.horizontalScrollBarPolicy = RTextScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            scrollPane.lineNumbersEnabled = false
            scrollPane.isIconRowHeaderEnabled = false
            scrollPane.border = null
        } else {
            scrollPane.isIconRowHeaderEnabled = true
            scrollPane.verticalScrollBarPolicy = RTextScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            scrollPane.horizontalScrollBarPolicy = RTextScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        if (force || previous.textStyle != config.textStyle) {
            val size = config.textStyle.fontSize.takeIf { it.isSp }?.value ?: 13f
            textArea.font = Font(Font.MONOSPACED, Font.PLAIN, size.toInt().coerceAtLeast(6))
        }
        if (force || appliedColors != config.colors) applyColors(config.colors)
        if (force || appliedDiagnostics != config.diagnostics) setDiagnostics(config.diagnostics)
        if (force || appliedHighlights != config.lineHighlights) setLineHighlights(config.lineHighlights)
    }

    private inner class StateDocumentFilter : DocumentFilter() {
        override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
            replace(fb, offset, 0, string, attr)
        }

        override fun remove(fb: FilterBypass, offset: Int, length: Int) {
            replace(fb, offset, length, "", null)
        }

        override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
            if (applyingFromState) {
                fb.replace(offset, length, text, attrs)
                return
            }
            val inserted = text.orEmpty().let {
                if (config.singleLine) it.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ') else it
            }
            val kind = when {
                length == 0 && inserted.length == 1 && inserted != "\n" -> EditKind.TYPING
                inserted.isEmpty() && length == 1 -> EditKind.DELETION
                else -> EditKind.OTHER
            }
            applyingToState = true
            try {
                fb.replace(offset, length, inserted, attrs)
                state.apply(
                    EditOutcome(listOf(Replacement(offset, offset + length, inserted)), Selection(offset + inserted.length), kind),
                    typed = inserted.singleOrNull()?.takeIf { length == 0 },
                )
            } finally {
                applyingToState = false
            }
        }
    }

    private fun installLanguage(rules: TokenRules) {
        tokenMakerRules = rules
        val key = styleKeyFor(rules)
        val maker = SharedLexerTokenMaker(rules)
        val factory = object : TokenMakerFactory() {
            override fun getTokenMakerImpl(key: String?): TokenMaker = maker
            override fun keySet(): Set<String> = setOf(key)
        }
        FoldParserManager.get().addFoldParserMapping(key, SharedFoldParser { rules })
        val text = state.text
        val document = RSyntaxDocument(factory, key)
        applyingFromState = true
        try {
            document.insertString(0, text, null)
            textArea.document = document
            textArea.syntaxEditingStyle = key
        } finally {
            applyingFromState = false
        }
        document.documentFilter = StateDocumentFilter()
        textArea.discardAllEdits()
    }

    private fun applyColors(colors: CodeEditorColors) {
        appliedColors = colors
        diagnosticsParser.colors = colors
        textArea.background = colors.background.toAwt()
        textArea.foreground = colors.foreground.toAwt()
        textArea.caretColor = colors.caret.toAwt()
        textArea.selectionColor = colors.selectionBackground.toAwt()
        textArea.selectedTextColor = colors.foreground.toAwt()
        textArea.useSelectedTextColor = false
        textArea.currentLineHighlightColor = colors.currentLineBackground.toAwt()
        textArea.matchedBracketBGColor = colors.matchingBracketBackground.toAwt()
        textArea.matchedBracketBorderColor = colors.matchingBracketBackground.toAwt()
        textArea.markAllHighlightColor = colors.findMatchBackground.toAwt()
        val scheme = SyntaxScheme(true)
        TokenKind.entries.forEach { kind ->
            scheme.setStyle(kind.toRstaType(), Style(colors.tokenColor(kind).toAwt()))
        }
        scheme.setStyle(TokenTypes.COMMENT_MULTILINE, Style(colors.tokenColor(TokenKind.COMMENT).toAwt()))
        scheme.setStyle(TokenTypes.WHITESPACE, Style(colors.foreground.toAwt()))
        textArea.syntaxScheme = scheme
        val gutter = scrollPane.gutter
        gutter.background = colors.gutterBackground.toAwt()
        gutter.lineNumberColor = colors.gutterForeground.toAwt()
        gutter.currentLineNumberColor = colors.gutterActiveForeground.toAwt()
        gutter.foldIndicatorForeground = colors.gutterForeground.toAwt()
        gutter.foldBackground = colors.gutterBackground.toAwt()
        gutter.borderColor = colors.gutterBackground.toAwt()
        scrollPane.background = colors.background.toAwt()
        scrollPane.viewport.background = colors.background.toAwt()
        textArea.repaint()
    }

    /** Shows [diagnostics] as notices and gutter icons. */
    fun setDiagnostics(diagnostics: List<Diagnostic>) {
        appliedDiagnostics = diagnostics
        diagnosticsParser.diagnostics = diagnostics
        textArea.forceReparsing(diagnosticsParser)
        val gutter = scrollPane.gutter
        gutter.removeAllTrackingIcons()
        val colors = config.colors
        val size = (textArea.lineHeight).coerceAtLeast(8)
        diagnostics.groupBy { it.range.start.line }.forEach { (line, list) ->
            val worst = list.minBy { it.severity.ordinal }
            if (line < textArea.lineCount) {
                runCatching {
                    gutter.addLineTrackingIcon(line, DotIcon(colors.diagnosticColor(worst.severity).toAwt(), size), escapeHtml(list.joinToString("\n") { it.message }))
                }
            }
        }
    }

    /** Applies whole-line highlights. */
    fun setLineHighlights(highlights: List<LineHighlight>) {
        appliedHighlights = highlights
        lineHighlightTags.forEach { textArea.removeLineHighlight(it) }
        val tags = ArrayList<Any>()
        for (highlight in highlights) {
            val color = config.colors.lineHighlightColor(highlight.role).toAwt()
            for (line in highlight.lines) {
                if (line in 0 until textArea.lineCount) runCatching { tags += textArea.addLineHighlight(line, color) }
            }
        }
        lineHighlightTags = tags
    }

    /** Shows, refreshes, or hides the completion popup for [session]. */
    fun showCompletion(session: CompletionSession?) {
        completionProvider.session = session
        if (GraphicsEnvironment.isHeadless()) return
        if (session == null || (session.items.isEmpty() && !session.explicit)) {
            refreshingPopup = true
            try {
                autoCompletion.hideChildWindows()
            } finally {
                refreshingPopup = false
            }
            return
        }
        if (!textArea.isShowing) return
        refreshingPopup = true
        try {
            autoCompletion.doCompletion()
        } finally {
            refreshingPopup = false
        }
    }

    /** Scrolls [range] into view, centering it vertically. */
    fun reveal(range: TextRange) {
        val snapshot = state.snapshot()
        val offset = snapshot.offsetOf(range.start).coerceIn(0, textArea.document.length)
        val rect = runCatching { textArea.modelToView2D(offset)?.bounds }.getOrNull() ?: return
        val viewport = scrollPane.viewport
        val extent = viewport.extentSize
        val y = (rect.y - extent.height / 2 + rect.height / 2).coerceIn(0, (textArea.height - extent.height).coerceAtLeast(0))
        viewport.viewPosition = Point(viewport.viewPosition.x, y)
    }

    private fun tooltipFor(e: MouseEvent): String? {
        if (!config.options.hover) return null
        val offset = textArea.viewToModel2D(e.point)
        if (offset < 0) return null
        if (offset == hoverOffset && hoverHtml != null) return hoverHtml
        callbacks.requestHover(offset)
        return null
    }

    /** Called when a hover result for [offset] arrives; re-shows the tooltip if the mouse is still there. */
    fun showHover(offset: Int, hover: Hover?) {
        hoverOffset = offset
        hoverHtml = hover?.let { "<html>${markupToHtml(it.contents)}</html>" }
        val point = lastMouse ?: return
        if (hoverHtml == null || GraphicsEnvironment.isHeadless()) return
        ToolTipManager.sharedInstance().mouseMoved(MouseEvent(textArea, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false))
    }

    private fun updateVisibleLines() {
        val rect = scrollPane.viewport.viewRect
        val first = textArea.viewToModel2D(Point(rect.x, rect.y))
        val last = textArea.viewToModel2D(Point(rect.x, rect.y + rect.height - 1))
        if (first < 0 || last < 0) return
        state.visibleLines = runCatching { textArea.getLineOfOffset(first)..textArea.getLineOfOffset(last) }.getOrElse { 0..0 }
    }

    fun focusEditor() {
        textArea.requestFocusInWindow()
    }

    fun dispose() {
        autoCompletion.uninstall()
        ToolTipManager.sharedInstance().unregisterComponent(textArea)
    }
}
