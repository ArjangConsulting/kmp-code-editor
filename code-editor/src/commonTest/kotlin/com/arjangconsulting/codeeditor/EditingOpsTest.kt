package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditingOpsTest {
    /** Applies an outcome to [text] and renders the selection as `|` (caret) or `[...]`. */
    private fun render(text: String, outcome: EditOutcome?): String {
        if (outcome == null) return "<none>"
        val result = applyReplacements(text, outcome.replacements)
        val s = outcome.selection
        return if (s.collapsed) {
            result.substring(0, s.caret) + "|" + result.substring(s.caret)
        } else {
            result.substring(0, s.start) + "[" + result.substring(s.start, s.end) + "]" + result.substring(s.end)
        }
    }

    private fun applyReplacements(text: String, replacements: List<Replacement>): String {
        val builder = StringBuilder(text)
        replacements.sortedByDescending { it.start }.forEach { builder.setRange(it.start, it.end, it.text) }
        return builder.toString()
    }

    /** Parses `|` (caret) or `[` `]` (selection) markers out of [marked]. */
    private fun parse(marked: String): Pair<String, Selection> {
        val caret = marked.indexOf('|')
        if (caret >= 0) return marked.removeRange(caret, caret + 1) to Selection(caret)
        val start = marked.indexOf('[')
        val end = marked.indexOf(']') - 1
        return marked.replace("[", "").replace("]", "") to Selection(start, end)
    }

    private fun newline(marked: String, rules: TokenRules = KotlinRules): String {
        val (text, selection) = parse(marked)
        val snapshot = DocumentSnapshot(text, 1)
        return render(text, newlineOutcome(snapshot, selection, rules, TokenLookup.of(snapshot, rules), autoIndent = true))
    }

    private fun type(marked: String, c: Char, rules: TokenRules = KotlinRules): String {
        val (text, selection) = parse(marked)
        val snapshot = DocumentSnapshot(text, 1)
        return render(text, typeCharacterOutcome(snapshot, selection, c, rules, TokenLookup.of(snapshot, rules), autoClose = true))
    }

    private fun backspace(marked: String, rules: TokenRules = KotlinRules): String {
        val (text, selection) = parse(marked)
        return render(text, backspaceOutcome(DocumentSnapshot(text, 1), selection, rules))
    }

    @Test
    fun `finds matching brackets and ignores brackets in strings and comments`() {
        val text = "f(a, \"(\", g(b)) // )"
        val tokens = TokenLookup.of(DocumentSnapshot(text, 1), KotlinRules)
        assertEquals(BracketPair(1, 14), findMatchingBracket(text, 2, KotlinRules, tokens))
        assertEquals(BracketPair(1, 14), findMatchingBracket(text, 15, KotlinRules, tokens))
        assertEquals(BracketPair(11, 13), findMatchingBracket(text, 12, KotlinRules, tokens))
        assertNull(findMatchingBracket(text, 6, KotlinRules, tokens))
        assertNull(findMatchingBracket("(()", 0, KotlinRules, TokenLookup.AllCode))
    }

    @Test
    fun `matches brackets across lines`() {
        val text = "fun a() {\n    if (x) {\n    }\n}"
        val tokens = TokenLookup.of(DocumentSnapshot(text, 1), KotlinRules)
        assertEquals(BracketPair(8, text.length - 1), findMatchingBracket(text, 9, KotlinRules, tokens))
    }

    @Test
    fun `enter keeps indentation`() {
        assertEquals("    val a = 1\n    |", newline("    val a = 1|"))
    }

    @Test
    fun `enter after an opening bracket indents one level`() {
        assertEquals("fun a() {\n    |", newline("fun a() {|"))
    }

    @Test
    fun `enter between brackets splits into three lines`() {
        assertEquals("fun a() {\n    |\n}", newline("fun a() {|}"))
        assertEquals("  [\n    |\n  ]", newline("  [|]", JsonRules))
    }

    @Test
    fun `enter after a bracket inside a string does not indent`() {
        assertEquals("val s = \"{\n|", newline("val s = \"{|"))
    }

    @Test
    fun `enter replaces the selection and trims trailing blanks`() {
        assertEquals("a\n|c", newline("a[b]c"))
        assertEquals("  foo\n  |", newline("  foo   |"))
    }

    @Test
    fun `typing an opening bracket auto closes it`() {
        assertEquals("f(|)", type("f|", '('))
        assertEquals("f(|) + x", type("f| + x", '('))
    }

    @Test
    fun `JSON smart typing pairs a brace then indents Enter between the pair`() {
        val paired = type("|", '{', JsonRules)
        assertEquals("{|}", paired)
        assertEquals("{\n  |\n}", newline(paired, JsonRules))
        assertEquals("{\n  \"items\": {\n    |\n  }", newline("{\n  \"items\": {|}", JsonRules))
    }

    @Test
    fun `typing an opening bracket before a word does not auto close`() {
        assertEquals("f(|x", type("f|x", '('))
    }

    @Test
    fun `typing the closing character overtypes it`() {
        assertEquals("f()|", type("f(|)", ')'))
        assertEquals("\"ab\"|", type("\"ab|\"", '"'))
    }

    @Test
    fun `quotes do not auto close after an identifier or inside strings`() {
        assertEquals("it\"|", type("it|", '"'))
        assertEquals("x = \"|\"", type("x = |", '"'))
        assertEquals("// don'|", type("// don|", '\''))
    }

    @Test
    fun `typing a bracket with a selection wraps it`() {
        assertEquals("([abc])", type("[abc]", '('))
    }

    @Test
    fun `closing bracket on a blank line outdents to the opener`() {
        assertEquals("fun a() {\n    x\n}|", type("fun a() {\n    x\n    |", '}'))
    }

    @Test
    fun `backspace deletes an empty pair`() {
        assertEquals("f|", backspace("f(|)"))
    }

    @Test
    fun `backspace in indentation deletes to the previous indent stop`() {
        assertEquals("    |", backspace("      |"))
        assertEquals("    |x", backspace("        |x"))
    }

    @Test
    fun `backspace deletes whole surrogate pairs and crlf`() {
        assertEquals("a|", backspace("a😀|"))
        assertEquals("a|b", backspace("a\r\n|b"))
        assertEquals("<none>", backspace("|abc"))
    }

    @Test
    fun `forward delete removes the next code point`() {
        val text = "😀b"
        assertEquals("|b", render(text, deleteForwardOutcome(text, Selection(0))))
        assertNull(deleteForwardOutcome(text, Selection(3)))
    }

    @Test
    fun `tab inserts spaces to the next stop`() {
        val (text, selection) = parse("ab|")
        assertEquals("ab  |", render(text, indentOutcome(DocumentSnapshot(text, 1), selection, KotlinRules)))
    }

    @Test
    fun `tab and shift tab indent and outdent selected lines`() {
        val (text, selection) = parse("[a\n\nb]")
        val snapshot = DocumentSnapshot(text, 1)
        assertEquals("[    a\n\n    b]", render(text, indentOutcome(snapshot, selection, KotlinRules)))
        val (indented, sel2) = parse("[    a\n  b\nc]")
        assertEquals("[a\nb\nc]", render(indented, outdentOutcome(DocumentSnapshot(indented, 1), sel2, KotlinRules)))
        assertNull(outdentOutcome(DocumentSnapshot("a", 1), Selection(0), KotlinRules))
    }

    @Test
    fun `toggle line comment adds at the shared indent and removes`() {
        val (text, selection) = parse("[  a\n    b]")
        val commented = render(text, toggleLineCommentOutcome(DocumentSnapshot(text, 1), selection, KotlinRules))
        assertEquals("[  // a\n  //   b]", commented)
        val (again, sel2) = parse(commented)
        assertEquals("[  a\n    b]", render(again, toggleLineCommentOutcome(DocumentSnapshot(again, 1), sel2, KotlinRules)))
        assertNull(toggleLineCommentOutcome(DocumentSnapshot("{}", 1), Selection(0), JsonRules))
    }

    @Test
    fun `word boundaries skip whitespace and runs of one class`() {
        val text = "foo.barBaz  (x)\nnext"
        assertEquals(3, nextWordBoundary(text, 0, KotlinRules))
        assertEquals(4, nextWordBoundary(text, 3, KotlinRules))
        assertEquals(10, nextWordBoundary(text, 4, KotlinRules))
        assertEquals(13, nextWordBoundary(text, 10, KotlinRules))
        assertEquals(16, nextWordBoundary(text, 15, KotlinRules))
        assertEquals(12, previousWordBoundary(text, 13, KotlinRules))
        assertEquals(4, previousWordBoundary(text, 10, KotlinRules))
        assertEquals(15, previousWordBoundary(text, 16, KotlinRules))
        assertEquals(4..9, wordRangeAt(text, 6, KotlinRules))
        assertEquals(4, completionPrefixStart(text, 7, KotlinRules))
    }

    @Test
    fun `smart home toggles between indent and column zero`() {
        val snapshot = DocumentSnapshot("x\n    abc", 1)
        assertEquals(6, smartHome(snapshot, 9))
        assertEquals(2, smartHome(snapshot, 6))
    }

    @Test
    fun `find returns every match with optional case sensitivity`() {
        assertEquals(listOf(0..1, 6..7), findMatches("ab cd AB", "ab", caseSensitive = false))
        assertEquals(listOf(0..1), findMatches("ab cd AB", "ab", caseSensitive = true))
        assertEquals(listOf(0..1, 2..3), findMatches("aaaa", "aa", caseSensitive = true))
        assertEquals(emptyList(), findMatches("abc", "", caseSensitive = true))
    }

    @Test
    fun `snippets expand placeholders and select the first stop`() {
        assertEquals(ParsedSnippet("tap(id=)", 7, 7), parseSnippet("tap(id=$1)"))
        assertEquals(ParsedSnippet("swipe(dir=up)", 10, 12), parseSnippet("swipe(dir=\${1:up})$0"))
        assertEquals(ParsedSnippet("a b", 3, 3), parseSnippet("a $0b".replace("$0b", "b$0")))
        assertEquals(ParsedSnippet("cost \$5", 7, 7), parseSnippet("cost \\$5"))
        assertEquals(ParsedSnippet("f(outer inner)", 2, 13), parseSnippet("f(\${1:outer \${2:inner}})"))
    }

    @Test
    fun `accepting a completion replaces the prefix or applies the text edit`() {
        val snapshot = DocumentSnapshot("tap sc", 1)
        val session = CompletionSession(4, TextPosition(0, 6), 1, emptyList(), emptyList(), 0, false, false, "sc")
        val plain = completionAcceptOutcome(snapshot, CompletionItem("scroll"), session, 6)
        assertEquals("tap scroll|", render(snapshot.text, plain))

        val snippet = completionAcceptOutcome(
            snapshot,
            CompletionItem("scroll", insertText = "scroll(to=\${1:top})", insertTextFormat = InsertTextFormat.SNIPPET),
            session,
            6,
        )
        assertEquals("tap scroll(to=[top])", render(snapshot.text, snippet))

        val withEdit = CompletionItem(
            "scrollTo",
            textEdit = TextEdit(TextRange(TextPosition(0, 0), TextPosition(0, 6)), "scrollTo"),
            additionalTextEdits = listOf(TextEdit.insert(TextPosition(0, 0), "import x\n")),
        )
        assertEquals("import x\nscrollTo|", render(snapshot.text, completionAcceptOutcome(snapshot, withEdit, session, 6)))
    }

    @Test
    fun `a text edit ending at the request position extends to the caret`() {
        val snapshot = DocumentSnapshot("tap scr", 2)
        val session = CompletionSession(4, TextPosition(0, 6), 1, emptyList(), emptyList(), 0, false, false, "sc")
        val item = CompletionItem("scroll", textEdit = TextEdit(TextRange(TextPosition(0, 4), TextPosition(0, 6)), "scroll"))
        assertEquals("tap scroll|", render(snapshot.text, completionAcceptOutcome(snapshot, item, session, 7)))
    }
}
