package com.arjangconsulting.codeeditor

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice
import javax.swing.text.Segment
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Headless tests of the RSyntaxTextArea engine, ported from moqserver's JsonEditorPanelTest. */
class RstaEditorPanelTest {
    private val events = mutableListOf<String>()
    private var submitted: String? = null
    private val history = mutableListOf<Int>()
    private lateinit var state: CodeEditorState
    private lateinit var panel: RstaEditorPanel

    private val callbacks = object : RstaEditorPanel.Callbacks {
        override fun requestCompletion() {
            events += "complete"
        }

        override fun closeCompletion() {
            events += "close"
        }

        override fun acceptCompletion(item: CompletionItem) {
            events += "accept:${item.label}"
        }

        override fun requestHover(offset: Int) {
            events += "hover:$offset"
        }

        override fun cancelHover() = Unit

        override fun openFind() {
            events += "find"
        }

        override fun closeFind() = Unit
    }

    private fun config(
        language: TokenRules = JsonRules,
        readOnly: Boolean = false,
        singleLine: Boolean = false,
        diagnostics: List<Diagnostic> = emptyList(),
        lineHighlights: List<LineHighlight> = emptyList(),
        colors: CodeEditorColors = CodeEditorDefaults.colorsFrom(lightColorScheme()),
    ) = EditorConfig(
        language = language,
        readOnly = readOnly,
        singleLine = singleLine,
        onSubmit = { submitted = it },
        onHistory = { history += it },
        showLineNumbers = !singleLine,
        folding = !singleLine,
        lineHighlights = lineHighlights,
        diagnostics = diagnostics,
        onCaretChange = null,
        onLineClick = null,
        tabBehavior = TabBehavior.INSERT_INDENT,
        colors = colors,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        labels = CodeEditorLabels(),
        options = CodeEditorOptions(),
        serviceOptions = LanguageServiceOptions(),
        logger = CodeEditorLogger.None,
    )

    @BeforeTest
    fun setUp() {
        System.setProperty("java.awt.headless", "true")
        state = CodeEditorState("{\"a\":1}")
        panel = RstaEditorPanel(state, config(), callbacks)
    }

    @Test
    fun `initial text comes from the state`() {
        assertEquals("{\"a\":1}", panel.textArea.text)
        assertEquals(0, state.version, "showing the state must not count as an edit")
    }

    @Test
    fun `syncing only replaces the document when text differs`() {
        val before = state.version
        panel.syncFromState()
        assertEquals(before, state.version, "identical text should not trigger a document change")

        state.setText("{\"a\":2}")
        panel.syncFromState()
        assertEquals("{\"a\":2}", panel.textArea.text)
        assertEquals(before + 1, state.version, "pushing state into RSTA must not echo back as another edit")
    }

    @Test
    fun `typing in the text area updates the state and its history`() {
        panel.textArea.caretPosition = 1
        panel.textArea.document.insertString(1, "\"b\":0,", null)
        assertEquals("{\"b\":0,\"a\":1}", state.text)
        assertTrue(state.canUndo)
        state.undo()
        panel.syncFromState()
        assertEquals("{\"a\":1}", panel.textArea.text)
    }

    @Test
    fun `selection follows the caret both ways`() {
        panel.textArea.select(1, 4)
        assertEquals(1, state.selectionStart)
        assertEquals(4, state.selectionEnd)
        state.select(2, 3)
        panel.syncFromState()
        assertEquals(2, panel.textArea.selectionStart)
        assertEquals(3, panel.textArea.selectionEnd)
    }

    @Test
    fun `read only toggles editability`() {
        panel.applyConfig(config(readOnly = true))
        assertFalse(panel.textArea.isEditable)
        panel.applyConfig(config(readOnly = false))
        assertTrue(panel.textArea.isEditable)
    }

    @Test
    fun `colors map to the text area and syntax scheme`() {
        val colors = CodeEditorDefaults.colorsFrom(lightColorScheme()).copy(background = Color.DarkGray, foreground = Color.LightGray, keyword = Color.Red)
        panel.applyConfig(config(colors = colors))
        assertEquals(Color.DarkGray.toAwt(), panel.textArea.background)
        assertEquals(Color.LightGray.toAwt(), panel.textArea.foreground)
        assertEquals(Color.Red.toAwt(), panel.textArea.syntaxScheme.getStyle(TokenTypes.RESERVED_WORD).foreground)
    }

    @Test
    fun `line highlights are added and replaced`() {
        state.setText("a\nb\nc\nd")
        panel.syncFromState()
        panel.applyConfig(config(lineHighlights = listOf(LineHighlight(0..1), LineHighlight(3, LineHighlightRole.ERROR))))
        assertEquals(3, panel.lineHighlightCount)
        panel.applyConfig(config(lineHighlights = listOf(LineHighlight(2))))
        assertEquals(1, panel.lineHighlightCount)
        panel.applyConfig(config(lineHighlights = emptyList()))
        assertEquals(0, panel.lineHighlightCount)
    }

    @Test
    fun `diagnostics become parser notices with levels`() {
        state.setText("{\n  \"a\": x\n}")
        panel.syncFromState()
        val diagnostics = listOf(
            Diagnostic(TextRange(TextPosition(1, 7), TextPosition(1, 8)), "Unexpected token", source = "json"),
            Diagnostic(TextRange.at(TextPosition(0, 0)), "Prefer arrays", DiagnosticSeverity.WARNING),
        )
        panel.applyConfig(config(diagnostics = diagnostics))
        val result = panel.diagnosticsParserForTest.parse(panel.textArea.document as RSyntaxDocument, null)
        val notices = result.notices
        assertEquals(2, notices.size)
        val error = notices.first { it.level == ParserNotice.Level.ERROR }
        assertEquals("Unexpected token (json)", error.message)
        assertEquals(1, error.line)
        assertEquals(state.text.indexOf('x'), error.offset)
        assertEquals(1, error.length)
        assertEquals(ParserNotice.Level.WARNING, notices.first { it.message == "Prefer arrays" }.level)
    }

    @Test
    fun `the token maker uses the shared lexer and carries multi line state`() {
        val maker = SharedLexerTokenMaker(SwiftRules)
        val line1 = "let s = \"\"\""
        val first = maker.getTokenList(Segment(line1.toCharArray(), 0, line1.length), TokenTypes.NULL, 0)
        val types = generateSequence(first) { it.nextToken }.toList()
        assertEquals(TokenTypes.RESERVED_WORD, types.first().type)
        val endType = maker.getLastTokenTypeOnLine(Segment(line1.toCharArray(), 0, line1.length), TokenTypes.NULL)
        assertTrue(endType < 0, "an open multi-line string is encoded as a negative state")
        val line2 = "still a string"
        val second = maker.getTokenList(Segment(line2.toCharArray(), 0, line2.length), endType, 20)
        assertEquals(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, second.type)
        assertEquals(20, second.offset)
        assertEquals("//", maker.getLineCommentStartAndEnd(0)[0])
    }

    @Test
    fun `custom languages highlight in the swing engine`() {
        val commands = tokenRules("commands") {
            keywords("tap")
            attributeAssignments()
            string("\"")
        }
        panel.applyConfig(config(language = commands))
        state.setText("tap id=\"x\"")
        panel.syncFromState()
        val tokens = generateSequence((panel.textArea.document as RSyntaxDocument).getTokenListForLine(0)) { it.nextToken }
            .filter { it.isPaintable }
            .map { it.lexeme to it.type }
            .toList()
        assertTrue("tap" to TokenTypes.RESERVED_WORD in tokens)
        assertTrue("id" to TokenTypes.MARKUP_TAG_NAME in tokens)
        assertTrue("\"x\"" to TokenTypes.LITERAL_STRING_DOUBLE_QUOTE in tokens)
    }

    @Test
    fun `folds come from the shared folding computation`() {
        state.setText("{\n  \"a\": [\n    1\n  ],\n  \"b\": 2\n}")
        panel.syncFromState()
        val folds = SharedFoldParser { JsonRules }.getFolds(panel.textArea)
        assertEquals(1, folds.size)
        assertEquals(0, folds[0].startLine)
        assertEquals(1, folds[0].childCount)
        assertEquals(1, folds[0].getChild(0).startLine)
    }

    @Test
    fun `completion sessions feed the provider and filter by the entered text`() {
        state.setText("sw")
        panel.syncFromState()
        panel.textArea.caretPosition = 2
        val items = listOf(CompletionItem("swipe", documentation = MarkupContent.markdown("Swipes <fast>")), CompletionItem("scroll"), CompletionItem("switch"))
        val session = CompletionSession(0, TextPosition(0, 2), state.version, items, filterAndRankCompletions(items, "sw"), 0, false, false, "sw")
        panel.showCompletion(session)
        assertEquals(listOf("swipe", "switch"), panel.completionProvider.completions.map { it.item.label })
        assertEquals("sw", panel.completionProvider.getAlreadyEnteredText(panel.textArea))
        val visible = panel.completionProvider.getCompletions(panel.textArea).map { it.inputText }
        assertEquals(listOf("swipe", "switch"), visible)
        assertTrue(panel.completionProvider.completions.first().summary.contains("&lt;fast&gt;"), "documentation is HTML-escaped")
        panel.showCompletion(null)
        assertTrue(panel.completionProvider.completions.isEmpty())
    }

    @Test
    fun `accepting a completion is routed to the controller callback`() {
        val items = listOf(CompletionItem("swipe"))
        panel.showCompletion(CompletionSession(0, TextPosition.Zero, 0, items, filterAndRankCompletions(items, ""), 0, false, true, ""))
        panel.autoCompletion.acceptForTest(panel.completionProvider.completions.single())
        assertEquals("accept:swipe", events.last())
    }

    @Test
    fun `single line enter submits and arrows browse history when no popup is open`() {
        val single = RstaEditorPanel(CodeEditorState("tap id=a"), config(singleLine = true), callbacks)
        assertTrue(single.handleEnter())
        assertEquals("tap id=a", submitted)
        assertTrue(single.handleHistory(-1))
        assertTrue(single.handleHistory(1))
        assertEquals(listOf(-1, 1), history)
        assertEquals(1, single.textArea.rows)
    }

    @Test
    fun `single line mode flattens pasted newlines`() {
        val singleState = CodeEditorState("")
        val single = RstaEditorPanel(singleState, config(singleLine = true), callbacks)
        single.textArea.replaceSelection("a\nb")
        assertEquals("a b", singleState.text)
    }

    @Test
    fun `multi line enter is left to the text area`() {
        assertFalse(panel.handleEnter())
        assertFalse(panel.handleHistory(-1))
        assertEquals(null, submitted)
    }

    @Test
    fun `hover results are rendered as escaped html`() {
        panel.showHover(3, Hover(MarkupContent.plain("a < b")))
        assertNotNull(panel.hoverHtml)
        assertTrue(panel.hoverHtml!!.contains("a &lt; b"))
        assertEquals(3, panel.hoverOffset)
    }
    @Test
    fun `replacing a selection is one undo step and one notification`() {
        val original = state.text
        panel.textArea.select(1, 4)
        val seen = mutableListOf<String>()
        state.addTextListener { seen += it }
        panel.textArea.replaceSelection("replacement")
        assertEquals(listOf("{replacement:1}"), seen)
        assertTrue(state.undo())
        assertEquals(original, state.text)
        assertEquals(Selection(1, 4), state.selectionInternal)
        assertFalse(state.canUndo)
        assertTrue(state.redo())
        assertEquals("{replacement:1}", state.text)
    }

    @Test
    fun `switching from single to multi line restores newlines and scrolling`() {
        panel.applyConfig(config(singleLine = true))
        panel.applyConfig(config(singleLine = false))
        panel.textArea.selectAll()
        panel.textArea.replaceSelection("a\nb")
        assertEquals("a\nb", state.text)
        assertEquals(javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, panel.scrollPane.verticalScrollBarPolicy)
        assertEquals(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, panel.scrollPane.horizontalScrollBarPolicy)
    }

}
