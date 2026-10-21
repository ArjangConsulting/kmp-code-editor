package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeEditorStateTest {
    private var now = 0L

    private fun history() = EditHistory(coalesceWindowMillis = 1_000, clock = { now })

    private fun CodeEditorState.typeAll(text: String) {
        text.forEach { c ->
            val s = selectionInternal
            apply(EditOutcome(listOf(Replacement(s.start, s.end, c.toString())), Selection(s.start + 1), EditKind.TYPING), typed = c)
        }
    }

    @Test
    fun `every change bumps the version and notifies listeners`() {
        val state = CodeEditorState("ab")
        val seen = mutableListOf<String>()
        state.addTextListener { seen += it }
        state.insert("c")
        state.edit(listOf(TextEdit.insert(TextPosition.Zero, ">")))
        assertEquals(">abc", state.text)
        assertEquals(2, state.version)
        assertEquals(listOf("abc", ">abc"), seen)
        assertEquals(4, state.selectionEnd, "the caret is mapped through edits")
        state.setText(">abc")
        assertEquals(2, state.version, "setting identical text is not a change")
    }

    @Test
    fun `selection and caret positions are exposed in LSP terms`() {
        val state = CodeEditorState("ab\ncd")
        state.select(4, 1)
        assertEquals(TextRange(TextPosition(0, 1), TextPosition(1, 1)), state.selection)
        assertEquals(TextPosition(0, 1), state.caret)
        assertEquals("b\nc", state.selectedText)
        state.select(TextRange(TextPosition(1, 0), TextPosition(1, 2)))
        assertEquals("cd", state.selectedText)
    }

    @Test
    fun `typing coalesces into one undo step per word`() {
        val state = CodeEditorState("")
        state.typeAll("hello world")
        assertEquals("hello world", state.text)
        assertTrue(state.undo())
        assertEquals("hello", state.text)
        assertTrue(state.undo())
        assertEquals("", state.text)
        assertFalse(state.undo())
        assertTrue(state.redo())
        assertTrue(state.redo())
        assertEquals("hello world", state.text)
        assertEquals(11, state.selectionEnd)
        assertFalse(state.redo())
    }

    @Test
    fun `history breaks groups on time gaps caret moves and structural edits`() {
        val h = history()
        h.record(listOf(EditHistory.Change(0, "", "a")), Selection(0), Selection(1), EditKind.TYPING)
        now = 500
        h.record(listOf(EditHistory.Change(1, "", "b")), Selection(1), Selection(2), EditKind.TYPING)
        now = 2_000
        h.record(listOf(EditHistory.Change(2, "", "c")), Selection(2), Selection(3), EditKind.TYPING)
        h.seal()
        h.record(listOf(EditHistory.Change(3, "", "d")), Selection(3), Selection(4), EditKind.TYPING)
        h.record(listOf(EditHistory.Change(4, "", "e")), Selection(4), Selection(5), EditKind.OTHER)
        var text = "abcde"
        val steps = mutableListOf<String>()
        while (true) {
            text = h.undo(text)?.first ?: break
            steps += text
        }
        assertEquals(listOf("abcd", "abc", "ab", ""), steps)
    }

    @Test
    fun `backspace and forward delete coalesce`() {
        val h = history()
        h.record(listOf(EditHistory.Change(3, "d", "")), Selection(4), Selection(3), EditKind.DELETION)
        h.record(listOf(EditHistory.Change(2, "c", "")), Selection(3), Selection(2), EditKind.DELETION)
        val (restored, selection) = h.undo("abe")!!
        assertEquals("abcde", restored)
        assertEquals(Selection(4), selection)
        val h2 = history()
        h2.record(listOf(EditHistory.Change(1, "b", "")), Selection(1), Selection(1), EditKind.DELETION)
        h2.record(listOf(EditHistory.Change(1, "c", "")), Selection(1), Selection(1), EditKind.DELETION)
        assertEquals("abcd", h2.undo("ad")!!.first)
    }

    @Test
    fun `multi replacement edits undo and redo as one step`() {
        val state = CodeEditorState("a\nb\nc")
        state.selectAll()
        val outcome = indentOutcome(state.snapshot(), state.selectionInternal, KotlinRules)
        state.apply(outcome)
        assertEquals("    a\n    b\n    c", state.text)
        state.undo()
        assertEquals("a\nb\nc", state.text)
        assertEquals(Selection(0, 5), state.selectionInternal)
        state.redo()
        assertEquals("    a\n    b\n    c", state.text)
    }

    @Test
    fun `a new edit clears redo`() {
        val state = CodeEditorState("x")
        state.insert("y")
        state.undo()
        assertTrue(state.canRedo)
        state.insert("z")
        assertFalse(state.canRedo)
    }

    @Test
    fun `reset clears history and folds`() {
        val state = CodeEditorState("a")
        state.insert("b")
        state.resetText("new")
        assertFalse(state.canUndo)
        assertEquals("new", state.text)
    }

    @Test
    fun `folds shift with inserted lines and unfold when edited`() {
        val state = CodeEditorState("x\nfun a() {\n  1\n  2\n}")
        state.foldingRanges = listOf(FoldingRange(1, 3))
        state.fold(1)
        assertEquals(setOf(1), state.foldedLines)
        state.edit(listOf(TextEdit.insert(TextPosition.Zero, "line\n")))
        assertEquals(setOf(2), state.foldedLines)
        state.foldingRanges = listOf(FoldingRange(2, 4))
        state.edit(listOf(TextEdit.insert(TextPosition(3, 0), "!")))
        assertEquals(emptySet(), state.foldedLines, "editing inside a fold unfolds it")
    }

    @Test
    fun `fold toggles only known ranges and unfoldToShow expands`() {
        val state = CodeEditorState("a {\n b\n c\n}")
        state.fold(0)
        assertTrue(state.foldedLines.isEmpty())
        state.foldingRanges = listOf(FoldingRange(0, 2))
        state.toggleFold(0)
        assertEquals(setOf(0), state.foldedLines)
        state.unfoldToShow(2)
        assertTrue(state.foldedLines.isEmpty())
        state.foldAll()
        assertEquals(setOf(0), state.foldedLines)
        state.unfoldAll()
        assertTrue(state.foldedLines.isEmpty())
    }

    @Test
    fun `reveal requests are distinct even for the same line`() {
        val state = CodeEditorState("a\nb")
        state.revealLine(1)
        val first = state.revealRequest
        state.revealLine(1)
        assertEquals(TextPosition(1, 0), state.revealRequest?.range?.start)
        assertTrue(state.revealRequest != first)
    }
    @Test
    fun `edits inserting at the same position preserve list order and history`() {
        val state = CodeEditorState("ab")
        state.edit(listOf(TextEdit.insert(TextPosition(0, 1), "1"), TextEdit.insert(TextPosition(0, 1), "2")))
        assertEquals("a12b", state.text)
        assertTrue(state.undo())
        assertEquals("ab", state.text)
        assertTrue(state.redo())
        assertEquals("a12b", state.text)
    }

    @Test
    fun `folds shift for every supported line terminator`() {
        for (terminator in listOf("\n", "\r\n", "\r")) {
            val state = CodeEditorState("x\nhead\nbody\nend")
            state.foldingRanges = listOf(FoldingRange(1, 2))
            state.fold(1)
            state.edit(listOf(TextEdit.insert(TextPosition.Zero, "line" + terminator)))
            assertEquals(setOf(2), state.foldedLines, terminator)
        }
    }

    @Test
    fun `undo remaps collapsed folds below the reverted edit`() {
        val state = CodeEditorState("x\nhead\nbody\nend")
        state.foldingRanges = listOf(FoldingRange(1, 2))
        state.fold(1)
        state.edit(listOf(TextEdit.insert(TextPosition.Zero, "line\n")))
        state.foldingRanges = listOf(FoldingRange(2, 3))
        assertEquals(setOf(2), state.foldedLines)
        state.undo()
        assertEquals(setOf(1), state.foldedLines)
        state.foldingRanges = listOf(FoldingRange(1, 2))
        state.redo()
        assertEquals(setOf(2), state.foldedLines)
    }

}
