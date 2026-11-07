package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindReplaceTest {
    private val regex = FindOptions(caseSensitive = true, regex = true)
    private val regexIgnoreCase = FindOptions(caseSensitive = false, regex = true)

    @Test
    fun `literal mode matches text verbatim`() {
        assertEquals(listOf(0..1, 6..7), findInText("ab cd AB", "ab", FindOptions()).matches)
        assertEquals(listOf(0..1, 4..5), findInText("a.b a.c", "a.", FindOptions(caseSensitive = true)).matches)
        assertEquals(emptyList(), findInText("abc", "a+", FindOptions()).matches)
    }

    @Test
    fun `regex mode honors case and multi-line anchors`() {
        assertEquals(listOf(0..2, 4..6), findInText("foo\nfoo Foo", "^foo", regex).matches)
        assertEquals(listOf(0..2, 4..6, 8..10), findInText("foo\nfoo Foo", "foo", regexIgnoreCase).matches)
        assertEquals(listOf(0..2, 5..7), findInText("foo\r\nbar", "\\w+$", regex).matches)
    }

    @Test
    fun `invalid patterns report an error instead of matches`() {
        val result = findInText("abc", "(a", regex)
        assertEquals(FindError.INVALID_PATTERN, result.error)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `zero length matches are skipped`() {
        assertEquals(emptyList(), findInText("abc", "x*", regex).matches)
        assertEquals(listOf(0..1, 5..5), findInText("aa b a", "a*", regex).matches)
    }

    @Test
    fun `deeply recursive patterns over a long line never crash`() {
        val line = "ab".repeat(500_000)
        for (pattern in listOf("((a)|(b))+", "(a|b)*c", "(?:ab|a)*")) {
            val result = findInText(line, pattern, regex)
            assertTrue(result.error != null || result.matches.isNotEmpty(), pattern)
            val (replacements, error) = replaceAllReplacements(line, pattern, regex, "x")
            assertTrue(error != null || replacements.isNotEmpty(), pattern)
        }
    }

    @Test
    fun `matches respect the limit`() {
        assertEquals(3, findInText("a".repeat(10), "a", regex, limit = 3).matches.size)
    }

    @Test
    fun `surrogate pairs are matched as whole code points`() {
        val text = "x😀y😀"
        assertEquals(listOf(1..2, 4..5), findInText(text, "😀", regex).matches)
    }

    @Test
    fun `replacement templates expand groups and escapes`() {
        val match = Regex("(?<key>\\w+)=(\\d+)?").find("name=")!!.asRegexMatch()
        assertEquals("name:[]", expandReplacement("$1:[$2]", match))
        assertEquals("name", expandReplacement("\${key}", match))
        assertEquals("\$1 \$9 \${nope}", expandReplacement("$$1 $9 \${nope}", match))
        assertEquals("a\nb\tc\\d", expandReplacement("a\\nb\\tc\\\\d", match))
        assertEquals("name=!", expandReplacement("$0!", match))
    }

    @Test
    fun `two digit group references fall back to one digit`() {
        val match = Regex("(a)(b)").find("ab")!!.asRegexMatch()
        assertEquals("a0", expandReplacement("$10", match))
    }

    @Test
    fun `literal replacement keeps dollar signs`() {
        val (replacements, error) = replaceAllReplacements("a a", "a", FindOptions(), "$1")
        assertNull(error)
        assertEquals(listOf(Replacement(0, 1, "$1"), Replacement(2, 3, "$1")), replacements)
    }

    @Test
    fun `regex replace all expands each match`() {
        val (replacements, error) = replaceAllReplacements("k1=v1, k2=v2", "(\\w+)=(\\w+)", regex, "$2=$1")
        assertNull(error)
        assertEquals(listOf(Replacement(0, 5, "v1=k1"), Replacement(7, 12, "v2=k2")), replacements)
    }

    @Test
    fun `replace all refuses an invalid pattern`() {
        assertEquals(FindError.INVALID_PATTERN, replaceAllReplacements("abc", "[", regex, "x").second)
    }

    @Test
    fun `replacement for a match checks the range still matches`() {
        assertEquals(Replacement(4, 7, "B"), replacementForMatch("foo bar", 4..6, "b\\w+", regex, "B"))
        assertNull(replacementForMatch("foo bar", 0..2, "b\\w+", regex, "B"))
        assertEquals(Replacement(0, 3, "x"), replacementForMatch("FOO", 0..2, "foo", FindOptions(), "x"))
        assertNull(replacementForMatch("FOO", 0..2, "foo", FindOptions(caseSensitive = true), "x"))
        assertEquals(Replacement(4, 5, "B"), replacementForMatch("a b b", 4..4, "(?<= )b", regex, "B"))
    }

    @Test
    fun `escaping makes a selection match literally`() {
        val escaped = escapeRegexLiteral("a.b(c)[d]$")
        assertEquals(listOf(2..11), findInText("x a.b(c)[d]$", escaped, regex).matches)
    }

    @Test
    fun `session replace all is one undoable edit`() {
        val host = FakeHost(CodeEditorState("foo bar foo baz foo"))
        val session = FindSession(host)
        session.open(replace = true)
        session.query.edit { replace(0, length, "foo") }
        session.replacement.edit { replace(0, length, "qux") }
        session.replaceAll()
        assertEquals("qux bar qux baz qux", host.state.text)
        assertEquals(3, session.replacedCount)
        assertTrue(host.state.undo())
        assertEquals("foo bar foo baz foo", host.state.text)
        assertFalse(host.state.canUndo)
    }

    @Test
    fun `session replace current replaces the selected match then selects the next`() {
        val host = FakeHost(CodeEditorState("a1 a2 a3"))
        val session = FindSession(host)
        session.open(replace = true)
        session.regex = true
        session.query.edit { replace(0, length, "a(\\d)") }
        session.replacement.edit { replace(0, length, "b$1") }
        session.incremental()
        assertEquals(Selection(0, 2), host.state.selectionInternal)
        session.replaceCurrent()
        assertEquals("b1 a2 a3", host.state.text)
        assertEquals(Selection(3, 5), host.state.selectionInternal)
        session.replaceCurrent()
        assertEquals("b1 b2 a3", host.state.text)
        assertEquals(Selection(6, 8), host.state.selectionInternal)
    }

    @Test
    fun `replace current only steps when the selection is not a match`() {
        val host = FakeHost(CodeEditorState("xa a"))
        val session = FindSession(host)
        session.open(replace = true)
        session.query.edit { replace(0, length, "a") }
        host.state.selectionInternal = Selection(0, 2)
        session.replaceCurrent()
        assertEquals("xa a", host.state.text)
        assertEquals(Selection(3, 4), host.state.selectionInternal)
    }

    @Test
    fun `read only sessions never replace or show the replace row`() {
        val host = FakeHost(CodeEditorState("aaa"), readOnly = true)
        val session = FindSession(host)
        session.open(replace = true)
        assertFalse(session.replaceVisible)
        session.query.edit { replace(0, length, "a") }
        session.replaceAll()
        assertEquals("aaa", host.state.text)
    }

    @Test
    fun `opening with a selection escapes it in regex mode`() {
        val host = FakeHost(CodeEditorState("a.b"))
        val session = FindSession(host)
        session.regex = true
        host.state.selectionInternal = Selection(0, 3)
        session.open()
        assertEquals("a\\.b", session.query.text.toString())
        assertEquals(listOf(0..2), session.matches())
    }

    private class FakeHost(override val state: CodeEditorState, override val readOnly: Boolean = false) : FindSession.Host {
        override fun select(start: Int, end: Int) {
            state.selectionInternal = Selection(start, end)
        }

        override fun apply(outcome: EditOutcome): Boolean = !readOnly && state.apply(outcome)

        override fun focusEditor() = Unit
    }
}
