package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LexerTest {
    private fun TokenRules.kinds(line: String, state: LexerState = LexerState.Initial): List<Pair<String, TokenKind>> =
        tokenizeLine(line, state).tokens.map { line.substring(it.start, it.end) to it.kind }

    @Test
    fun `plain text has no tokens except brackets`() {
        assertEquals(listOf("(" to TokenKind.PUNCTUATION, ")" to TokenKind.PUNCTUATION), PlainText.kinds("call (it) now"))
    }

    @Test
    fun `json distinguishes keys strings numbers and constants`() {
        val kinds = JsonRules.kinds("""{"name": "x\"y", "n": -1.5e3, "ok": true, "v": null}""")
        assertTrue("\"name\"" to TokenKind.PROPERTY in kinds)
        assertTrue("\"x" to TokenKind.STRING in kinds)
        assertTrue("\\\"" to TokenKind.ESCAPE in kinds)
        assertTrue("1.5e3" to TokenKind.NUMBER in kinds)
        assertTrue("true" to TokenKind.CONSTANT in kinds)
        assertTrue("null" to TokenKind.CONSTANT in kinds)
        assertTrue("-" to TokenKind.OPERATOR in kinds)
    }

    @Test
    fun `json unterminated string ends at line end without carrying state`() {
        val line = JsonRules.tokenizeLine("\"open")
        assertEquals(LexerState.Initial, line.endState)
        assertEquals(TokenKind.STRING, line.tokens.single().kind)
    }

    @Test
    fun `kotlin keywords types functions annotations and numbers`() {
        val kinds = KotlinRules.kinds("@Composable fun Foo(x: Int = 0x1F_FF, y: Long = 10L) = bar(x)")
        assertTrue("@Composable" to TokenKind.ANNOTATION in kinds)
        assertTrue("fun" to TokenKind.KEYWORD in kinds)
        assertTrue("Foo" to TokenKind.FUNCTION in kinds)
        assertTrue("Int" to TokenKind.TYPE in kinds)
        assertTrue("0x1F_FF" to TokenKind.NUMBER in kinds)
        assertTrue("10L" to TokenKind.NUMBER in kinds)
        assertTrue("bar" to TokenKind.FUNCTION in kinds)
    }

    @Test
    fun `kotlin string templates are highlighted as escapes`() {
        val kinds = KotlinRules.kinds("val s = \"hi \$name and \${a + b}!\"")
        assertTrue("\$name" to TokenKind.ESCAPE in kinds)
        assertTrue("\${a + b}" to TokenKind.ESCAPE in kinds)
        assertTrue("!\"" to TokenKind.STRING in kinds)
    }

    @Test
    fun `kotlin nested block comments carry state across lines`() {
        val first = KotlinRules.tokenizeLine("val a = 1 /* outer /* inner */")
        assertNotEquals(LexerState.Initial, first.endState)
        val second = KotlinRules.tokenizeLine("still comment */ val b", first.endState)
        assertEquals(TokenKind.COMMENT, second.tokens.first().kind)
        assertEquals("still comment */", "still comment */ val b".substring(second.tokens.first().start, second.tokens.first().end))
        assertEquals(LexerState.Initial, second.endState)
        assertTrue(second.tokens.any { it.kind == TokenKind.KEYWORD })
    }

    @Test
    fun `kotlin raw strings span lines and ignore escapes`() {
        val lines = KotlinRules.tokenize("val s = \"\"\"\nraw \\n text\n\"\"\" + x")
        assertNotEquals(LexerState.Initial, lines[0].endState)
        assertEquals(listOf(TokenKind.STRING), lines[1].tokens.map { it.kind })
        assertEquals(LexerState.Initial, lines[2].endState)
        assertEquals(TokenKind.STRING, lines[2].tokens.first().kind)
    }

    @Test
    fun `swift interpolation directives attributes and multiline strings`() {
        val kinds = SwiftRules.kinds("@MainActor func greet() { print(\"Hi \\(name)!\") } // done")
        assertTrue("@MainActor" to TokenKind.ANNOTATION in kinds)
        assertTrue("func" to TokenKind.KEYWORD in kinds)
        assertTrue("greet" to TokenKind.FUNCTION in kinds)
        assertTrue("\\(" to TokenKind.ESCAPE in kinds)
        assertTrue("name)" to TokenKind.ESCAPE in kinds)
        assertTrue("// done" to TokenKind.COMMENT in kinds)
        assertTrue("#if" to TokenKind.PREPROCESSOR in SwiftRules.kinds("#if DEBUG"))
        val lines = SwiftRules.tokenize("let s = \"\"\"\nbody\n\"\"\"")
        assertNotEquals(LexerState.Initial, lines[0].endState)
        assertEquals(LexerState.Initial, lines[2].endState)
        assertTrue("nil" to TokenKind.CONSTANT in SwiftRules.kinds("x = nil"))
    }

    @Test
    fun `custom dsl covers keywords attributes literals and comments`() {
        val rules = tokenRules("commands") {
            keywords("tap", "swipe")
            constants("true", "false")
            attributeAssignments()
            lineComment("#")
            string("\"")
            numbers()
            operators("=")
        }
        val kinds = rules.kinds("tap id=\"login\" count=2 strict=true x==y # note")
        assertEquals(
            listOf(
                "tap" to TokenKind.KEYWORD,
                "id" to TokenKind.ATTRIBUTE,
                "=" to TokenKind.OPERATOR,
                "\"login\"" to TokenKind.STRING,
                "count" to TokenKind.ATTRIBUTE,
                "=" to TokenKind.OPERATOR,
                "2" to TokenKind.NUMBER,
                "strict" to TokenKind.ATTRIBUTE,
                "=" to TokenKind.OPERATOR,
                "true" to TokenKind.CONSTANT,
                "=" to TokenKind.OPERATOR,
                "=" to TokenKind.OPERATOR,
                "# note" to TokenKind.COMMENT,
            ),
            kinds,
        )
    }

    @Test
    fun `rules can extend a base and add patterns`() {
        val jsonc = tokenRules("jsonc", base = JsonRules) { lineComment("//") }
        assertTrue("// c" to TokenKind.COMMENT in jsonc.kinds("{} // c"))
        assertEquals("//", jsonc.lineComment)
        assertEquals(JsonRules.indentUnit, jsonc.indentUnit)
        val withPattern = tokenRules("p") { pattern("\\$[a-z]+", TokenKind.VARIABLE) }
        assertEquals(listOf("\$abc" to TokenKind.VARIABLE), withPattern.kinds("x \$abc"))
    }

    @Test
    fun `case insensitive keywords`() {
        val sql = tokenRules("sql") {
            caseInsensitiveKeywords = true
            keywords("select", "from")
        }
        assertEquals(listOf("SELECT" to TokenKind.KEYWORD, "From" to TokenKind.KEYWORD), sql.kinds("SELECT a From b"))
    }

    @Test
    fun `kindAt finds the covering token`() {
        val line = KotlinRules.tokenizeLine("val s = \"x\"")
        assertEquals(TokenKind.KEYWORD, line.kindAt(1))
        assertEquals(TokenKind.PLAIN, line.kindAt(3))
        assertEquals(TokenKind.STRING, line.kindAt(9))
    }

    @Test
    fun `highlighter relexes only from the edited line until states converge`() {
        val text = (0 until 200).joinToString("\n") { "val v$it = $it // line" }
        val highlighter = Highlighter(KotlinRules)
        highlighter.update(DocumentSnapshot(text, 1))
        highlighter.line(199)
        assertEquals(200, highlighter.lexedLineCount)

        val edited = text.replaceFirst("val v100 = 100", "val v100 = 1000")
        highlighter.update(DocumentSnapshot(edited, 2))
        highlighter.line(199)
        assertEquals(201, highlighter.lexedLineCount, "only the edited line is lexed again")
    }

    @Test
    fun `opening a block comment relexes following lines and closing it converges`() {
        val text = (0 until 50).joinToString("\n") { "val v$it = $it" }
        val highlighter = Highlighter(KotlinRules)
        highlighter.update(DocumentSnapshot(text, 1))
        highlighter.line(49)
        val opened = text.replaceFirst("val v10 = 10", "val v10 = 10 /*")
        highlighter.update(DocumentSnapshot(opened, 2))
        assertEquals(TokenKind.COMMENT, highlighter.line(49).tokens.single().kind)
        val before = highlighter.lexedLineCount
        val closed = opened.replaceFirst("val v12 = 12", "*/ val v12 = 12")
        highlighter.update(DocumentSnapshot(closed, 3))
        assertTrue(highlighter.line(13).tokens.any { it.kind == TokenKind.KEYWORD })
        assertTrue(highlighter.line(49).tokens.any { it.kind == TokenKind.KEYWORD })
        assertEquals(before + 38, highlighter.lexedLineCount, "lines 12..49 changed state and must be lexed")
    }

    @Test
    fun `highlighter handles inserted and deleted lines`() {
        val highlighter = Highlighter(KotlinRules)
        highlighter.update(DocumentSnapshot("a\nb\nc", 1))
        highlighter.line(2)
        highlighter.update(DocumentSnapshot("a\nval x\nb\nc", 2))
        assertEquals(TokenKind.KEYWORD, highlighter.line(1).tokens.single().kind)
        assertEquals(4, highlighter.lexedLineCount, "only the inserted line is lexed")
        highlighter.update(DocumentSnapshot("c", 3))
        assertTrue(highlighter.line(0).tokens.isEmpty())
    }
    @Test
    fun `empty block comment delimiters are rejected before tokenizing`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { tokenRules("invalid") { blockComment("", "*/", nested = true) } }
        kotlin.test.assertFailsWith<IllegalArgumentException> { tokenRules("invalid") { blockComment("/*", "") } }
    }

}
