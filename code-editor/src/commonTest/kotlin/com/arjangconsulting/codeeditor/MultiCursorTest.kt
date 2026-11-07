package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiCursorTest {
    private var now = 0L

    private fun type(state: CodeEditorState, c: Char) {
        val snapshot = state.snapshot()
        val outcome = combineOutcomes(state.selectionSet) { s, _ ->
            typeCharacterOutcome(snapshot, s, c, PlainText, TokenLookup.AllCode, autoClose = true)
        }
        state.apply(outcome!!, typed = c)
    }

    private fun backspace(state: CodeEditorState) {
        val snapshot = state.snapshot()
        state.apply(combineOutcomes(state.selectionSet) { s, _ -> backspaceOutcome(snapshot, s, PlainText) }!!)
    }

    private fun carets(vararg offsets: Int) = Selections(Selection(offsets.last()), offsets.dropLast(1).map { Selection(it) })

    @Test
    fun `normalizing merges overlaps and same position carets and keeps the primary`() {
        val merged = normalizeSelections(Selection(4), listOf(Selection(1, 3), Selection(2, 6), Selection(9), Selection(9)))
        assertEquals(Selections(Selection(1, 6), listOf(Selection(9))), merged)
        val touching = normalizeSelections(Selection(5, 3), listOf(Selection(3)))
        assertEquals(Selections(Selection(5, 3)), touching, "a caret touching a selection merges, keeping its direction")
        val adjacent = normalizeSelections(Selection(3, 5), listOf(Selection(1, 3)))
        assertEquals(Selections(Selection(3, 5), listOf(Selection(1, 3))), adjacent, "adjacent selections stay separate")
    }

    @Test
    fun `typing at several carets edits each and maps every caret`() {
        val state = CodeEditorState("ab\nab\nab")
        state.selectionSet = carets(1, 4, 7)
        type(state, 'X')
        assertEquals("aXb\naXb\naXb", state.text)
        assertEquals(listOf(Selection(2), Selection(6), Selection(10)), state.selectionSet.all)
        assertEquals(Selection(10), state.selectionInternal, "the primary stays the last added caret")
    }

    @Test
    fun `auto closing pairs apply at each caret`() {
        val state = CodeEditorState("a\nb")
        state.selectionSet = carets(1, 3)
        type(state, '(')
        assertEquals("a()\nb()", state.text)
        assertEquals(listOf(Selection(2), Selection(6)), state.selectionSet.all)
    }

    @Test
    fun `backspace at adjacent carets merges them`() {
        val state = CodeEditorState("abcd")
        state.selectionSet = carets(2, 3)
        backspace(state)
        assertEquals("ad", state.text)
        assertEquals(Selections(Selection(1)), state.selectionSet)
    }

    @Test
    fun `an edit overlapping an earlier caret's edit is skipped`() {
        val state = CodeEditorState("x = 1")
        state.selectionSet = carets(0, 2)
        val snapshot = state.snapshot()
        val outcome = combineOutcomes(state.selectionSet) { s, _ -> toggleLineCommentOutcome(snapshot, s, KotlinRules) }!!
        state.apply(outcome)
        assertEquals("// x = 1", state.text, "two carets on one line toggle the comment once")
    }

    @Test
    fun `pieces are distributed by selection index`() {
        val state = CodeEditorState("a b c")
        state.selectionSet = Selections(Selection(4, 5), listOf(Selection(0, 1), Selection(2, 3)))
        val pieces = listOf("1", "22", "333")
        state.apply(
            combineOutcomes(state.selectionSet) { s, i ->
                EditOutcome(listOf(Replacement(s.start, s.end, pieces[i])), Selection(s.start + pieces[i].length))
            }!!,
        )
        assertEquals("1 22 333", state.text)
    }

    @Test
    fun `typing at several carets coalesces into one undo step that restores the carets`() {
        val state = CodeEditorState("a\na")
        state.selectionSet = carets(1, 3)
        type(state, 'x')
        type(state, 'y')
        type(state, 'z')
        assertEquals("axyz\naxyz", state.text)
        assertTrue(state.undo())
        assertEquals("a\na", state.text)
        assertEquals(carets(1, 3), state.selectionSet)
        assertFalse(state.canUndo)
        assertTrue(state.redo())
        assertEquals("axyz\naxyz", state.text)
        assertEquals(listOf(Selection(4), Selection(9)), state.selectionSet.all)
    }

    @Test
    fun `backspace at several carets coalesces`() {
        val state = CodeEditorState("abc\nabc")
        state.selectionSet = carets(3, 7)
        backspace(state)
        backspace(state)
        assertEquals("a\na", state.text)
        assertTrue(state.undo())
        assertEquals("abc\nabc", state.text)
        assertFalse(state.canUndo)
    }

    @Test
    fun `multi change history merges only matching steps`() {
        val history = EditHistory(clock = { now })
        val before = Selections(Selection(3), listOf(Selection(0)))
        history.record(listOf(EditHistory.Change(3, "", "a"), EditHistory.Change(0, "", "a")), before, before, EditKind.TYPING)
        history.record(listOf(EditHistory.Change(5, "", "b"), EditHistory.Change(1, "", "b")), before, before, EditKind.TYPING)
        assertEquals("xyz", history.undo("abxyzab")?.first)
        val separate = EditHistory(clock = { now })
        separate.record(listOf(EditHistory.Change(3, "", "a"), EditHistory.Change(0, "", "a")), before, before, EditKind.TYPING)
        separate.record(listOf(EditHistory.Change(1, "", "b")), before, before, EditKind.TYPING)
        assertEquals("axyza", separate.undo("abxyza")?.first)
    }

    @Test
    fun `setting the primary selection removes secondary selections`() {
        val state = CodeEditorState("abc")
        state.selectionSet = carets(0, 2)
        state.selectionInternal = Selection(1)
        assertEquals(Selections(Selection(1)), state.selectionSet)
    }

    @Test
    fun `public selections api`() {
        val state = CodeEditorState("ab\ncd")
        state.setSelections(listOf(TextRange(TextPosition(1, 0), TextPosition(1, 1)), TextRange.at(TextPosition(0, 1))))
        assertEquals(
            listOf(TextRange(TextPosition(1, 0), TextPosition(1, 1)), TextRange.at(TextPosition(0, 1))),
            state.selections,
        )
        assertEquals("c", state.selectedText)
        state.setText("xy")
        assertEquals(1, state.selections.size)
    }

    @Test
    fun `lsp edits shift every selection`() {
        val state = CodeEditorState("a b")
        state.selectionSet = carets(1, 3)
        state.edit(listOf(TextEdit.insert(TextPosition.Zero, ">>")))
        assertEquals(listOf(Selection(3), Selection(5)), state.selectionSet.all)
    }

    @Test
    fun `next occurrence wraps skips selected ones and stops when all are selected`() {
        val text = "foo bar foo baz foo"
        val first = Selections(Selection(8, 11))
        val second = addNextOccurrence(text, first)!!
        assertEquals(Selection(16, 19), second.primary)
        val third = addNextOccurrence(text, second)!!
        assertEquals(Selection(0, 3), third.primary)
        assertNull(addNextOccurrence(text, third))
        assertNull(addNextOccurrence(text, Selections(Selection(2))))
    }

    @Test
    fun `ranges become selections with the one near the caret as primary`() {
        val selections = selectionsForRanges(listOf(0..2, 8..10, 16..18), nearOffset = 9)!!
        assertEquals(Selection(8, 11), selections.primary)
        assertEquals(3, selections.size)
        assertNull(selectionsForRanges(emptyList(), 0))
    }

    @Test
    fun `toggling adds a caret and removes one but never the last`() {
        val one = Selections(Selection(2))
        val two = toggleCaret(one, 5)
        assertEquals(Selections(Selection(5), listOf(Selection(2))), two)
        assertEquals(Selections(Selection(2)), toggleCaret(two, 5))
        assertEquals(Selections(Selection(5)), toggleCaret(two, 2))
        assertEquals(one, toggleCaret(one, 2))
    }
}
