package com.arjangconsulting.codeeditor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldingTest {
    private fun ranges(text: String, rules: TokenRules = KotlinRules): List<FoldingRange> {
        val snapshot = DocumentSnapshot(text, 1)
        return computeFoldingRanges(snapshot, rules, Highlighter(rules).also { it.update(snapshot) })
    }

    @Test
    fun `brackets spanning lines fold and keep the closing line visible`() {
        val text = """
            fun main() {
                if (x) {
                    y()
                }
                val list = listOf(
                    1,
                    2)
            }
        """.trimIndent()
        assertEquals(listOf(FoldingRange(0, 6), FoldingRange(1, 2), FoldingRange(4, 6)), ranges(text))
    }

    @Test
    fun `brackets in strings and comments are ignored`() {
        assertEquals(emptyList(), ranges("val s = \"{\"\n// {\nval t = 1\n}"))
    }

    @Test
    fun `adjacent open and close lines do not fold`() {
        assertEquals(emptyList(), ranges("fun a() {\n}"))
    }

    @Test
    fun `multi line block comments fold`() {
        val text = "/*\n * doc\n */\nfun a() = 1"
        assertEquals(listOf(FoldingRange(0, 2, FoldingRangeKind.COMMENT)), ranges(text))
    }

    @Test
    fun `json objects and arrays fold`() {
        val json = "{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {}\n}"
        assertEquals(listOf(FoldingRange(0, 5), FoldingRange(1, 3)), ranges(json, JsonRules))
    }

    @Test
    fun `fold layout hides collapsed bodies and maps rows`() {
        val layout = FoldLayout(10, listOf(FoldingRange(1, 4), FoldingRange(2, 3), FoldingRange(6, 8)), setOf(1, 6))
        assertContentEquals(intArrayOf(0, 1, 5, 6, 9), layout.visibleLines)
        assertEquals(5, layout.rowCount)
        assertEquals(1, layout.rowOf(3))
        assertEquals(2, layout.rowOf(5))
        assertEquals(9, layout.lineAt(4))
        assertTrue(layout.isHidden(7))
        assertFalse(layout.isHidden(6))
        assertEquals(setOf(1, 6), layout.collapsedRanges.keys)
    }

    @Test
    fun `nested collapsed folds inside a collapsed fold stay hidden`() {
        val layout = FoldLayout(6, listOf(FoldingRange(0, 4), FoldingRange(1, 3)), setOf(0, 1))
        assertContentEquals(intArrayOf(0, 5), layout.visibleLines)
        assertEquals(setOf(0), layout.collapsedRanges.keys)
    }
    @Test
    fun `folding at the caret chooses the innermost region and moves hidden selections to its header`() {
        val state = CodeEditorState("outer\ninner\nbody\nend\nlast")
        state.foldingRanges = listOf(FoldingRange(0, 3), FoldingRange(1, 2))
        state.select(state.snapshot().lineStart(2))
        assertTrue(state.foldAtCaret(collapse = true))
        assertEquals(setOf(1), state.foldedLines)
        assertEquals(1, state.caret.line)
        assertTrue(state.foldAtCaret(collapse = false))
        assertTrue(state.foldedLines.isEmpty())
        state.select(state.text.length)
        assertFalse(state.foldAtCaret(collapse = true))
    }

    @Test
    fun `folding all moves a selection whose anchor would be hidden`() {
        val state = CodeEditorState("head\nbody\nend\nlast")
        state.foldingRanges = listOf(FoldingRange(0, 1))
        state.select(state.snapshot().lineStart(1), state.text.length)
        state.foldAll()
        state.keepSelectionVisible()
        assertEquals(0, state.caret.line)
        assertTrue(state.selectionInternal.collapsed)
    }

}
