package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnippetSessionTest {
    private fun session(source: String): Pair<CodeEditorState, SnippetSession> {
        val parsed = parseSnippet(source)
        val state = CodeEditorState(parsed.text, parsed.selectionStart until parsed.selectionEnd)
        return state to SnippetSession().also { it.start(state, parsed.tabStops) }
    }

    @Test
    fun numericStopsNavigateBothDirectionsAndFollowPlaceholderEdits() {
        val (state, session) = session("tap id=\${1:id} text=\${2:text}$0")
        assertEquals("id", state.selectedText)
        state.edit(listOf(TextEdit(state.selection, "button")))
        assertTrue(session.move(state, true))
        assertEquals("text", state.selectedText)
        assertTrue(session.move(state, false))
        assertEquals("button", state.selectedText)
        assertTrue(session.move(state, true))
        state.edit(listOf(TextEdit(state.selection, "hello")))
        assertTrue(session.move(state, true))
        assertEquals(state.text.length, state.selectionEnd)
        assertFalse(session.move(state, true))
    }

    @Test
    fun replacingOuterPlaceholderDropsNestedStops() {
        val (state, session) = session("\${1:outer \${2:inner}} \${3:last}$0")
        state.edit(listOf(TextEdit(state.selection, "replacement")))
        assertTrue(session.move(state, true))
        assertEquals("last", state.selectedText)
    }

    @Test
    fun outsideEditsEscapeResetAndUndoEndTheSession() {
        val source = "tap \${1:id} \${2:text}$0"
        val (state, session) = session(source)
        state.edit(listOf(TextEdit(TextRange.at(TextPosition(0, 0)), "outside")))
        assertFalse(session.move(state, true))
        val (resetState, resetSession) = session(source)
        resetState.resetText(resetState.text, resetState.selectionEnd)
        assertFalse(resetSession.move(resetState, true))
        val (undoState, undoSession) = session(source)
        undoState.edit(listOf(TextEdit(undoState.selection, "new")))
        undoState.undo()
        assertFalse(undoSession.move(undoState, true))
        val (cancelState, cancelSession) = session(source)
        assertTrue(cancelSession.cancel())
        assertFalse(cancelSession.move(cancelState, true))
    }

    @Test
    fun caretLeavingThePlaceholderEndsNavigation() {
        val (state, session) = session("tap $1 text=$2$0")
        state.select(0)
        assertFalse(session.move(state, true))
    }

    @Test
    fun stopsSortNumericallyWithZeroLastAndSupportEmptyPlaceholders() {
        val parsed = parseSnippet("$2 $1 $0")
        assertEquals(listOf(Selection(1), Selection(0), Selection(2)), parsed.tabStops)
        val (state, session) = session("tap id=$1 text=$2$0")
        val caret = state.selectionEnd
        state.edit(listOf(TextEdit(state.selection, "button")))
        assertTrue(session.move(state, true))
        assertEquals(caret + "button text=".length, state.selectionEnd)
    }
}
